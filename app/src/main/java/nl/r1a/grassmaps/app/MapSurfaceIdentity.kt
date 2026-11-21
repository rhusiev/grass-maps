package nl.r1a.grassmaps.app

import nl.r1a.grassmaps.core.services.UiLanguage
import nl.r1a.grassmaps.plugin.api.model.StyleId

/**
 * The key that owns the map surface. `rememberMapSurface` builds the engine
 * from the base style id alone and then updates sources and labels in place,
 * so the UI language is deliberately not part of this key (T-0111). A locale
 * change recomposes the screen but keeps the same map surface.
 */
internal data class MapSurfaceIdentity(
    val styleId: StyleId,
)

internal fun mapSurfaceIdentity(
    styleId: StyleId,
    language: UiLanguage,
): MapSurfaceIdentity = MapSurfaceIdentity(styleId)
