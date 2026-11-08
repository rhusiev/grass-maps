package nl.r1a.grassmap.plugin.api.model

/**
 * The length unit system a reading is shown in (T-0107).
 *
 * Metric readings use metres and kilometres. Imperial readings use feet below
 * a mile and miles from a mile up. The value type lives in `plugin-api` so the
 * settings seam in `core-services` and the renderers in `plugins/map-controls`
 * and `ui-shell` agree without a module edge between them.
 */
enum class UnitSystem {
    Metric,
    Imperial,
}
