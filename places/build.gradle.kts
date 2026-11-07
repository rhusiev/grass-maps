import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

// The Rust search reader. One crate builds a host library for the JVM tests and
// one Android library per ABI; the Kotlin bindings are generated from the same
// crate, so no hand-written JNI code exists.
val rustCrate = rootProject.layout.projectDirectory.dir("rust/grass-core")
val rustBuild = layout.buildDirectory.dir("rust")
val rustTargets = rustBuild.get().dir("target").asFile
val hostTriple = "x86_64-unknown-linux-gnu"
val androidApi = 29
val androidTargets =
    linkedMapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "x86_64" to "x86_64-linux-android",
    )
val bindingsDirectory = layout.buildDirectory.dir("generated/uniffi")
val jniDirectory = layout.buildDirectory.dir("rust-jni")

fun executableOnPath(name: String): File? =
    System
        .getenv("PATH")
        ?.split(File.pathSeparator)
        ?.map { File(it, name) }
        ?.firstOrNull { it.canExecute() }

val cargoBinary: File =
    System
        .getenv("CARGO_HOME")
        ?.let { File(it, "bin/cargo") }
        ?.takeIf { it.canExecute() }
        ?: executableOnPath("cargo")
        ?: File("cargo")

// The physical path matters: the crate sources resolve templates relative to
// the symlinked registry path and fail when the home is reached through a
// symlink.
val cargoHome: File = cargoBinary.absoluteFile.parentFile.parentFile.canonicalFile
val cargoCommand: String = if (cargoBinary.canExecute()) cargoBinary.absolutePath else "cargo"

fun androidSdk(): File {
    val properties = Properties()
    rootProject
        .file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use { properties.load(it) }
    val path =
        System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: properties.getProperty("sdk.dir")
            ?: throw GradleException("No Android SDK: set ANDROID_HOME or write local.properties")
    return File(path)
}

fun ndkDirectory(): File {
    System.getenv("ANDROID_NDK_HOME")?.let { return File(it) }
    System.getenv("ANDROID_NDK_ROOT")?.let { return File(it) }
    val root = File(androidSdk(), "ndk")
    return root.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        ?: throw GradleException("No Android NDK under ${root.absolutePath}; install one with sdkmanager")
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
    llvmBin(ndk).resolve("$triple$androidApi-clang").also {
        if (!it.canExecute()) {
            throw GradleException("No NDK clang wrapper at ${it.absolutePath}")
        }
    }

// Fixed absolute prefixes keep the compiled library independent of the
// checkout path, so two builds in different directories produce the same .so.
fun remapFlags(): String =
    listOf(
        "--remap-path-prefix=${rustCrate.asFile.absolutePath}=/rust/grass-core",
        "--remap-path-prefix=${cargoHome.absolutePath}=/cargo",
    ).joinToString(" ")

fun registerCargo(
    name: String,
    triple: String,
    extraFlags: String,
    extraEnvironment: Map<String, String>,
): TaskProvider<Exec> =
    tasks.register<Exec>(name) {
        group = "rust"
        description = "Builds grass-core for $triple."
        workingDir(rustCrate)
        environment("CARGO_HOME", cargoHome.absolutePath)
        environment("CARGO_TARGET_DIR", rustTargets.absolutePath)
        environment("RUSTFLAGS", listOf(remapFlags(), extraFlags).filter { it.isNotBlank() }.joinToString(" "))
        extraEnvironment.forEach { (key, value) -> environment(key, value) }
        commandLine(cargoCommand, "build", "--release", "--locked", "--target", triple)
        inputs.files(
            rustCrate.asFileTree.matching {
                include("src/**", "Cargo.toml", "Cargo.lock", "uniffi.toml", "rust-toolchain.toml")
            },
        )
        inputs.property("remap", remapFlags())
        outputs.dir(rustTargets.resolve(triple).resolve("release"))
    }

val cargoHost =
    registerCargo(
        name = "cargoBuildHost",
        triple = hostTriple,
        extraFlags = "",
        extraEnvironment = emptyMap(),
    )

val cargoAndroid =
    androidTargets.values.associateWith { triple ->
        val ndk = ndkDirectory()
        registerCargo(
            name = "cargoBuild${triple.replace("-", "")}",
            triple = triple,
            extraFlags = "-C link-arg=-Wl,-z,max-page-size=16384",
            extraEnvironment =
                mapOf(
                    "CARGO_TARGET_${triple.uppercase().replace("-", "_")}_LINKER" to clangFor(ndk, triple).absolutePath,
                    "CC_${triple.replace("-", "_")}" to clangFor(ndk, triple).absolutePath,
                    "AR_${triple.replace("-", "_")}" to llvmBin(ndk).resolve("llvm-ar").absolutePath,
                ),
        )
    }

val hostLibrary = rustTargets.resolve(hostTriple).resolve("release/libgrass_core.so")
val hostBindgen = rustTargets.resolve(hostTriple).resolve("release/uniffi-bindgen")

val generateRustBindings =
    tasks.register<Exec>("generateRustBindings") {
        group = "rust"
        description = "Generates the Kotlin bindings from the host grass-core library."
        dependsOn(cargoHost)
        workingDir(rustCrate)
        environment("CARGO_HOME", cargoHome.absolutePath)
        commandLine(
            hostBindgen.absolutePath,
            "generate",
            "--library",
            hostLibrary.absolutePath,
            "--language",
            "kotlin",
            "--out-dir",
            bindingsDirectory.get().asFile.absolutePath,
            "--no-format",
        )
        inputs.file(hostLibrary)
        inputs.file(rustCrate.file("uniffi.toml"))
        outputs.dir(bindingsDirectory)
    }

val stripRustLibraries =
    androidTargets.map { (abi, triple) ->
        val cargoTask = cargoAndroid.getValue(triple)
        val stripped = jniDirectory.get().dir(abi).asFile
        tasks.register<Exec>("stripRust${triple.replace("-", "")}") {
            group = "rust"
            description = "Copies and strips grass-core for $abi."
            dependsOn(cargoTask)
            val ndk = ndkDirectory()
            val source = rustTargets.resolve(triple).resolve("release/libgrass_core.so")
            val target = File(stripped, "libgrass_core.so")
            inputs.file(source)
            outputs.file(target)
            doFirst {
                target.parentFile.mkdirs()
                target.delete()
                source.copyTo(target, overwrite = true)
            }
            commandLine(
                llvmBin(ndk).resolve("llvm-strip").absolutePath,
                "--strip-unneeded",
                target.absolutePath,
            )
        }
    }

android {
    namespace = "nl.r1a.grassmap.places"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += androidTargets.keys
        }
    }

    sourceSets.getByName("main") {
        kotlin.directories.add(bindingsDirectory.get().asFile.absolutePath)
        jniLibs.directories.add(jniDirectory.get().asFile.absolutePath)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") {
    dependsOn(generateRustBindings, stripRustLibraries)
}

val jnaDesktop =
    configurations.create("jnaDesktop") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    api(project(":plugin-api"))
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    add(jnaDesktop.name, "net.java.dev.jna:jna:5.19.1") {
        artifact {
            name = "jna"
            type = "jar"
            extension = "jar"
        }
        isTransitive = false
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoHost)
    // The generated bindings read this property before loading the library.
    systemProperty(
        "uniffi.component.grass_core.libraryOverride",
        hostLibrary.absolutePath,
    )
    // The Android variant of JNA carries only the device natives; the JVM test
    // needs the desktop jar for its own libjnidispatch. The assignment happens
    // at execution because AGP finalizes the test classpath after this block.
    doFirst {
        classpath = files(jnaDesktop) + classpath
    }
}
