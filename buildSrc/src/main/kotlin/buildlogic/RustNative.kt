package buildlogic

import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

/**
 * The one implementation of the Rust native build used by `:places` and
 * `:regions`.
 *
 * Both modules build a host library for the JVM tests and one Android library
 * per ABI, generate the uniffi bindings from the host library, and copy and
 * strip the Android library into their `jniLibs` source set. The NDK is pinned
 * by [NDK_VERSION] and resolved only when a task that builds native code runs,
 * so a checkout with no NDK still configures and runs unrelated Gradle tasks.
 */
object RustNative {
    /** The NDK that builds the shipped libraries. Keep in step with README.md. */
    const val NDK_VERSION: String = "28.2.13676358"

    const val ANDROID_API: Int = 29

    /** The host triple the Rust crates are built for on a developer machine. */
    const val HOST_TRIPLE: String = "x86_64-unknown-linux-gnu"

    /** The shipped ABIs and their Rust targets. */
    val androidTargets: Map<String, String> =
        linkedMapOf(
            "arm64-v8a" to "aarch64-linux-android",
            "x86_64" to "x86_64-linux-android",
        )

    /** The `Pkg.Revision` a directory's `source.properties` declares, if readable. */
    fun ndkRevision(directory: File): String? {
        val propertiesFile = directory.resolve("source.properties")
        if (!propertiesFile.isFile) return null
        val properties = Properties()
        propertiesFile.inputStream().use(properties::load)
        return properties.getProperty("Pkg.Revision")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Returns [directory] when its `source.properties` names [version].
     * Otherwise fails with the required and the found revision.
     */
    fun requireNdk(
        directory: File,
        version: String = NDK_VERSION,
    ): File {
        val found = ndkRevision(directory)
        if (found != version) {
            val foundText =
                if (found == null) {
                    "has no readable source.properties (Pkg.Revision)"
                } else {
                    "is NDK $found"
                }
            throw GradleException(
                "This build requires Android NDK $version, but ${directory.absolutePath} $foundText. " +
                    "Install NDK $version with sdkmanager \"ndk;$version\" or point ANDROID_NDK_HOME at it.",
            )
        }
        return directory
    }

    /**
     * Resolves the pinned NDK. An environment variable wins over the SDK, but
     * it must point at exactly [version]; a mismatch never falls back silently.
     */
    fun resolveNdk(
        version: String,
        sdkRoot: File?,
        ndkHome: String?,
        ndkRoot: String?,
    ): File {
        ndkHome?.trim()?.takeIf { it.isNotEmpty() }?.let { return requireNdk(File(it), version) }
        ndkRoot?.trim()?.takeIf { it.isNotEmpty() }?.let { return requireNdk(File(it), version) }
        val sdk =
            sdkRoot
                ?: throw GradleException(
                    "No Android SDK: set ANDROID_HOME or write local.properties. " +
                        "This build also requires Android NDK $version.",
                )
        val ndkRootDirectory = File(sdk, "ndk")
        val installed =
            ndkRootDirectory
                .listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        val pinned = File(ndkRootDirectory, version)
        if (pinned.isDirectory) return requireNdk(pinned, version)
        throw GradleException(
            "No Android NDK $version under ${ndkRootDirectory.absolutePath} " +
                "(found: ${installed.joinToString().ifEmpty { "none" }}). " +
                "Install it with sdkmanager \"ndk;$version\".",
        )
    }

    /** The Android SDK from `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties`. */
    fun sdkRoot(project: Project): File {
        val properties = Properties()
        project.rootProject
            .file("local.properties")
            .takeIf { it.isFile }
            ?.inputStream()
            ?.use { properties.load(it) }
        val path =
            System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: properties.getProperty("sdk.dir")
                ?: throw GradleException("No Android SDK: set ANDROID_HOME or write local.properties")
        return project.rootProject.file(path)
    }

    /** The pinned NDK, resolved when a native task runs, never at configuration time. */
    fun pinnedNdk(project: Project): File {
        val ndkHome = System.getenv("ANDROID_NDK_HOME")
        val ndkRoot = System.getenv("ANDROID_NDK_ROOT")
        val sdk =
            if (ndkHome.isNullOrBlank() && ndkRoot.isNullOrBlank()) {
                sdkRoot(project)
            } else {
                null
            }
        return resolveNdk(NDK_VERSION, sdk, ndkHome, ndkRoot)
    }

    fun llvmBin(ndk: File): File =
        ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin").also {
            if (!it.isDirectory) {
                throw GradleException("No LLVM toolchain in ${ndk.absolutePath}")
            }
        }

    fun clangFor(
        ndk: File,
        triple: String,
    ): File =
        llvmBin(ndk).resolve("$triple$ANDROID_API-clang").also {
            if (!it.canExecute()) {
                throw GradleException("No NDK clang wrapper at ${it.absolutePath}")
            }
        }

    /**
     * Registers the Rust tasks for [crate] and returns the files the module's
     * Android and test configuration needs. [crateInputs] adds crate-specific
     * input patterns such as the crate's data directory.
     */
    fun configureModule(
        project: Project,
        crate: String,
        library: String,
        crateInputs: List<String> = emptyList(),
    ): RustModuleBuild {
        val crateDirectory = project.rootProject.layout.projectDirectory.dir(crate)
        val rustBuild = project.layout.buildDirectory.dir("rust").get().asFile
        val rustTargets = File(rustBuild, "target")
        val bindingsDirectory = project.layout.buildDirectory.dir("generated/uniffi").get().asFile
        val jniDirectory = project.layout.buildDirectory.dir("rust-jni").get().asFile

        val cargoBinary = findCargo()
        // The physical path matters: the crate sources resolve templates
        // relative to the symlinked registry path and fail when the home is
        // reached through a symlink.
        val cargoHome = cargoBinary.absoluteFile.parentFile.parentFile.canonicalFile
        val cargoCommand = if (cargoBinary.canExecute()) cargoBinary.absolutePath else "cargo"

        // Fixed absolute prefixes keep the compiled library independent of the
        // checkout path, so two builds in different directories produce the
        // same .so.
        val remapFlags =
            listOf(
                "--remap-path-prefix=${crateDirectory.asFile.absolutePath}=/rust/${crateDirectory.asFile.name}",
                "--remap-path-prefix=${cargoHome.absolutePath}=/cargo",
            ).joinToString(" ")

        val crateFiles =
            project.files(
                crateDirectory.asFileTree.matching {
                    include("src/**")
                    crateInputs.forEach { include(it) }
                    include("Cargo.toml", "Cargo.lock", "uniffi.toml", "rust-toolchain.toml")
                },
            )

        fun registerCargo(
            name: String,
            triple: String,
            extraFlags: String,
            usesNdk: Boolean,
        ): TaskProvider<Exec> =
            project.tasks.register<Exec>(name) {
                group = "rust"
                description = "Builds $library for $triple."
                workingDir(crateDirectory)
                environment("CARGO_HOME", cargoHome.absolutePath)
                environment("CARGO_TARGET_DIR", rustTargets.absolutePath)
                environment(
                    "RUSTFLAGS",
                    listOf(remapFlags, extraFlags).filter { it.isNotBlank() }.joinToString(" "),
                )
                commandLine(cargoCommand, "build", "--release", "--locked", "--target", triple)
                inputs.files(crateFiles)
                inputs.property("remap", remapFlags)
                if (usesNdk) {
                    inputs.property("ndkVersion", NDK_VERSION)
                    doFirst {
                        val ndk = pinnedNdk(project)
                        val clang = clangFor(ndk, triple).absolutePath
                        val targetKey = triple.uppercase().replace("-", "_")
                        environment("CARGO_TARGET_${targetKey}_LINKER", clang)
                        environment("CC_${triple.replace("-", "_")}", clang)
                        environment("AR_${triple.replace("-", "_")}", llvmBin(ndk).resolve("llvm-ar").absolutePath)
                    }
                }
                outputs.dir(File(rustTargets, "$triple/release"))
            }

        val cargoHost =
            registerCargo(
                name = "cargoBuildHost",
                triple = HOST_TRIPLE,
                extraFlags = "",
                usesNdk = false,
            )

        val cargoAndroid =
            androidTargets.values.associateWith { triple ->
                registerCargo(
                    name = "cargoBuild${triple.replace("-", "")}",
                    triple = triple,
                    extraFlags = "-C link-arg=-Wl,-z,max-page-size=16384",
                    usesNdk = true,
                )
            }

        val hostLibrary = File(rustTargets, "$HOST_TRIPLE/release/lib$library.so")

        // The bindgen binary needs uniffi's CLI feature, which brings
        // uniffi_bindgen into the crate graph. Building it in its own target
        // directory keeps that feature out of the shipped library, whose
        // uniffi_bindgen codegen is not byte-reproducible and is not used at
        // runtime.
        val bindgenTargets = File(rustBuild, "target-bindgen")
        val hostBindgen = File(bindgenTargets, "$HOST_TRIPLE/release/uniffi-bindgen")

        val cargoBindgen =
            project.tasks.register<Exec>("cargoBuildBindgen") {
                group = "rust"
                description = "Builds the host uniffi-bindgen with its CLI feature."
                workingDir(crateDirectory)
                environment("CARGO_HOME", cargoHome.absolutePath)
                environment("CARGO_TARGET_DIR", bindgenTargets.absolutePath)
                environment("RUSTFLAGS", remapFlags)
                commandLine(
                    cargoCommand,
                    "build",
                    "--release",
                    "--locked",
                    "--target",
                    HOST_TRIPLE,
                    "--features",
                    "cli",
                    "--bin",
                    "uniffi-bindgen",
                )
                inputs.files(crateFiles)
                inputs.property("remap", remapFlags)
                outputs.file(hostBindgen)
            }

        val generateRustBindings =
            project.tasks.register<Exec>("generateRustBindings") {
                group = "rust"
                description = "Generates the Kotlin bindings from the host $library library."
                dependsOn(cargoHost, cargoBindgen)
                workingDir(crateDirectory)
                environment("CARGO_HOME", cargoHome.absolutePath)
                commandLine(
                    hostBindgen.absolutePath,
                    "generate",
                    "--library",
                    hostLibrary.absolutePath,
                    "--language",
                    "kotlin",
                    "--out-dir",
                    bindingsDirectory.absolutePath,
                    "--no-format",
                )
                inputs.file(hostLibrary)
                inputs.file(hostBindgen)
                inputs.file(crateDirectory.file("uniffi.toml"))
                outputs.dir(bindingsDirectory)
            }

        val stripRustLibraries =
            androidTargets.map { (abi, triple) ->
                val cargoTask = cargoAndroid.getValue(triple)
                val stripped = File(jniDirectory, abi)
                project.tasks.register<Exec>("stripRust${triple.replace("-", "")}") {
                    group = "rust"
                    description = "Copies and strips $library for $abi."
                    dependsOn(cargoTask)
                    val source = File(rustTargets, "$triple/release/lib$library.so")
                    val target = File(stripped, "lib$library.so")
                    inputs.file(source)
                    inputs.property("ndkVersion", NDK_VERSION)
                    outputs.file(target)
                    doFirst {
                        val ndk = pinnedNdk(project)
                        target.parentFile.mkdirs()
                        target.delete()
                        source.copyTo(target, overwrite = true)
                        commandLine(
                            llvmBin(ndk).resolve("llvm-strip").absolutePath,
                            "--strip-unneeded",
                            target.absolutePath,
                        )
                    }
                }
            }

        return RustModuleBuild(
            project = project,
            hostLibrary = hostLibrary,
            bindingsDirectory = bindingsDirectory,
            jniDirectory = jniDirectory,
            hostBuild = cargoHost,
            generateBindings = generateRustBindings,
            stripLibraries = stripRustLibraries,
        )
    }

    private fun findCargo(): File =
        System
            .getenv("CARGO_HOME")
            ?.let { File(it, "bin/cargo") }
            ?.takeIf { it.canExecute() }
            ?: System
                .getenv("PATH")
                ?.split(File.pathSeparator)
                ?.map { File(it, "cargo") }
                ?.firstOrNull { it.canExecute() }
            ?: File("cargo")
}

/**
 * What [RustNative.configureModule] registered for one module. [wire] connects
 * the generated bindings to compilation and the stripped libraries to the
 * tasks that merge native libraries, so a JVM-only command such as
 * `:app:testDebugUnitTest` never runs the NDK.
 */
class RustModuleBuild internal constructor(
    private val project: Project,
    val hostLibrary: File,
    val bindingsDirectory: File,
    val jniDirectory: File,
    val hostBuild: TaskProvider<Exec>,
    val generateBindings: TaskProvider<Exec>,
    val stripLibraries: List<TaskProvider<Exec>>,
) {
    fun wire() {
        project.tasks.named("preBuild") { dependsOn(generateBindings) }
        project.tasks
            .matching {
                it.name.startsWith("merge") &&
                    (it.name.endsWith("NativeLibs") || it.name.endsWith("JniLibFolders"))
            }
            .configureEach { dependsOn(stripLibraries) }
    }
}
