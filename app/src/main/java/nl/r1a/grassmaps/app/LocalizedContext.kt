package nl.r1a.grassmaps.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources

/**
 * The composition's context for the in-app language (T-0111): the activity
 * with the localized resources. It wraps the activity rather than replacing
 * it, because Compose finds the activity's owners - the result registry the
 * Backup screen's document launchers need, among them - by unwrapping
 * `LocalContext`. A bare `createConfigurationContext` context hides them.
 */
internal class LocalizedContext(
    base: Context,
    private val localized: Resources,
) : ContextWrapper(base) {
    override fun getResources(): Resources = localized
}
