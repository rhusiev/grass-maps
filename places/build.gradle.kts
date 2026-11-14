import buildlogic.RustNative

plugins {
    alias(libs.plugins.android.library)
}

// The Rust search reader. One crate builds a host library for the JVM tests and
// one Android library per ABI; the Kotlin bindings are generated from the same
// crate, so no hand-written JNI code exists. The shared build logic lives in
// buildSrc (`RustNative`).
val rust =
    RustNative.configureModule(
        project = project,
        crate = "rust/grass-core",
        library = "grass_core",
        crateInputs = listOf("data/**"),
    )

android {
    namespace = "nl.r1a.grassmap.places"
    compileSdk = 37
    ndkVersion = RustNative.NDK_VERSION

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += RustNative.androidTargets.keys
        }
    }

    sourceSets.getByName("main") {
        kotlin.directories.add(rust.bindingsDirectory.absolutePath)
        jniLibs.directories.add(rust.jniDirectory.absolutePath)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

rust.wire()

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
    dependsOn(rust.hostBuild)
    // The generated bindings read this property before loading the library.
    systemProperty(
        "uniffi.component.grass_core.libraryOverride",
        rust.hostLibrary.absolutePath,
    )
    // The Android variant of JNA carries only the device natives; the JVM test
    // needs the desktop jar for its own libjnidispatch. The assignment happens
    // at execution because AGP finalizes the test classpath after this block.
    doFirst {
        classpath = files(jnaDesktop) + classpath
    }
}
