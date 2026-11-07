package nl.r1a.grassmap.core.services

import nl.r1a.grassmap.plugin.api.service.LogLevel
import nl.r1a.grassmap.plugin.api.service.LogRecord
import nl.r1a.grassmap.plugin.api.service.Logger

/**
 * A fixed-capacity, in-memory log ring.
 *
 * The oldest record is evicted when the ring is full, so memory use stays
 * bounded for the whole process. Every append is redacted by [LogScrubber]
 * before it is stored: a coordinate, a search query or a token never enters the
 * ring, and the diagnostic bundle therefore cannot contain one. Appends do no
 * I/O; the optional [enabled] predicate is an in-memory read.
 */
class RingLogger(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val enabled: () -> Boolean = { true },
    private val scrubber: LogScrubber = LogScrubber(),
) : Logger {
    private val capacityLimit: Int = capacity.coerceAtLeast(1)
    private val records: ArrayDeque<LogRecord> = ArrayDeque(capacityLimit)
    private var evicted: Long = 0

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        if (!enabled()) {
            return
        }
        val record =
            LogRecord(
                timestampMillis = clock(),
                level = level,
                tag = scrubber.scrub(tag).take(MAX_TAG_LENGTH),
                message = scrubber.scrub(message).take(MAX_MESSAGE_LENGTH),
            )
        synchronized(this) {
            if (records.size == capacityLimit) {
                records.removeFirst()
                evicted += 1
            }
            records.addLast(record)
        }
    }

    /** A copy of the stored records, oldest first. */
    fun snapshot(): List<LogRecord> =
        synchronized(this) {
            records.toList()
        }

    /** How many records the ring dropped because it was full. */
    fun evictedCount(): Long =
        synchronized(this) {
            evicted
        }

    /** Drops every stored record. */
    fun clear() {
        synchronized(this) {
            records.clear()
        }
    }

    companion object {
        /** Records kept for a bundle; chosen to bound the ring at a few MiB. */
        const val DEFAULT_CAPACITY: Int = 2000

        const val MAX_TAG_LENGTH: Int = 96
        const val MAX_MESSAGE_LENGTH: Int = 1024
    }
}
