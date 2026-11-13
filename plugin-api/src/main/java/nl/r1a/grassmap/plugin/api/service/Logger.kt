package nl.r1a.grassmap.plugin.api.service

/** Severity of one log record, in the order a reader filters it. */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * One stored log record. The timestamp is epoch milliseconds.
 *
 * A record never holds a coordinate, a search query or a token. The
 * implementation redacts those before it stores the text.
 */
data class LogRecord(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * The platform log sink (architecture.md section 4.3, 21).
 *
 * Messages carry no coordinate, search query or token. The implementation
 * redacts them anyway, so a careless call site cannot leak one into the
 * diagnostic bundle. Logging never performs I/O.
 */
interface Logger {
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
    )

    fun debug(
        tag: String,
        message: String,
    ) = log(LogLevel.DEBUG, tag, message)

    fun info(
        tag: String,
        message: String,
    ) = log(LogLevel.INFO, tag, message)

    fun warn(
        tag: String,
        message: String,
    ) = log(LogLevel.WARN, tag, message)

    fun error(
        tag: String,
        message: String,
    ) = log(LogLevel.ERROR, tag, message)
}

/**
 * The sink a host without diagnostics uses: it drops every record. A plugin
 * reads [nl.r1a.grassmap.plugin.api.host.PluginServices.logger] without
 * knowing whether the host wired one.
 */
object NoLogger : Logger {
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
    ) = Unit
}
