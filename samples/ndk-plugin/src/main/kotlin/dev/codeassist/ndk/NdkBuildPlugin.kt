package dev.codeassist.ndk

import dev.ide.build.BuildConfiguration
import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildGoal
import dev.ide.build.BuildPlugin
import dev.ide.build.BuildSeverity
import dev.ide.build.DiagnosticLocation
import dev.ide.build.Lifecycle
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import dev.ide.model.Module
import dev.ide.platform.log.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.extension

/**
 * Compiles a module's C and C++ into the APK, as part of the ordinary build.
 *
 * One task per module that carries an [NdkFacet], wired ahead of the Android packaging merge.
 */
class NdkBuildPlugin(
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
) : BuildPlugin {

    override val id = "ndk"

    override fun appliesTo(config: BuildConfiguration): Boolean =
        config.request.goal != BuildGoal.CLEAN &&
            config.project.modules.any { it.facets.get(NdkFacet.KEY) != null }

    override fun apply(config: BuildConfiguration) {
        runCatching { NdkSourceRoots.ensureFor(config.project.modules, log) }
            .onFailure { log.warn("could not declare the native source roots", it) }

        val variant = config.request.variant.name

        for (module in config.project.modules) {
            dev.codeassist.ndk.jni.JniModules.remember(module)

            val facet = module.facets.get(NdkFacet.KEY) ?: continue
            val taskName = TaskName(":${module.name}:compileNative")
            val buildDir = config.env.buildDir(module)

            val generated = buildDir.resolve("intermediates")
                .resolve("ndk")
                .resolve("lib")

            val libDir =
                if (declareNativeLibraries(config, module, generated, taskName)) {
                    generated
                } else {
                    Paths.get(module.dir.path).resolve("src/main/jniLibs")
                }

            config.tasks.register(taskName) {
                NdkCompileTask(
                    taskName,
                    module,
                    facet,
                    buildDir,
                    libDir,
                    toolchain,
                    log
                )
            }

            config.tasks.named(
                TaskName(":${module.name}:mergeNativeLibs${variant.cap()}")
            ).configure {
                dependsOn(taskName)
            }

            config.tasks.named(
                Lifecycle.assemble(module.name)
            ).configure {
                dependsOn(taskName)
            }

            config.tasks.named(
                Lifecycle.assemble(module.name, variant)
            ).configure {
                dependsOn(taskName)
            }
        }
    }

    private fun declareNativeLibraries(
        config: BuildConfiguration,
        module: Module,
        dir: Path,
        task: TaskName
    ): Boolean =
        try {
            config.addNativeLibraries(module, dir, task)
            true
        } catch (e: LinkageError) {
            log.info(
                "this IDE cannot package a build directory's native libraries; " +
                    "writing to src/main/jniLibs"
            )
            false
        }

    private fun String.cap(): String =
        replaceFirstChar { it.uppercase() }
}

/**
 * Compiles every source the facet names and links them into one shared library.
 */
internal class NdkCompileTask(
    override val name: TaskName,
    private val module: Module,
    private val facet: NdkFacet,
    private val buildDir: Path,
    private val libDir: Path,
    private val toolchain: () -> NdkToolchain?,
    private val log: Logger,
    private val executableDir: Path? = null,
) : Task {

    private val moduleDir: Path
        get() = Paths.get(module.dir.path)

    private val executable: Boolean
        get() = executableDir != null

    /**
     * Local libraries directory for the requested ABI.
     *
     * Example:
     * app/src/injLibs/android.arm64-v8a
     */
    private fun localLibraryDir(abi: String): Path =
        moduleDir.resolve("src/injLibs/android.$abi")

    /**
     * Returns all local .so libraries for an ABI.
     */
    private fun localLibraries(abi: String): List<Path> {
        val dir = localLibraryDir(abi)

        if (!Files.isDirectory(dir)) {
            return emptyList()
        }

        return Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".so") }
                .collect(Collectors.toList())
        }.sorted()
    }

    /**
     * Copies local .so libraries into the directory
     * consumed by Android native library packaging.
     */
    private fun copyLocalLibraries(
        ctx: TaskContext,
        abi: String
    ): TaskResult? {

        val libraries = localLibraries(abi)

        if (libraries.isEmpty()) {
            return null
        }

        val destinationDir = libDir.resolve(abi)

        return try {
            Files.createDirectories(destinationDir)

            for (source in libraries) {
                ctx.checkCanceled()

                val destination = destinationDir.resolve(source.fileName)

                Files.copy(
                    source,
                    destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )

                ctx.logger()(
                    "ndk: packaged local library " +
                        "${source.fileName} for $abi"
                )
            }

            null

        } catch (e: Exception) {
            log.warn(
                "could not copy local native libraries for $abi",
                e
            )

            TaskResult.Failed(
                "ndk: could not package local libraries for $abi: ${e.message}",
                e
            )
        }
    }

    /**
     * Describes the files in a local library directory.
     *
     * This makes adding, removing, or updating a library invalidate the build inputs.
     */
    private fun libraryDirectoryStamp(abi: String): String {
        val dir = localLibraryDir(abi)

        if (!Files.isDirectory(dir)) {
            return "$dir (missing)"
        }

        val files = Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { file ->
                    "${file.fileName}:${Files.size(file)}:" +
                        "${Files.getLastModifiedTime(file).toMillis()}"
                }
                .sorted()
                .collect(Collectors.joining("\n"))
        }

        return "$dir\n$files"
    }

    /** Every C and C++ file under the facet's source directories. */
    private fun sources(): List<Path> =
        facet.sourceDirs
            .map { moduleDir.resolve(it) }
            .filter { Files.isDirectory(it) }
            .flatMap { dir ->
                Files.walk(dir).use { walk ->
                    walk.filter {
                        Files.isRegularFile(it) &&
                            it.extension.lowercase() in SOURCE_EXTENSIONS
                    }.collect(Collectors.toList())
                }
            }
            .sorted()

    /** Every header under the facet's source directories. */
    private fun headers(): List<Path> =
        facet.sourceDirs
            .map { moduleDir.resolve(it) }
            .filter { Files.isDirectory(it) }
            .flatMap { dir ->
                Files.walk(dir).use { walk ->
                    walk.filter {
                        Files.isRegularFile(it) &&
                            it.extension.lowercase() in HEADER_EXTENSIONS
                    }.collect(Collectors.toList())
                }
            }
            .sorted()

    internal fun outputFor(abi: String): Path =
        executableDir?.resolve(abi)?.resolve(module.name)
            ?: libDir.resolve(abi)
                .resolve("lib${facet.libraryName}.so")

    private val objRoot: Path
        get() = buildDir.resolve("intermediates")
            .resolve("ndk")
            .resolve("obj")

    override val inputs: TaskInputs
        get() = TaskInputsImpl().apply {

            sources().forEach {
                property("src:$it", NativeIncremental.modified(it))
            }

            headers().forEach {
                property("h:$it", NativeIncremental.modified(it))
            }

            property(
                "deps",
                NativeIncremental.recordedDependencies(objRoot)
                    .joinToString("\n") {
                        "$it@${NativeIncremental.modified(Paths.get(it))}"
                    }
                    .hashCode()
            )

            property(
                "facet",
                NdkFacetCodec.encode(facet).toString()
            )

            property("libDir", libDir.toString())
            property("executableDir", executableDir?.toString())

            facet.abis.distinct().forEach { abi ->
                property(
                    "injLibs:$abi",
                    libraryDirectoryStamp(abi)
                )
            }
        }

    override val outputs: TaskOutputs
        get() = TaskOutputsImpl().apply {

            facet.abis.distinct().forEach { abi ->

                filePath("so:$abi", outputFor(abi))

                // Register copied local libraries as task outputs too.
                localLibraries(abi).forEach { library ->
                    filePath(
                        "injLib:$abi:${library.fileName}",
                        libDir.resolve(abi).resolve(library.fileName)
                    )
                }
            }
        }

    override suspend fun execute(ctx: TaskContext): TaskResult {

        ctx.checkCanceled()

        /*
         * Package local native libraries first.
         *
         * This happens even if there are no C/C++ sources
         * or the NDK toolchain is unavailable.
         */
        for (abi in facet.abis.distinct()) {
            ctx.checkCanceled()

            val copyResult = copyLocalLibraries(ctx, abi)

            if (copyResult != null) {
                return copyResult
            }
        }

        val sources = sources()

        if (sources.isEmpty()) {
            ctx.logger()(
                "ndk: no C or C++ sources under " +
                    "${facet.sourceDirs.joinToString()} — nothing to build"
            )

            return TaskResult.Success
        }

        val toolchain = toolchain()
            ?: return skip(
                ctx,
                "the NDK plugin has no toolchain on this device"
            )

        val version = when (val status = toolchain.prepare()) {

            is NdkToolchain.Status.Unavailable ->
                return skip(ctx, status.reason)

            is NdkToolchain.Status.Ready ->
                status.version.also {
                    ctx.logger()("ndk: $it")
                }
        }

        val buildable: List<NdkAbi> =
            if (executable) {
                listOf(toolchain.hostAbi)
            } else {
                facet.abis
                    .mapNotNull { NdkAbi.byName(it) }
                    .distinct()
            }

        if (!executable) {
            facet.abis
                .filter { NdkAbi.byName(it) == null }
                .forEach {

                    ctx.diagnostics.report(
                        BuildDiagnostic(
                            severity = BuildSeverity.WARNING,
                            message =
                                "ndk: skipping unknown ABI '$it' — " +
                                    "supported: ${NdkAbi.names.joinToString()}",
                            source = "ndk",
                            location = DiagnosticLocation(module.dir.path)
                        )
                    )
                }
        }

        if (buildable.isEmpty()) {
            return skip(
                ctx,
                "no ABI this toolchain can target"
            )
        }

        for (abi in buildable) {

            ctx.checkCanceled()

            val result = buildAbi(
                ctx,
                toolchain,
                version,
                sources,
                abi
            )

            if (result != null) {
                return result
            }

            if (!executable) {
                removeLegacyOutput(ctx, abi.abiName)
            }
        }

        return TaskResult.Success
    }

    /**
     * Delete the library an older version of this plugin wrote into src/main/jniLibs.
     */
    private fun removeLegacyOutput(
        ctx: TaskContext,
        abi: String
    ) {

        val legacyDir = moduleDir.resolve("src/main/jniLibs")

        if (libDir.normalize() == legacyDir.normalize()) {
            return
        }

        val abiDir = legacyDir.resolve(abi)
        val stale = abiDir.resolve("lib${facet.libraryName}.so")

        if (!Files.isRegularFile(stale)) {
            return
        }

        runCatching {

            Files.delete(stale)

            ctx.logger()(
                "ndk: removed src/main/jniLibs/$abi/${stale.fileName}, " +
                    "which an earlier version of the NDK plugin wrote; " +
                    "native output now goes to ${moduleDir.relativize(libDir)}"
            )

            Files.newDirectoryStream(abiDir).use {
                if (!it.iterator().hasNext()) {
                    Files.delete(abiDir)
                }
            }

            Files.newDirectoryStream(legacyDir).use {
                if (!it.iterator().hasNext()) {
                    Files.delete(legacyDir)
                }
            }

        }.onFailure {
            log.warn("could not remove the stale $stale", it)
        }
    }

    /** Compile what changed and link one ABI. */
    private fun buildAbi(
        ctx: TaskContext,
        toolchain: NdkToolchain,
        version: String,
        sources: List<Path>,
        target: NdkAbi,
    ): TaskResult? {

        val abi = target.abiName
        val objDir = objRoot.resolve(abi)

        // Local libraries for this ABI.
        val extraLibraryDir = localLibraryDir(abi)

        val libraryDirs =
            if (Files.isDirectory(extraLibraryDir)) {
                listOf(extraLibraryDir)
            } else {
                emptyList()
            }

        val glue =
            if (facet.nativeActivity) {
                toolchain.nativeAppGlueDir
                    ?.resolve("android_native_app_glue.c")
            } else {
                null
            }

        val units = sources.map { it to objectKey(it) } +
            listOfNotNull(
                glue?.takeIf { Files.isRegularFile(it) }
                    ?.let { it to "_glue/${it.fileName}" }
            )

        val compileStamp = buildString {

            appendLine(version)

            appendLine(
                NdkFlags.build(
                    facet,
                    toolchain,
                    cpp = true
                ).joinToString(" ")
            )

            appendLine(
                NdkFlags.build(
                    facet,
                    toolchain,
                    cpp = false
                ).joinToString(" ")
            )
        }

        val compileStampFile = objDir.resolve("compile.stamp")

        if (readStamp(compileStampFile) != compileStamp) {
            deleteTree(objDir)
        }

        Files.createDirectories(objDir)

        val objects = ArrayList<Path>()

        val stale = units.filter { (source, key) ->

            val obj = objDir.resolve("$key.o")

            NativeIncremental.isStale(
                source,
                obj,
                depFileFor(obj)
            )
        }

        if (stale.isNotEmpty()) {
            ctx.logger()(
                "ndk: compiling ${stale.size} of ${units.size} " +
                    "source(s) for $abi"
            )
        }

        for ((index, unit) in stale.withIndex()) {

            val (source, key) = unit

            ctx.checkCanceled()

            ctx.progress.report(
                index.toDouble() / (stale.size + 1),
                "Compiling ${source.fileName}"
            )

            val obj = objDir.resolve("$key.o")
            val isCpp = source.extension.lowercase() != "c"

            val compiled = toolchain.compile(
                source,
                obj,
                cpp = isCpp,
                extraFlags = NdkFlags.build(
                    facet,
                    toolchain,
                    isCpp
                ),
                depFile = depFileFor(obj),
                abi = target
            )

            report(ctx, compiled.output, source)

            if (!compiled.ok) {

                runCatching {
                    Files.deleteIfExists(obj)
                    Files.deleteIfExists(depFileFor(obj))
                }

                ctx.logger()(
                    "ndk: failed to compile ${source.fileName}"
                )

                return TaskResult.Failed(
                    "ndk: ${source.fileName} did not compile"
                )
            }
        }

        writeStamp(
            compileStampFile,
            compileStamp
        )

        units.forEach { (_, key) ->
            objects.add(objDir.resolve("$key.o"))
        }

        removeOrphanObjects(
            objDir,
            objects.toSet()
        )

        val out = outputFor(abi)

        val cpp = units.any { (source, _) ->
            source.extension.lowercase() != "c"
        }

        val linkStamp = buildString {

            appendLine(version)

            objects.forEach {
                appendLine(it.toString())
            }

            appendLine(
                facet.linkLibraries.joinToString(" ")
            )

            appendLine("cpp=$cpp stl=${facet.stl}")

            appendLine("libraryDirs:")

            libraryDirs.forEach {
                appendLine(it.toAbsolutePath().toString())
            }

            appendLine(
                libraryDirectoryStamp(abi)
            )
        }

        val linkStampFile = objDir.resolve(
            if (executable) {
                "link-exe.stamp"
            } else {
                "link.stamp"
            }
        )

        if (!NativeIncremental.needsLink(
                stale.isNotEmpty(),
                out,
                objects,
                linkStamp,
                readStamp(linkStampFile)
            )
        ) {

            ctx.logger()(
                "ndk: ${out.fileName} for $abi is up to date"
            )

            return null
        }

        ctx.progress.report(
            stale.size.toDouble() / (stale.size + 1),
            "Linking ${out.fileName}"
        )

        // Pass local library directories to the linker.
        val linked =
            if (executable) {

                toolchain.linkExecutable(
                    objects,
                    out,
                    facet.linkLibraries,
                    cpp = cpp,
                    stl = facet.stl,
                    abi = target,
                    libraryDirs = libraryDirs
                )

            } else {

                toolchain.linkShared(
                    objects,
                    out,
                    facet.linkLibraries,
                    cpp = cpp,
                    stl = facet.stl,
                    abi = target,
                    libraryDirs = libraryDirs
                )
            }

        report(ctx, linked.output, null)

        if (!linked.ok) {

            runCatching {
                Files.deleteIfExists(linkStampFile)
            }

            return TaskResult.Failed(
                "ndk: ${out.fileName} did not link"
            )
        }

        packageCxxRuntime(
            ctx,
            toolchain,
            cpp,
            out,
            target
        )?.let {
            return it
        }

        writeStamp(
            linkStampFile,
            linkStamp
        )

        ctx.logger()(
            "ndk: wrote ${out.fileName} " +
                "(${runCatching {
                    Files.size(out)
                }.getOrDefault(0L)} bytes) for $abi"
        )

        return null
    }

    /**
     * Put libc++_shared.so beside the library when needed.
     */
    private fun packageCxxRuntime(
        ctx: TaskContext,
        toolchain: NdkToolchain,
        cpp: Boolean,
        out: Path,
        abi: NdkAbi,
    ): TaskResult? {

        val target = out.resolveSibling(CXX_SHARED)

        if (cpp && facet.stl == "c++_shared") {

            val runtime = toolchain.sharedCxxRuntime(abi)
                ?: return TaskResult.Failed(
                    "ndk: stl = c++_shared, but the toolchain " +
                        "has no $CXX_SHARED for ${abi.abiName}"
                )

            runCatching {

                Files.copy(
                    runtime,
                    target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )

            }.onFailure {

                return TaskResult.Failed(
                    "ndk: could not package $CXX_SHARED: ${it.message}",
                    it
                )
            }

            ctx.logger()(
                "ndk: packaged $CXX_SHARED"
            )

        } else if (
            libDir.normalize() !=
            moduleDir.resolve("src/main/jniLibs").normalize()
        ) {

            runCatching {
                Files.deleteIfExists(target)
            }
        }

        return null
    }

    private fun depFileFor(obj: Path): Path =
        obj.resolveSibling(
            obj.fileName.toString() + ".d"
        )

    private fun objectKey(source: Path): String =
        moduleDir.relativize(source)
            .toString()
            .replace('\\', '/')
            .split('/')
            .joinToString("/") {
                if (it == "..") "__" else it
            }

    private fun removeOrphanObjects(
        objDir: Path,
        keep: Set<Path>
    ) {

        Files.walk(objDir).use { walk ->

            walk.filter {

                Files.isRegularFile(it) &&
                    it.fileName.toString().endsWith(".o") &&
                    it !in keep

            }.collect(Collectors.toList())

        }.forEach { obj ->

            runCatching {
                Files.deleteIfExists(obj)
                Files.deleteIfExists(depFileFor(obj))
            }
        }
    }

    private fun readStamp(file: Path): String? =
        runCatching {
            String(Files.readAllBytes(file))
        }.getOrNull()

    private fun writeStamp(
        file: Path,
        text: String
    ) {

        runCatching {
            Files.write(file, text.toByteArray())
        }.onFailure {
            log.warn("could not write $file", it)
        }
    }

    private fun deleteTree(dir: Path) {

        if (!Files.exists(dir)) {
            return
        }

        Files.walk(dir).use { walk ->

            walk.sorted(Comparator.reverseOrder())
                .collect(Collectors.toList())

        }.forEach {

            runCatching {
                Files.deleteIfExists(it)
            }
        }
    }

    private fun report(
        ctx: TaskContext,
        output: String,
        source: Path?
    ) {

        if (output.isBlank()) {
            return
        }

        val text = source?.let {
            runCatching {
                it.toFile().readText()
            }.getOrNull()
        }

        val parsed = text?.let {
            runCatching {
                ClangDiagnostics.parse(
                    output,
                    it,
                    bufferName = source.toString()
                )
            }.getOrNull()
        }

        if (parsed.isNullOrEmpty()) {

            output.lineSequence()
                .filter { it.isNotBlank() }
                .forEach {
                    ctx.logger()("ndk: $it")
                }

            return
        }

        for (d in parsed) {

            ctx.diagnostics.report(
                BuildDiagnostic(
                    severity =
                        if (
                            d.severity ==
                            dev.ide.lang.dom.Severity.ERROR
                        ) {
                            BuildSeverity.ERROR
                        } else {
                            BuildSeverity.WARNING
                        },
                    message = d.message,
                    source = "ndk",
                    location = DiagnosticLocation(
                        source?.toString() ?: module.dir.path
                    )
                )
            )
        }
    }

    private fun skip(
        ctx: TaskContext,
        why: String
    ): TaskResult {

        log.warn(
            "ndk: skipping the native build — $why"
        )

        ctx.diagnostics.report(
            BuildDiagnostic(
                severity = BuildSeverity.WARNING,
                message = "ndk: no native library was built — $why",
                source = "ndk",
                location = DiagnosticLocation(module.dir.path)
            )
        )

        return TaskResult.Success
    }

    private companion object {

        val SOURCE_EXTENSIONS =
            setOf("c", "cc", "cpp", "cxx", "c++")

        val HEADER_EXTENSIONS =
            setOf("h", "hh", "hpp", "hxx", "inl")

        const val CXX_SHARED = "libc++_shared.so"
    }
}