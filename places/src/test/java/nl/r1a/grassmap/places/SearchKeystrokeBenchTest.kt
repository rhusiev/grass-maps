package nl.r1a.grassmap.places

import kotlinx.coroutines.runBlocking
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.provider.SearchContext
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The T-0203 keystroke budget on the JVM.
 *
 * The committed probe set is typed letter by letter through
 * [SearchScreenSession], the same path a screen keystroke takes, so the timing
 * includes the session publish, the dispatcher hop and the uniffi bridge into
 * the Rust reader. It is the JVM counterpart of the phone run: the acceptance
 * number is the Moto g32 p99, and this test is the cheap regression gate that
 * runs on every check.
 *
 * Run only this report with:
 * `./gradlew :places:test --tests '*SearchKeystrokeBenchTest*' --info`
 */
class SearchKeystrokeBenchTest {
    @Test
    fun `the committed replay stays inside the keystroke budget`() {
        val queries = readProbes()
        assertTrue("the committed probe set is empty", queries.isNotEmpty())
        val session =
            SearchScreenSession { request ->
                provider.search(
                    query = request.query,
                    context = request.context,
                    limit = request.limit,
                    cancellation = request.cancellation,
                )
            }
        val timings = mutableListOf<Long>()
        var cold = 0L
        var sequence = 0L
        runBlocking {
            for (query in queries) {
                for (length in 1..query.length) {
                    sequence += 1
                    val started = System.nanoTime()
                    val outcome =
                        session.submit(
                            query = query.take(length),
                            sequence = sequence,
                            context = SearchContext(position = LVIV_CENTRE),
                        )
                    val elapsed = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started)
                    assertTrue("the replay failed at \"${query.take(length)}\": $outcome", outcome is Outcome.Success)
                    if (sequence == 1L) {
                        cold = elapsed
                    }
                    timings += elapsed
                }
            }
        }
        val p50 = percentile(timings, 50)
        val p95 = percentile(timings, 95)
        val p99 = percentile(timings, 99)
        println(machineLine())
        println(
            "search keystroke replay on this JVM: ${timings.size} keystrokes from ${queries.size} probes, " +
                "p50 ${millis(p50)} ms, p95 ${millis(p95)} ms, p99 ${millis(p99)} ms, " +
                "max ${millis(timings.maxOrNull() ?: 0L)} ms, first keystroke ${millis(cold)} ms",
        )
        assertTrue(
            "p99 ${millis(p99)} ms is over the ${millis(BUDGET_MICROS)} ms keystroke budget",
            p99 <= BUDGET_MICROS,
        )
    }

    private fun machineLine(): String =
        "machine: os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")} " +
            "cores=${Runtime.getRuntime().availableProcessors()} " +
            "jvm=${System.getProperty("java.version")}"

    private fun readProbes(): List<String> {
        val text =
            javaClass.getResourceAsStream("/search/queries.tsv")?.bufferedReader()?.use { it.readText() }
                ?: error("missing /search/queries.tsv")
        return text
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                line
                    .split('\t')
                    .firstOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }.toList()
    }

    companion object {
        /** The phone criterion, used here as the JVM regression tolerance. */
        private const val BUDGET_MICROS = 50_000L
        private val LVIV_CENTRE = GeoPoint(49.8419, 24.0316)

        private lateinit var provider: RustPlaceProvider

        @BeforeClass
        @JvmStatic
        fun setUp() {
            provider = TestSearchIndex.open()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            provider.close()
        }
    }
}
