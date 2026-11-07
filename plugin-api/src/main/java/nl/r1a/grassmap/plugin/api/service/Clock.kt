package nl.r1a.grassmap.plugin.api.service

/**
 * A source of wall-clock time in epoch milliseconds.
 *
 * The contract exists so a provider's behaviour is deterministic in a test: a
 * fake clock advances time without sleeping. [SystemClock] is the default the
 * host uses.
 */
fun interface Clock {
    fun nowMillis(): Long
}

/** The process clock. Widgets use it only to throttle their own emissions. */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
