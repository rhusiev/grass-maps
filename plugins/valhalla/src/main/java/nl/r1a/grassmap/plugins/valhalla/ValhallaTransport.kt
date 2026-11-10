package nl.r1a.grassmap.plugins.valhalla

import com.valhalla.valhalla.Valhalla
import java.io.Closeable
import java.io.File

/** One way to run a Valhalla request body. Tests replace the native engine. */
internal fun interface ValhallaTransport : Closeable {
    fun route(requestJson: String): String

    /**
     * Runs a `trace_attributes` body. Valhalla's library exposes it separately
     * from `route`, so an engine that only needs matching leaves this unused.
     */
    fun traceAttributes(requestJson: String): String = throw NotImplementedError("trace_attributes is not available")

    override fun close() = Unit
}

/**
 * The native valhalla-mobile engine. It writes a config whose paths point at
 * the copied pack, then builds the actor from that file. The actor is created
 * on the first route and reused for the process lifetime.
 */
internal class NativeValhallaTransport private constructor(
    private val pack: ValhallaPackStatus.Ready,
    private val configDirectory: File,
) : ValhallaTransport {
    constructor(
        packDirectory: File,
        configDirectory: File,
    ) : this(
        ValhallaPack.inspect(packDirectory.absolutePath).readyOrThrow(),
        configDirectory,
    )

    private val engineDelegate = lazy { createEngine() }
    private val engine: Valhalla by engineDelegate

    override fun route(requestJson: String): String = engine.routeRaw(requestJson)

    override fun traceAttributes(requestJson: String): String = engine.traceAttributesRaw(requestJson)

    override fun close() {
        if (engineDelegate.isInitialized()) {
            engine.close()
        }
    }

    private fun createEngine(): Valhalla {
        val config = ValhallaPackConfig.write(pack, configDirectory)
        return try {
            Valhalla(config.absolutePath)
        } catch (exception: ValhallaPackException) {
            throw exception
        } catch (exception: Exception) {
            val reason = exception.message ?: exception.javaClass.simpleName
            throw ValhallaPackException(
                ValhallaPackProblem.Unusable,
                "the native engine rejected ${config.absolutePath}: $reason",
                exception,
            )
        }
    }
}
