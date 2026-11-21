package nl.r1a.grassmaps.core.services

import android.annotation.SuppressLint
import android.content.Context
import nl.r1a.grassmaps.plugin.api.service.Strings
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of [Strings].
 *
 * The resources live in the application's `values` (US English),
 * `values-en-rGB` (British English) and `values-uk` (Ukrainian) directories.
 * Lookup is by resource name so features can name a key without importing an
 * Android `R` class. A missing or malformed resource returns the key instead of
 * throwing across the service boundary.
 */
class AndroidStrings(
    context: Context,
) : Strings {
    private val resources = context.applicationContext.resources
    private val packageName = context.applicationContext.packageName
    private val resourceIds = ConcurrentHashMap<String, Int>()
    private val pluralResourceIds = ConcurrentHashMap<String, Int>()

    override fun resolve(
        key: String,
        arguments: List<String>,
    ): String {
        val resourceId = resourceId(key)
        if (resourceId == 0) {
            return key
        }
        return try {
            if (arguments.isEmpty()) {
                resources.getString(resourceId)
            } else {
                resources.getString(resourceId, *arguments.toTypedArray())
            }
        } catch (exception: RuntimeException) {
            key
        }
    }

    override fun resolveQuantity(
        key: String,
        quantity: Int,
        arguments: List<String>,
    ): String {
        val resourceId = pluralResourceId(key)
        if (resourceId == 0) {
            return resolve(key, arguments)
        }
        return try {
            resources.getQuantityString(resourceId, quantity, *arguments.toTypedArray())
        } catch (exception: RuntimeException) {
            resolve(key, arguments)
        }
    }

    override fun contains(key: String): Boolean = resourceId(key) != 0 || pluralResourceId(key) != 0

    // Key-based lookup is the service contract, so an R id cannot be used.
    @SuppressLint("DiscouragedApi")
    private fun resourceId(key: String): Int {
        resourceIds[key]?.let { return it }
        val resourceId = resources.getIdentifier(key, "string", packageName)
        resourceIds[key] = resourceId
        return resourceId
    }

    // Plural keys are a separate resource type, so they need their own lookup.
    @SuppressLint("DiscouragedApi")
    private fun pluralResourceId(key: String): Int {
        pluralResourceIds[key]?.let { return it }
        val resourceId = resources.getIdentifier(key, "plurals", packageName)
        pluralResourceIds[key] = resourceId
        return resourceId
    }
}
