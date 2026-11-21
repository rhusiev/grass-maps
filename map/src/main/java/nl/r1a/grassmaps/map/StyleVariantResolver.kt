package nl.r1a.grassmaps.map

import nl.r1a.grassmaps.plugin.api.model.StyleId

/** The part of a style the engine loads. The layer variant is applied as a diff. */
internal data class StyleVariant(
    val id: StyleId,
    val baseStyleJson: String,
)

/**
 * Whether moving from [current] to [next] needs an engine style load (T-0603).
 * Meadow and Trail, light and dark, share one base document, so a profile
 * switch between them only replaces layers and is a no-op for the loader. A
 * change that edits the base document, or the first style, loads once.
 */
internal fun needsStyleReload(
    current: StyleVariant?,
    next: StyleVariant,
): Boolean = current == null || current.baseStyleJson != next.baseStyleJson
