package nl.r1a.grassmap.app

import nl.r1a.grassmap.plugin.api.model.MobileDataPolicy
import java.io.File
import java.io.IOException

/**
 * The stored mobile-data policy (T-0505): `settings/downloads.json` holds
 * `{"mobileData":"ANY"}` or `"WIFI_ONLY"`. A missing, unreadable or unknown
 * value reads as Wi-Fi only, the safe default for a metered plan. Call both
 * methods off Main.
 */
class DownloadsSettings(
    private val file: File?,
) {
    fun load(): MobileDataPolicy {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return MobileDataPolicy.WIFI_ONLY
        return if (text.contains("\"${MobileDataPolicy.ANY.name}\"")) MobileDataPolicy.ANY else MobileDataPolicy.WIFI_ONLY
    }

    /** Stores [policy]. A write failure keeps the in-memory choice for this run. */
    fun save(policy: MobileDataPolicy) {
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "${target.name}.part")
            partial.writeText("""{"mobileData":"${policy.name}"}""")
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        } catch (error: IOException) {
            // The policy still holds for this run.
        }
    }
}
