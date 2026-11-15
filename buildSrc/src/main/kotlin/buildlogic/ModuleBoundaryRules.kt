package buildlogic

/**
 * The module boundary rule from `README.md`, "Module map": a module may depend
 * on other project modules only where the documented graph allows it.
 *
 * The rule is here, not in the root build file, so a deliberately bad edge can
 * be tested without running a second build. `build.gradle.kts` collects each
 * configuration's project dependencies and hands them over.
 *
 * An edge is judged by the lane its configuration belongs to, because the same
 * dependency is legal in one lane and illegal in another: `:app` may take a
 * development plugin on `debugImplementation`, which ships in the debug APK,
 * but never on `implementation`, which ships in the release APK.
 */
object ModuleBoundaryRules {
    /** Where an edge ends up: a release build, a debug build, or test only. */
    enum class Lane(
        val label: String,
    ) {
        RELEASE("release"),
        DEBUG("debug"),
        TEST("test"),
    }

    data class Edge(
        val module: String,
        val configuration: String,
        val dependency: String,
    )

    data class Violation(
        val edge: Edge,
        val lane: Lane?,
        val reason: String,
    ) {
        /** One line naming the module, the configuration and the dependency. */
        override fun toString(): String = "${edge.module} (${edge.configuration}) -> ${edge.dependency}: $reason"
    }

    /**
     * Source-set prefixes that make a configuration test-only. Gradle builds a
     * configuration name as `<variant><SourceSet><Kind>`, and a variant prefix
     * only precedes an Android test source set (`debugAndroidTestImplementation`).
     * The match is case-insensitive on the prefix and requires a capital letter
     * after it, so `latestVersions` and a build type called `testbed` are not
     * silently treated as test edges.
     */
    private val TEST_SOURCE_SET_PREFIXES = listOf("androidTest", "unitTest", "test")

    /** Build types this project declares, used to strip a variant prefix. */
    private val VARIANT_PREFIXES = listOf("debug", "release")

    private const val DEBUG_PREFIX = "debug"
    private const val RELEASE_PREFIX = "release"

    /**
     * The configuration kinds a source set declares without a variant prefix.
     * A name outside this set and outside the shapes above is not classified,
     * so its edges fail instead of passing by default.
     */
    private val PRODUCTION_KINDS =
        setOf(
            "api",
            "implementation",
            "compileOnly",
            "compileOnlyApi",
            "runtimeOnly",
            "annotationProcessor",
        )

    /**
     * The lane of a configuration, or null when the name does not match a shape
     * this rule knows. Null is a failure, not a default: a configuration the
     * check cannot classify would otherwise decide nothing about its edges.
     */
    fun laneOf(configuration: String): Lane? {
        if (isTestConfiguration(configuration)) return Lane.TEST
        if (configuration.startsWith(RELEASE_PREFIX)) return Lane.RELEASE
        if (configuration.startsWith(DEBUG_PREFIX)) return Lane.DEBUG
        if (configuration in PRODUCTION_KINDS) return Lane.RELEASE
        return null
    }

    /** True when the name names a test-only configuration. */
    fun isTestConfiguration(configuration: String): Boolean =
        testSourceSetCandidates(configuration).any { name ->
            TEST_SOURCE_SET_PREFIXES.any { prefix ->
                name.startsWith(prefix, ignoreCase = true) &&
                    name.getOrNull(prefix.length)?.isUpperCase() == true
            }
        }

    private fun testSourceSetCandidates(configuration: String): List<String> =
        buildList {
            add(configuration)
            VARIANT_PREFIXES
                .filter { configuration.startsWith(it) }
                .forEach { add(configuration.removePrefix(it)) }
        }

    /**
     * Returns one violation per edge the allowlists do not cover. [release] is
     * the documented production graph, [debugOnly] the edges allowed only on a
     * debug configuration, and [test] the test-only edges a module may take.
     */
    fun violations(
        edges: List<Edge>,
        release: Map<String, Set<String>>,
        debugOnly: Map<String, Set<String>>,
        test: Map<String, Set<String>>,
    ): List<Violation> =
        edges.mapNotNull { edge ->
            when (val lane = laneOf(edge.configuration)) {
                null ->
                    Violation(
                        edge,
                        null,
                        "configuration shape not known to the boundary check, so this edge is not judged",
                    )
                else -> {
                    val allowed = allowedFor(edge.module, lane, release, debugOnly, test)
                    if (edge.dependency in allowed) {
                        null
                    } else {
                        Violation(edge, lane, "not in the ${lane.label} allowlist for ${edge.module}")
                    }
                }
            }
        }.distinct()
            .sortedBy { it.toString() }

    private fun allowedFor(
        module: String,
        lane: Lane,
        release: Map<String, Set<String>>,
        debugOnly: Map<String, Set<String>>,
        test: Map<String, Set<String>>,
    ): Set<String> =
        when (lane) {
            Lane.RELEASE -> release[module].orEmpty()
            Lane.DEBUG -> release[module].orEmpty() + debugOnly[module].orEmpty()
            Lane.TEST -> test[module].orEmpty()
        }
}
