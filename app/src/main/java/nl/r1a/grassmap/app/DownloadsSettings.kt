package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.MobileDataPolicy
import java.io.File
import java.io.IOException

/**
 * The stored download choices: `settings/downloads.json` holds
 * `{"mobileData":"ANY","autoUpdate":false}`. A missing, unreadable or unknown
 * mobile-data value reads as Wi-Fi only, the safe default for a metered plan
 * (T-0505). A missing `autoUpdate` reads as on, the product default for
 * monthly updates (T-0508). Each save keeps the other choice. Call every
 * method off Main.
 */
class DownloadsSettings(
    private val file: File?,
) {
    fun load(): MobileDataPolicy {
        val text = readText() ?: return MobileDataPolicy.WIFI_ONLY
        return if (text.contains("\"${MobileDataPolicy.ANY.name}\"")) MobileDataPolicy.ANY else MobileDataPolicy.WIFI_ONLY
    }

    /** Whether the monthly automatic update is on. */
    fun loadAutoUpdate(): Boolean = readText()?.let { !AUTO_UPDATE_OFF.containsMatchIn(it) } ?: true

    /** Stores [policy]. A write failure keeps the in-memory choice for this run. */
    fun save(policy: MobileDataPolicy) = write(policy, loadAutoUpdate())

    /** Stores the automatic-update choice. A write failure keeps it for this run. */
    fun saveAutoUpdate(enabled: Boolean) = write(load(), enabled)

    private fun readText(): String? = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull()

    private fun write(
        policy: MobileDataPolicy,
        autoUpdate: Boolean,
    ) {
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "${target.name}.part")
            partial.writeText("""{"mobileData":"${policy.name}","autoUpdate":$autoUpdate}""")
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        } catch (error: IOException) {
            // The choice still holds for this run.
        }
    }

    private companion object {
        val AUTO_UPDATE_OFF = Regex("\"autoUpdate\"\\s*:\\s*false")
    }
}
