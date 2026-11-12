package nl.r1a.grassmap.app

import androidx.core.content.FileProvider

/**
 * The main app's FileProvider (T-0410). It is a named subclass so the measure
 * build's separate `FileProvider` component does not merge with this one when
 * the manifests are combined; each keeps its own authority and path resource.
 */
class TrackFileProvider : FileProvider()
