package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.service.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RingLoggerTest {
    @Test
    fun aFullRingEvictsTheOldestRecord() {
        val logger = RingLogger(capacity = 3, clock = { 1_000L })

        logger.info("test", "one")
        logger.info("test", "two")
        logger.info("test", "three")
        logger.info("test", "four")
        logger.info("test", "five")

        assertEquals(listOf("three", "four", "five"), logger.snapshot().map { it.message })
        assertEquals(2L, logger.evictedCount())
    }

    @Test
    fun levelsAndTagsSurviveTheRing() {
        val logger = RingLogger(capacity = 8, clock = { 1_000L })

        logger.debug("alpha", "d")
        logger.info("beta", "i")
        logger.warn("gamma", "w")
        logger.error("delta", "e")

        val records = logger.snapshot()
        assertEquals(
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            records.map { it.level },
        )
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), records.map { it.tag })
        assertEquals(listOf("d", "i", "w", "e"), records.map { it.message })
        assertTrue(records.all { it.timestampMillis == 1_000L })
    }

    @Test
    fun aDisabledToggleStoresNothing() {
        var enabled = false
        val logger = RingLogger(capacity = 4, clock = { 1_000L }, enabled = { enabled })

        logger.info("test", "dropped")
        assertTrue(logger.snapshot().isEmpty())

        enabled = true
        logger.info("test", "stored")
        assertEquals(listOf("stored"), logger.snapshot().map { it.message })
    }

    @Test
    fun aMessageIsBounded() {
        val logger = RingLogger(capacity = 2, clock = { 1_000L })
        val long = "x".repeat(RingLogger.MAX_MESSAGE_LENGTH * 2)

        logger.info("t".repeat(RingLogger.MAX_TAG_LENGTH * 2), long)

        val record = logger.snapshot().single()
        assertEquals(RingLogger.MAX_MESSAGE_LENGTH, record.message.length)
        assertEquals(RingLogger.MAX_TAG_LENGTH, record.tag.length)
    }

    @Test
    fun coordinatesQueriesAndTokensNeverEnterTheRing() {
        val logger = RingLogger(capacity = 8, clock = { 1_000L })

        logger.info("location", "fix lat=49.83972 lon=24.02972 accuracy=5")
        logger.info("search", "keystroke=\"lviv pizza\" hits=3")
        logger.info("network", "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.c2VjcmV0.c2lnbmF0dXJl")

        val stored = logger.snapshot().joinToString("\n") { it.message }
        assertFalse(stored.contains("49.83972"))
        assertFalse(stored.contains("24.02972"))
        assertFalse(stored.contains("lviv pizza"))
        assertFalse(stored.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(stored.contains("c2VjcmV0"))
        assertTrue(stored.contains("accuracy=5"))
    }

    @Test
    fun concurrentAppendsKeepTheRingBounded() {
        val capacity = 100
        val writers = 4
        val perWriter = 250
        val logger = RingLogger(capacity = capacity, clock = { 1_000L })
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        val pool = Executors.newFixedThreadPool(writers)

        repeat(writers) { writer ->
            pool.execute {
                start.await()
                repeat(perWriter) { index -> logger.info("w$writer", "message-$index") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(capacity, logger.snapshot().size)
        assertEquals((writers * perWriter - capacity).toLong(), logger.evictedCount())
    }
}
