package nl.r1a.grassmap.regions

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * When the monthly check last succeeded and last started, as epoch
 * milliseconds. Null means never.
 */
data class AutoUpdateLedgerState(
    val lastCheckMs: Long? = null,
    val lastAttemptMs: Long? = null,
)

/** Keeps [AutoUpdateLedgerState] across process starts. Call both methods off Main. */
interface AutoUpdateLedger {
    fun load(): AutoUpdateLedgerState

    fun save(state: AutoUpdateLedgerState)
}

/**
 * The ledger in a JSON file. A missing, unreadable or malformed file reads as
 * never checked, which only makes the next check run sooner. A write failure
 * keeps the state for this run through [cached].
 */
class FileAutoUpdateLedger(
    private val file: File?,
) : AutoUpdateLedger {
    private var cached: AutoUpdateLedgerState? = null

    @Synchronized
    override fun load(): AutoUpdateLedgerState = cached ?: read().also { cached = it }

    @Synchronized
    override fun save(state: AutoUpdateLedgerState) {
        cached = state
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "${target.name}.part")
            partial.writeText(encode(state))
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        } catch (error: IOException) {
            // The state still holds for this run.
        }
    }

    private fun read(): AutoUpdateLedgerState =
        try {
            val text = file?.takeIf { it.isFile }?.readText()
            if (text == null) AutoUpdateLedgerState() else decode(text)
        } catch (error: IOException) {
            AutoUpdateLedgerState()
        } catch (error: SerializationException) {
            AutoUpdateLedgerState()
        } catch (error: IllegalArgumentException) {
            AutoUpdateLedgerState()
        }

    private fun encode(state: AutoUpdateLedgerState): String =
        buildJsonObject {
            state.lastCheckMs?.let { put(LAST_CHECK, it) }
            state.lastAttemptMs?.let { put(LAST_ATTEMPT, it) }
        }.toString()

    private fun decode(text: String): AutoUpdateLedgerState {
        val json: JsonObject = Json.parseToJsonElement(text).jsonObject
        return AutoUpdateLedgerState(
            lastCheckMs = (json[LAST_CHECK] as? JsonPrimitive)?.longOrNull,
            lastAttemptMs = (json[LAST_ATTEMPT] as? JsonPrimitive)?.longOrNull,
        )
    }

    private companion object {
        const val LAST_CHECK = "lastCheckMs"
        const val LAST_ATTEMPT = "lastAttemptMs"
    }
}
