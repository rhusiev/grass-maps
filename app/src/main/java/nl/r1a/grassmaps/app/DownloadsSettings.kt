package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.model.MobileDataPolicy
import java.io.File
import java.io.IOException

/**
 * The stored download choices: `settings/downloads.json` holds
 * `{"mobileData":"ANY","autoUpdate":false}`. A missing, unreadable or unknown
 * mobile-data value reads as Wi-Fi only, the safe default for a metered plan
 * (T-0505). A missing `autoUpdate` reads as on, the product default for
 * monthly updates (T-0508). Each save keeps the other choice. Every method
 * reads the file, because the class keeps no copy. A save reads, changes and
 * writes under one lock, so two saves of one instance never lose each other's
 * choice. Call every method off Main.
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

    /** Stores [policy]. A write failure leaves the stored choice as it was. */
    fun save(policy: MobileDataPolicy) = synchronized(LOCK) { write(policy, loadAutoUpdate()) }

    /** Stores the automatic-update choice. A write failure leaves the stored choice as it was. */
    fun saveAutoUpdate(enabled: Boolean) = synchronized(LOCK) { write(load(), enabled) }

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
        // One lock for every instance: the app builds a DownloadsSettings per caller.
        val LOCK = Any()
        val AUTO_UPDATE_OFF = Regex("\"autoUpdate\"\\s*:\\s*false")
    }
}
