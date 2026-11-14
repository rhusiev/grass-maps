package nl.r1a.grassmap.plugin.api.service

/**
 * Resolves user-facing text by a resource key.
 *
 * Implementations resolve the key in the active language and apply [arguments]
 * as Android format arguments. The contract carries no Android types, so core
 * and feature modules depend only on this interface. An unknown key or malformed
 * format arguments return the key itself; the implementation never throws.
 */
interface Strings {
    /**
     * Returns the text for [key], with [arguments] applied in the order the
     * resource declares its placeholders.
     */
    fun resolve(
        key: String,
        arguments: List<String> = emptyList(),
    ): String

    /**
     * Returns the text for a plural [key] in the form the active language uses
     * for [quantity], with [arguments] applied in the order the resource
     * declares its placeholders.
     *
     * The default implementation has no plural tables, so it falls back to
     * [resolve]. An implementation with access to the resources overrides it.
     */
    fun resolveQuantity(
        key: String,
        quantity: Int,
        arguments: List<String> = emptyList(),
    ): String = resolve(key, arguments)

    /** Returns true when [key] exists in the active language. */
    fun contains(key: String): Boolean
}
