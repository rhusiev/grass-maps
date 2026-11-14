package buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/** The host triple the Rust crates are built for on a developer machine. */
private const val HOST_TRIPLE = "x86_64-unknown-linux-gnu"

/**
 * Makes this module's JVM tests load the uniffi host library `:regions` builds
 * for `grass_regions`.
 *
 * A test in another module reaches the reader through `:regions`, so the
 * generated bindings find the library only when the test JVM is told where it
 * is. The Android JNA artifact also carries no host native, so the desktop jar
 * is added to the test runtime. Both are the same values `:regions` sets for
 * its own tests.
 */
fun Project.useGrassRegionsInTests() {
    val library =
        rootProject.layout.projectDirectory.file(
            "regions/build/rust/target/$HOST_TRIPLE/release/libgrass_regions.so",
        )
    tasks.withType(Test::class.java).configureEach {
        dependsOn(":regions:cargoBuildHost")
        systemProperty("uniffi.component.grass_regions.libraryOverride", library.asFile.absolutePath)
    }
    dependencies.add("testImplementation", "net.java.dev.jna:jna:5.19.1")
}
