import buildlogic.CheckPrivacyDependenciesTask
import buildlogic.CheckTranslationsTask
import buildlogic.LintPluginManifestsTask
import buildlogic.LintStyleRulesTask
import buildlogic.ModuleBoundaryRules

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Built-in Kotlin reads this classpath. AGP 9.1 embeds KGP 2.2.0, and
        // maplibre-compose 0.20 is compiled with Kotlin 2.4 metadata.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
}

// Formatting is configured once for every project, so a module build file does
// not repeat it. `./gradlew spotlessCheck` from the root covers all of them.
val spotlessPluginId =
    libs.plugins.spotless
        .get()
        .pluginId
val ktlintVersion = libs.versions.ktlint.get()

allprojects {
    apply(plugin = spotlessPluginId)

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
        }
    }
}

// The module boundary rule (README.md, "Module map"): a module may depend on
// other project modules only where the documented graph allows it. Plugins may
// use :plugin-api, and :plugin-ui-api only when they contribute Compose UI.
// Feature modules never import :core or :core-services; the only
// feature-to-feature edges are navigation -> routing, alerts -> navigation and
// planner -> routing. Anything else is a build failure, not a convention.
//
// :app is the composition root and depends on everything it wires, so it is
// checked too: its release allowlist is exactly the documented graph, which
// makes a new edge there a deliberate edit rather than a silent one. Its
// development plugins sit in the debug lane below, so a release configuration
// cannot take one.
val moduleDependencyAllowlist: Map<String, Set<String>> =
    mapOf(
        // Composition root. `README.md` records that app depends on every
        // module; the list is written out so an edge nobody documented fails.
        ":app" to
            setOf(
                ":plugin-api",
                ":plugin-ui-api",
                ":core",
                ":core-services",
                ":map",
                ":regions",
                ":routing",
                ":navigation",
                ":alerts",
                ":planner",
                ":places",
                ":tracks",
                ":ui-shell",
                ":plugins:style-standard",
                ":plugins:gpx",
                ":plugins:info-widgets",
                ":plugins:map-controls",
                ":plugins:point-info",
                ":plugins:osm-places",
                ":plugins:online-search",
                ":plugins:favourites-ui",
                ":plugins:guidance-ui",
                ":plugins:links",
                ":plugins:share",
                ":plugins:coordinates",
                ":plugins:valhalla",
                ":plugins:direct-engine",
                ":plugins:way-alerts",
                ":plugins:proximity-alerts",
                ":plugins:route-warnings",
                ":plugins:track-layer",
                ":plugins:route-layer",
                ":plugins:track-library",
                ":plugins:contours",
                ":plugins:route-relations",
                ":plugins:raster",
            ),
        // testFixtures(project(":plugin-api")) is the module's own fixtures.
        ":plugin-api" to setOf(":plugin-api"),
        ":plugin-ui-api" to setOf(":plugin-api"),
        ":core" to setOf(":plugin-api"),
        ":core-services" to setOf(":core", ":plugin-api"),
        ":map" to setOf(":plugin-api"),
        ":regions" to setOf(":plugin-api"),
        ":routing" to setOf(":plugin-api"),
        ":navigation" to setOf(":routing", ":plugin-api"),
        ":alerts" to setOf(":navigation", ":plugin-api"),
        // The planner is a compiled-in feature over the routing domain, like
        // navigation. It reaches no core internals and asks the host for the
        // engine through the plugin-api contract.
        ":planner" to setOf(":plugin-api", ":routing"),
        ":places" to setOf(":plugin-api"),
        ":tracks" to setOf(":plugin-api"),
        ":ui-shell" to setOf(":plugin-api", ":plugin-ui-api"),
        ":control" to setOf(":core", ":plugin-api"),
        ":devtools" to setOf(":core", ":control", ":plugin-api"),
        ":plugins:dev-tools" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:example" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:gpx" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:info-widgets" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:map-controls" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:point-info" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:osm-places" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:online-search" to setOf(":plugin-api"),
        ":plugins:favourites-ui" to setOf(":plugin-api"),
        ":plugins:guidance-ui" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:links" to setOf(":plugin-api"),
        ":plugins:share" to setOf(":plugin-api"),
        ":plugins:coordinates" to setOf(":plugin-api"),
        ":plugins:style-standard" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:synthetic-data" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:valhalla" to setOf(":plugin-api"),
        ":plugins:direct-engine" to setOf(":plugin-api"),
        // The navigation simulator drives the production session and geometry
        // follower, and reads a passed route through the shared JSON form. It
        // changes no navigation policy and reaches no core internals.
        ":plugins:nav-simulator" to setOf(":plugin-api", ":navigation", ":routing"),
        ":plugins:way-alerts" to setOf(":plugin-api"),
        ":plugins:proximity-alerts" to setOf(":plugin-api"),
        ":plugins:route-warnings" to setOf(":plugin-api"),
        ":plugins:track-layer" to setOf(":plugin-api"),
        ":plugins:route-layer" to setOf(":plugin-api"),
        ":plugins:track-library" to setOf(":plugin-api"),
        ":plugins:contours" to setOf(":plugin-api", ":plugin-ui-api"),
        ":plugins:route-relations" to setOf(":plugin-api"),
        ":plugins:raster" to setOf(":plugin-api"),
    )
// Edges that ship in the debug APK and must never reach a release build
// (architecture section 22). They are judged separately from the release
// allowlist, so moving any of them onto `implementation` fails here as well as
// in `checkReleaseApkPlugins`.
val moduleDebugOnlyDependencyAllowlist: Map<String, Set<String>> =
    mapOf(
        ":app" to
            setOf(
                ":control",
                ":devtools",
                ":plugins:example",
                ":plugins:synthetic-data",
                ":plugins:dev-tools",
                ":plugins:nav-simulator",
            ),
    )
// Test-only edges are separate so a test fixture cannot quietly become a
// production dependency. :control's info-widgets edge is the accepted T-0113
// control-fixture exception.
val moduleTestDependencyAllowlist: Map<String, Set<String>> =
    mapOf(
        // testFixtures(project(":plugin-api")) from app's own tests.
        ":app" to setOf(":plugin-api"),
        ":plugin-api" to setOf(":plugin-api"),
        ":regions" to setOf(":plugin-api"),
        ":control" to setOf(":plugins:info-widgets"),
        ":plugins:synthetic-data" to setOf(":plugin-api"),
        // T-0305: the follower's acceptance test feeds the real direct engine's
        // route through the Ferrostar mapper. Test only, never on a runtime
        // classpath, so the plugins -> plugin-api rule still holds for the app.
        ":navigation" to setOf(":plugins:direct-engine"),
        // T-0310: the guidance panel's acceptance feeds the real session's
        // published state into the panel. Test only.
        ":plugins:guidance-ui" to setOf(":navigation"),
        // T-0319: the way-alert acceptance reads the committed region through
        // the real reader and bridges its cues into the real alert host. Test
        // only; the plugin module itself depends on :plugin-api alone.
        ":plugins:way-alerts" to setOf(":plugin-api", ":regions", ":alerts"),
        // T-0413: the proximity acceptance replays a track through the
        // production session and bridges its cues into the real alert host.
        // Test only; the plugin module itself depends on :plugin-api alone.
        ":plugins:proximity-alerts" to setOf(":plugin-api", ":navigation", ":alerts"),
        // T-0409: the route-warning acceptance reads the committed region
        // through the real reader. Test only; the plugin depends on
        // :plugin-api alone.
        ":plugins:route-warnings" to setOf(":plugin-api", ":regions"),
        // T-0417: the contour acceptance parses and validates the committed
        // region's `contours` component through the real manifest reader. Test
        // only; the plugin's runtime classpath is :plugin-api plus UI.
        ":plugins:contours" to setOf(":plugin-api", ":regions", ":plugin-ui-api"),
        ":plugins:route-relations" to setOf(":plugin-api"),
    )
val moduleBoundaryViolations = objects.listProperty(String::class.java)
val moduleBoundaryEdges = objects.property(Int::class.java).convention(0)

tasks.register("checkPluginBoundaries") {
    group = "verification"
    description = "Fails when a module depends on a project outside its documented allowlist."
    inputs.property("violations", moduleBoundaryViolations)
    inputs.property("edges", moduleBoundaryEdges)

    doLast {
        val violations = (inputs.properties["violations"] as List<*>).map { it.toString() }.sorted()
        val edges = (inputs.properties["edges"] as Number).toInt()
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Module boundary rule violated.")
                    appendLine("The documented dependency graph allows only these project edges:")
                    for ((module, allowed) in moduleDependencyAllowlist.entries.sortedBy { it.key }) {
                        appendLine("  $module -> ${allowed.sorted().joinToString(", ").ifEmpty { "no project modules" }}")
                    }
                    appendLine("Allowed only on a debug configuration:")
                    for ((module, allowed) in moduleDebugOnlyDependencyAllowlist.entries.sortedBy { it.key }) {
                        appendLine("  $module -> ${allowed.sorted().joinToString(", ")}")
                    }
                    appendLine("Test-only edges are listed per module in build.gradle.kts.")
                    appendLine("Found these undeclared project dependencies:")
                    violations.forEach { appendLine("  $it") }
                    append(
                        "Feature modules take the host and service interfaces they need " +
                            "in their constructors; app wires the implementations.",
                    )
                },
            )
        }
        // A count proves the check looked at something: a filter that silently
        // matched no configuration would otherwise report a pass.
        println("Module boundaries OK ($edges project edges checked)")
    }
}

subprojects {
    afterEvaluate {
        // Only a declarable configuration carries the edges a build file states.
        // A resolvable one such as debugUnitTestCompileClasspath gains the
        // tested variant as a project dependency when it resolves - the module
        // depending on itself - and that dependency is not a decision anyone
        // made here, so it is not judged.
        val edges =
            configurations
                .filterNot { it.isCanBeResolved }
                .flatMap { configuration ->
                    configuration.dependencies
                        .filterIsInstance<ProjectDependency>()
                        .map { dependency ->
                            ModuleBoundaryRules.Edge(
                                module = path,
                                configuration = configuration.name,
                                dependency = dependency.path,
                            )
                        }
                }
        ModuleBoundaryRules
            .violations(
                edges = edges,
                release = moduleDependencyAllowlist,
                debugOnly = moduleDebugOnlyDependencyAllowlist,
                test = moduleTestDependencyAllowlist,
            ).forEach { violation -> moduleBoundaryViolations.add(violation.toString()) }
        moduleBoundaryEdges.set(moduleBoundaryEdges.get() + edges.size)
    }

    plugins.withId("com.android.library") {
        tasks.named("check") {
            dependsOn(rootProject.tasks.named("checkPluginBoundaries"))
        }
    }
    plugins.withId("com.android.application") {
        tasks.named("check") {
            dependsOn(rootProject.tasks.named("checkPluginBoundaries"))
        }
    }

    // The compiled-in planner sits at the top level but still ships a manifest.
    if (path.startsWith(":plugins:") || path == ":planner") {
        plugins.withId("com.android.library") {
            tasks.named("check") {
                dependsOn(":checkPluginManifests")
            }
        }
    }
}

// Privacy guards (T-0101, architecture section 26): no Play Services, ads or
// tracker dependency may reach a shipped runtime classpath, and the merged app
// manifest may not add background location or an account authenticator. The
// dependency rule resolves every module's debug and release runtime classpath,
// so a transitive artifact is caught as well as a declared one. The rules live
// in buildSrc and ci/privacy-denylist.txt; unit tests cover both.
val checkPrivacyDependencies =
    tasks.register<CheckPrivacyDependenciesTask>("checkPrivacyDependencies") {
        group = "verification"
        description =
            "Fails when a debug or release runtime classpath contains a Play Services, ads or tracker dependency."
        denylist.set(layout.projectDirectory.file("ci/privacy-denylist.txt"))
    }

tasks.register("checkPrivacyGuards") {
    group = "verification"
    description = "Runs the dependency denylist and merged-manifest privacy guards."
    dependsOn(checkPrivacyDependencies)
    dependsOn(":app:checkPrivacyManifest")
}

// Variant configurations exist only after a module is evaluated, so collect
// them once every project has been evaluated. The task resolves them on
// execution, not during configuration.
gradle.projectsEvaluated {
    subprojects.forEach { module ->
        module.configurations
            .matching { it.name == "debugRuntimeClasspath" || it.name == "releaseRuntimeClasspath" }
            .forEach { classpath ->
                checkPrivacyDependencies.configure { runtimeClasspaths.add(classpath) }
            }
    }
}

// Manifest-driven registries (architecture section 13): each plugin module carries
// src/main/plugin.json, and so does the compiled-in planner at the top level. The
// app generates the static registries; this task validates the same manifests.
val pluginManifestFiles =
    subprojects
        .filter { it.path.startsWith(":plugins:") || it.path == ":planner" }
        .map { it.file("src/main/plugin.json") }

tasks.register<LintPluginManifestsTask>("checkPluginManifests") {
    group = "verification"
    description = "Validates plugin manifests: ids, commands, settings, streams and providers."
    manifests.from(pluginManifestFiles)
}

// English and Ukrainian land together (AGENTS.md, Product). This checks every
// module that has Android string resources, so a new module is covered without
// editing the task.
tasks.register<CheckTranslationsTask>("checkTranslations") {
    group = "verification"
    description =
        "Fails when a string key or plurals name is missing from English or Ukrainian, exists only in the translation, or an en-GB override does not differ."
    stringResources.from(
        fileTree(rootDir) {
            include("**/src/main/res/values*/strings.xml")
            exclude("**/build/**")
        },
    )
}

// Map style rules (T-0108, design/map-style.md section 4): every authored style
// document is checked against the layer budget and the fill and symbol rules.
// The glob covers every plugin, so a new style module needs no edit here.
val lintStyleRules =
    tasks.register<LintStyleRulesTask>("lintStyleRules") {
        group = "verification"
        description = "Checks authored map style documents against the style rules."
        styles.from(
            fileTree(rootDir) {
                include("plugins/*/src/main/styles/*.json")
                exclude("**/build/**")
            },
        )
    }
