package dev.codeassist.ndk

import dev.ide.platform.log.Logger
import dev.ide.platform.notify.ProgressHandle
import dev.ide.platform.notify.UserMessages
import dev.ide.plugin.PluginRegistration
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The clang and lld this plugin ships, and how to run them.
 *
 * The toolchain arrives in two halves, for a reason that is not cosmetic. Since Android 10 an app may not
 * `exec()` a file it wrote into its own storage, so anything we RUN has to come out of the installed
 * package's own library directory, which the installer fills and nothing can write to. Anything we only READ
 * has no such restriction, and headers and link libraries are far too many files to package that way, so
 * they ride along as one archive and are unpacked into [PluginRegistration.dataDir] on first use.
 *
 *  - executables: `lib*.so` in the APK's `jniLibs`, found through [PluginRegistration.nativeLibrary]
 *  - headers and link libraries: `assets/toolchain.zip`, unpacked to `<dataDir>/toolchain`
 *
 * Every member answers null or [Status.Unavailable] rather than throwing when the device has no toolchain:
 * this plugin installs on any device, and an ABI it was not built for is an ordinary state to be in, not a
 * broken install.
 */
class NdkToolchain(
    private val reg: PluginRegistration,
    private val log: Logger,
    private val messages: UserMessages?,
) {

    /** The multicall driver: `clang`, `ar`, `nm`, `strip`, … all selected by the first argument. */
    private val driver: Path? = reg.nativeLibrary(DRIVER_LIB)

    /**
     * The linker, as a second copy of the same binary under a name that selects it.
     *
     * We never run this one ourselves; clang does, through `--ld-path`. That is the whole reason it needs a
     * name of its own: there is no way to make clang pass the linker a `-flavor gnu` first argument, so the
     * flavor has to come from the file name. See tools/ndk-toolchain/README.md.
     */
    private val linker: Path? = reg.nativeLibrary(LINKER_LIB)

    /** Where the unpacked headers and link libraries live. */
    private val root: Path = reg.dataDir.resolve("toolchain")

    /** Whether this device can compile at all, and why not when it cannot. */
    sealed interface Status {
        data class Ready(val version: String) : Status
        data class Unavailable(val reason: String) : Status
    }

    @Volatile
    private var unpacked = false

    /**
     * Make the toolchain usable, unpacking the read-only half if this is the first run, and report what the
     * compiler says about itself.
     *
     * Safe to call repeatedly and from anywhere: the unpack and the version check happen once, and every call
     * after that answers from memory.
     */
    @Synchronized
    fun prepare(): Status {
        val driver = driver ?: return Status.Unavailable(
            "This plugin ships no compiler for this device's ABI (${System.getProperty("os.arch")})."
        )
        if (!unpacked) {
            val result = runCatching { unpackAssets() }
            result.exceptionOrNull()?.let {
                log.warn("could not unpack the toolchain", it)
                return Status.Unavailable("The toolchain could not be unpacked: ${it.message}")
            }
            unpacked = true
        }
        ready?.let { return it }
        val version = run(listOf(driver.toString(), "clang", "--version"))
        return if (version.ok) Status.Ready(version.output.lineSequence().firstOrNull().orEmpty()).also { ready = it }
        else Status.Unavailable("The compiler did not run: ${version.output.take(200)}")
    }

    @Volatile
    private var ready: Status.Ready? = null

    /**
     * Unpack `assets/toolchain.zip` into [root], once per version.
     */
    private fun unpackAssets() {
        val stamp = root.resolve(".version")
        val current = layoutStamp()
        if (stamp.exists() && runCatching { stamp.readText().trim() }.getOrNull() == current) return

        val stream: InputStream = javaClass.classLoader?.getResourceAsStream(ASSET_ARCHIVE)
            ?: throw IllegalStateException("$ASSET_ARCHIVE is not packaged in this plugin")

        val progress = messages?.startProgress("Unpacking the NDK toolchain")
        try {
            if (root.exists()) root.toFile().deleteRecursively()
            Files.createDirectories(root)

            var files = 0
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = root.resolve(entry.name).normalize()
                    require(target.startsWith(root)) { "entry escapes the toolchain directory: ${entry.name}" }
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                        files++
                        if (files % 200 == 0) progress?.detail = entry.name.substringAfterLast('/')
                    }
                    zip.closeEntry()
                }
            }
            stamp.writeText(current)
            log.info("unpacked $files toolchain files into $root")
        } finally {
            progress?.finish()
        }
    }

    /**
     * The packaged `android_native_app_glue`, or null when the toolchain has not been unpacked.
     */
    val nativeAppGlueDir: Path?
        get() = root.resolve("native_app_glue").takeIf { Files.isDirectory(it) }

    /**
     * What the unpacked tree was unpacked FROM, so an updated plugin re-unpacks.
     */
    private fun layoutStamp(): String {
        val d = driver ?: return LAYOUT_VERSION
        val identity = runCatching {
            "${Files.size(d)}-${Files.getLastModifiedTime(d).toMillis()}"
        }.getOrDefault("unknown")
        return "$LAYOUT_VERSION-$identity"
    }

    /**
     * The shared C++ runtime an app linked with `c++_shared` has to carry in its APK beside its own library.
     */
    fun sharedCxxRuntime(abi: NdkAbi = hostAbi): Path? =
        root.resolve("sysroot/usr/lib/${abi.libTriple}/libc++_shared.so").takeIf { Files.isRegularFile(it) }

    /**
     * The ABI this device runs.
     */
    val hostAbi: NdkAbi by lazy {
        NdkAbi.forDevice(runCatching { android.os.Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList()))
    }

    data class ToolResult(val ok: Boolean, val output: String, val bufferName: String = "<stdin>")

    /**
     * Compile [source] to [output].
     */
    fun compile(
        source: Path,
        output: Path,
        cpp: Boolean,
        extraFlags: List<String> = emptyList(),
        depFile: Path? = null,
        abi: NdkAbi = hostAbi,
    ): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        Files.createDirectories(output.parent)
        return run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags(abi))
                addAll(extraFlags)
                if (depFile != null) { add("-MD"); add("-MF"); add(depFile.toString()) }
                add("-c"); add(source.toString())
                add("-o"); add(output.toString())
            }
        )
    }

    /**
     * Check the buffer [text] for errors without producing anything.
     */
    fun syntaxCheck(
        source: Path,
        text: String,
        cpp: Boolean,
        extraFlags: List<String> = emptyList(),
        cancellation: ToolCancellation? = null,
    ): ToolResult = viaDaemon("check", source, text, cpp, extraFlags, cancellation = cancellation)
        ?: withPreamble(source, text, cpp, extraFlags, usableBefore = text.length, what = "check", cancellation) { driver, preamble ->
        run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags())
                addAll(extraFlags)
                addAll(preambleFlags(preamble))
                add("-fsyntax-only")
                add("-fno-caret-diagnostics")
                add("-fno-color-diagnostics")
                add("-fdiagnostics-print-source-range-info")
                add("-x"); add(languageFor(source, cpp))
                add("-")
            },
            stdin = text,
            workingDir = source.parent,
            cancellation = cancellation,
        )
    }

    /**
     * Ask clang what could be typed at [line]:[column].
     */
    fun complete(
        source: Path,
        text: String,
        line: Int,
        column: Int,
        offset: Int,
        cpp: Boolean,
        extraFlags: List<String> = emptyList(),
        cancellation: ToolCancellation? = null,
    ): ToolResult = viaDaemon("complete", source, text, cpp, extraFlags, line, column, cancellation)
        ?: withPreamble(source, text, cpp, extraFlags, usableBefore = offset, what = "completion", cancellation) { driver, preamble ->
        run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags())
                addAll(extraFlags)
                addAll(preambleFlags(preamble))
                add("-fsyntax-only")
                add("-Xclang"); add("-code-completion-at=-:$line:$column")
                add("-Xclang"); add("-code-completion-macros")
                add("-Xclang"); add("-code-completion-brief-comments")
                add("-x"); add(languageFor(source, cpp))
                add("-")
            },
            timeoutSeconds = 20,
            stdin = text,
            workingDir = source.parent,
            cancellation = cancellation,
        )
    }

    /**
     * Classify the identifiers in [text].
     */
    fun semanticTokens(source: Path, text: String, cpp: Boolean, extraFlags: List<String>): ToolResult? =
        viaDaemon("tokens", source, text, cpp, extraFlags, cancellation = null)

    // -----------------------------------------------------------------------------------------------
    // The resident daemon (see ClangDaemon)
    // -----------------------------------------------------------------------------------------------

    private val daemon: ClangDaemon? by lazy {
        val executable = reg.nativeLibrary(ClangDaemon.LIB) ?: return@lazy null
        if (!Files.isRegularFile(executable)) return@lazy null
        ClangDaemon(executable, executable.parent, reg.dataDir.resolve("daemon"), log)
    }

    private fun viaDaemon(
        command: String,
        source: Path,
        text: String,
        cpp: Boolean,
        extraFlags: List<String>,
        line: Int = 0,
        column: Int = 0,
        cancellation: ToolCancellation?,
    ): ToolResult? {
        val daemon = daemon ?: return null
        val started = System.nanoTime()
        val args = buildList {
            addAll(commonFlags())
            addAll(extraFlags)
            if (cpp) add("-fpch-instantiate-templates")
            add("-x"); add(languageFor(source, cpp))
        }
        val reply = daemon.request(command, source.toString(), text, args, line, column, cancellation = cancellation,
            onTurn = { waited -> if (waited > 5) log.debug("clang $command waited $waited ms for the daemon") })
        if (reply == null) {
            return if (cancellation?.isCancelled == true) ToolResult(false, "cancelled") else null
        }
        if (!reply.ok) {
            log.info("the resident clang could not $command ${source.fileName}: ${reply.payload.take(200)}")
            return null
        }
        log.debug("clang $command: ${(System.nanoTime() - started) / 1_000_000} ms (daemon)")
        return ToolResult(true, reply.payload, bufferName = source.toString())
    }

    // -----------------------------------------------------------------------------------------------
    // The precompiled preamble (see ClangPreamble / PreambleCache)
    // -----------------------------------------------------------------------------------------------

    private val preambles: PreambleCache by lazy {
        PreambleCache(reg.dataDir.resolve("preambles"), log, build = ::buildPreamble)
    }

    private fun withPreamble(
        source: Path,
        text: String,
        cpp: Boolean,
        extraFlags: List<String>,
        usableBefore: Int,
        what: String,
        cancellation: ToolCancellation?,
        invoke: (driver: Path, preamble: PreambleCache.Ready?) -> ToolResult,
    ): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        val started = System.nanoTime()
        fun timed(result: ToolResult, how: String): ToolResult {
            log.debug("clang $what: ${(System.nanoTime() - started) / 1_000_000} ms $how")
            return result
        }

        val end = ClangPreamble.end(text)
        val sourceDir = source.parent
        if (end == 0 || end > usableBefore || sourceDir == null) return timed(invoke(driver, null), "(no preamble)")

        val request = PreambleCache.Request(
            text = text.substring(0, end),
            cpp = cpp,
            flags = extraFlags,
            sourceDir = sourceDir,
            toolchainStamp = layoutStamp(),
        )
        val ready = preambles.readyOrQueue(request, ClangPreamble.utf8Length(text, end))
            ?: return timed(invoke(driver, null), "(preamble queued)")

        val result = invoke(driver, ready)
        if (cancellation?.isCancelled == true) return timed(result, "(cancelled)")
        if (!PreambleCache.isStale(result.output)) return timed(result, "(preamble)")
        log.info("a precompiled preamble went stale: ${result.output.lineSequence().firstOrNull().orEmpty().take(200)}")
        preambles.rebuild(request)
        return timed(invoke(driver, null), "(stale preamble, rebuilt)")
    }

    private fun preambleFlags(preamble: PreambleCache.Ready?): List<String> =
        if (preamble == null) emptyList()
        else listOf("-include-pch", preamble.pch.toString(), "-Xclang", "-preamble-bytes=${preamble.bytes},1")

    private fun buildPreamble(request: PreambleCache.Request, header: Path, pch: Path): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        return run(
            buildList {
                add(driver.toString()); add("clang")
                if (request.cpp) add("--driver-mode=g++")
                addAll(commonFlags())
                addAll(request.flags)
                if (request.cpp) add("-fpch-instantiate-templates")
                add("-iquote"); add(request.sourceDir.toString())
                add("-x"); add(if (request.cpp) "c++-header" else "c-header")
                add(header.toString())
                add("-o"); add(pch.toString())
            },
            timeoutSeconds = 60,
            workingDir = request.sourceDir,
        )
    }

    /**
     * Link [objects] into a shared library at [output].
     *
     * [libraryDirs] contains extra directories in which the linker should search for libraries.
     */
    fun linkShared(
        objects: List<Path>,
        output: Path,
        libs: List<String> = emptyList(),
        cpp: Boolean = false,
        stl: String = "c++_static",
        abi: NdkAbi = hostAbi,
        libraryDirs: List<Path> = emptyList(),
    ): ToolResult = link(
        objects = objects,
        output = output,
        libs = libs,
        cpp = cpp,
        stl = stl,
        shared = true,
        abi = abi,
        libraryDirs = libraryDirs,
    )

    /**
     * Link [objects] into a program at [output], for Run.
     */
    fun linkExecutable(
        objects: List<Path>,
        output: Path,
        libs: List<String> = emptyList(),
        cpp: Boolean = false,
        stl: String = "c++_static",
        abi: NdkAbi = hostAbi,
        libraryDirs: List<Path> = emptyList(),
    ): ToolResult = link(
        objects = objects,
        output = output,
        libs = libs,
        cpp = cpp,
        stl = stl,
        shared = false,
        abi = abi,
        libraryDirs = libraryDirs,
    )

    private fun link(
        objects: List<Path>,
        output: Path,
        libs: List<String>,
        cpp: Boolean,
        stl: String,
        shared: Boolean,
        abi: NdkAbi,
        libraryDirs: List<Path>,
    ): ToolResult {
        val driver = driver ?: return ToolResult(false, "no compiler for this device's ABI")
        val linker = linker ?: return ToolResult(false, "no linker for this device's ABI")
        Files.createDirectories(output.parent)
        return run(
            buildList {
                add(driver.toString()); add("clang")
                if (cpp) add("--driver-mode=g++")
                addAll(commonFlags(abi))
                if (shared) add("-shared")
                add("-Wl,--no-undefined")
                if (cpp) when (stl) {
                    "c++_static" -> add("-static-libstdc++")
                    "none" -> add("-nostdlib++")
                }
                addAll(PAGE_SIZE_FLAGS)
                add("--ld-path=$linker")
                objects.forEach { add(it.toString()) }

                // Extra library search directories, for example:
                // app/src/injLibs/android.arm64-v8a
                libraryDirs.forEach { directory ->
                    if (Files.isDirectory(directory)) {
                        add("-L${directory.toAbsolutePath()}")
                    }
                }

                libs.forEach { add("-l$it") }
                add("-o"); add(output.toString())
            }
        )
    }

    /**
     * The Run launcher.
     */
    val runLauncher: Path?
        get() = reg.nativeLibrary(RUN_LAUNCHER_LIB)?.takeIf { Files.isRegularFile(it) }

    /** The directory the toolchain's own libraries are in, for a child process's `LD_LIBRARY_PATH`. */
    val libraryDir: Path? get() = driver?.parent

    private fun languageFor(source: Path, cpp: Boolean): String {
        val header = HEADER_EXTENSIONS.any { source.fileName.toString().endsWith(it) }
        return when {
            cpp && header -> "c++-header"
            cpp -> "c++"
            header -> "c-header"
            else -> "c"
        }
    }

    private fun commonFlags(abi: NdkAbi = hostAbi): List<String> = listOf(
        "--target=${abi.target}",
        "-resource-dir", root.resolve("lib-clang").resolve(LLVM_MAJOR).toString(),
        "--sysroot", root.resolve("sysroot").toString(),
    ) + abi.extraFlags

    private fun run(
        command: List<String>,
        timeoutSeconds: Long = 120,
        stdin: String? = null,
        workingDir: Path? = null,
        cancellation: ToolCancellation? = null,
    ): ToolResult {
        val libDir = driver?.parent ?: return ToolResult(false, "no toolchain")
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    workingDir?.let { if (Files.isDirectory(it)) directory(it.toFile()) }
                    environment()["LD_LIBRARY_PATH"] = libDir.toString()
                }
                .start()
            cancellation?.attach(process)

            val timedOut = AtomicBoolean(false)
            val watchdog = WATCHDOG.schedule({
                timedOut.set(true)
                process.destroyForcibly()
            }, timeoutSeconds, TimeUnit.SECONDS)

            try {
                if (stdin != null) {
                    runCatching { process.outputStream.use { it.write(stdin.toByteArray()) } }
                } else {
                    runCatching { process.outputStream.close() }
                }

                val output = try {
                    process.inputStream.bufferedReader().readText()
                } catch (e: IOException) {
                    if (timedOut.get() || cancellation?.isCancelled == true) "" else throw e
                }
                val exit = process.waitFor()
                if (timedOut.get()) return ToolResult(false, "timed out after ${timeoutSeconds}s\n$output")
                if (cancellation?.isCancelled == true) return ToolResult(false, "cancelled")
                ToolResult(exit == 0, output.trim())
            } finally {
                watchdog.cancel(false)
            }
        } catch (t: Throwable) {
            log.warn("could not run ${command.firstOrNull()}", t)
            ToolResult(false, t.message ?: t.toString())
        }
    }

    companion object {
        private val WATCHDOG: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ndk-tool-watchdog").apply { isDaemon = true }
        }

        const val DRIVER_LIB = "llvmtools"
        const val LINKER_LIB = "ld-gnu-lld"
        const val RUN_LAUNCHER_LIB = "ndkrun"

        const val ASSET_ARCHIVE = "assets/toolchain.zip"
        const val LLVM_MAJOR = "18"

        const val API_LEVEL = 26
        const val TARGET_TRIPLE = "aarch64-linux-android$API_LEVEL"
        const val LAYOUT_VERSION = "1"

        val PAGE_SIZE_FLAGS = listOf("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384")

        private val HEADER_EXTENSIONS = listOf(".h", ".hpp", ".hh", ".hxx", ".inl")
    }
}