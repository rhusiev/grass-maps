package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.plugin.api.service.Strings

/**
 * A stable [Strings] reference whose locale can change without recreating the
 * process, the activity or the map surface. The settings screen swaps in a
 * locale-specific [nl.r1a.grassmaps.core.services.AndroidStrings] when the
 * in-app language changes (T-0111).
 */
internal class MutableStrings(
    initial: Strings,
) : Strings {
    @Volatile
    private var delegate: Strings = initial

    fun use(strings: Strings) {
        delegate = strings
    }

    override fun resolve(
        key: String,
        arguments: List<String>,
    ): String = delegate.resolve(key, arguments)

    override fun resolveQuantity(
        key: String,
        quantity: Int,
        arguments: List<String>,
    ): String = delegate.resolveQuantity(key, quantity, arguments)

    override fun contains(key: String): Boolean = delegate.contains(key)
}
