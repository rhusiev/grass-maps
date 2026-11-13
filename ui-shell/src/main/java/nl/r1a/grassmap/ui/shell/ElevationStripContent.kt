package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.service.Strings
import nl.r1a.grassmap.plugin.ui.api.ElevationChartState
import nl.r1a.grassmap.plugin.ui.api.ElevationChartStretch
import java.util.Locale
import kotlin.math.floor

/*
 * The surface and way-type strips under the chart (T-0406).
 *
 * The strips read the engine's annotated stretches as they are: both ends are
 * distances on the profile the chart draws, so a stretch's position and its
 * share of the distance come from the engine's own segments and nothing is
 * sampled or measured again here. The two class sets, their labels, their order
 * and their colours are the round-10 design's; a value neither set knows is
 * left out of the strip instead of being guessed into a class.
 */

/** The round-10 classes the two strips draw. Steps are the one striped class. */
internal enum class ElevationStripBand {
    Asphalt,
    Paving,
    Ground,
    Gravel,
    Street,
    Footway,
    Path,
    Track,
    Steps,
}

/** True when the class is drawn as the design's 45° stripes rather than a colour. */
internal val ElevationStripBand.isStriped: Boolean get() = this == ElevationStripBand.Steps

/** One legend entry: the class, its label, its share of the classified line and that share in whole percent. */
internal data class ElevationStripLegendEntry(
    val band: ElevationStripBand,
    val label: String,
    val share: Double,
    val percent: Int,
)

/** One drawn stretch, as fractions across the chart's plot on the chosen axis. */
internal data class ElevationStripPiece(
    val startFraction: Float,
    val endFraction: Float,
    val band: ElevationStripBand,
) {
    val striped: Boolean get() = band.isStriped
}

/** One strip row: its title, its legend, the pieces to draw and its percentage line. */
internal data class ElevationStripRow(
    val title: String,
    val legend: List<ElevationStripLegendEntry>,
    val pieces: List<ElevationStripPiece>,
    val summary: String,
)

/**
 * The strips [state] carries, surface first, or an empty list when no stretch
 * has a value a strip can draw.
 *
 * A row's percentages are each class's summed segment length over the summed
 * length of the row's classified segments, so they add up to 100 % whenever the
 * line is classified throughout. A segment the palette has no class for leaves
 * a gap in the strip and adds nothing to either side of the fraction.
 */
internal fun elevationStripRows(
    state: ElevationChartState,
    strings: Strings,
    locale: Locale = Locale.getDefault(),
): List<ElevationStripRow> {
    if (!state.isDrawable) {
        return emptyList()
    }
    return listOfNotNull(
        stripRow(state, SURFACE_TITLE_KEY, SURFACE_LABELS, { it.surface }, ::surfaceBand, strings, locale),
        stripRow(state, WAY_TYPE_TITLE_KEY, WAY_TYPE_LABELS, { it.wayType }, ::wayTypeBand, strings, locale),
    )
}

/** The round-10 class for one engine surface value, or null when the palette has none. */
internal fun surfaceBand(value: String?): ElevationStripBand? = SURFACE_BANDS[value?.lowercase(Locale.ROOT)?.trim()]

/** The round-10 class for one engine highway value, or null when the palette has none. */
internal fun wayTypeBand(value: String?): ElevationStripBand? = WAY_TYPE_BANDS[value?.lowercase(Locale.ROOT)?.trim()]

private fun stripRow(
    state: ElevationChartState,
    titleKey: String,
    labels: Map<ElevationStripBand, String>,
    values: (ElevationChartStretch) -> String?,
    bandOf: (String?) -> ElevationStripBand?,
    strings: Strings,
    locale: Locale,
): ElevationStripRow? {
    val known =
        state.stretches.mapNotNull { stretch ->
            val band = bandOf(values(stretch)) ?: return@mapNotNull null
            if (stretch.distanceMeters <= 0.0) return@mapNotNull null
            band to stretch
        }
    if (known.isEmpty()) {
        return null
    }
    val total = known.sumOf { (_, stretch) -> stretch.distanceMeters }
    if (total <= 0.0) {
        return null
    }
    val order = known.map { (band, _) -> band }.distinct()
    val lengths = order.associateWith { entry -> known.filter { it.first == entry }.sumOf { it.second.distanceMeters } }
    val shares = order.map { band -> lengths.getValue(band) / total }
    val percents = wholePercentages(shares)
    val legend =
        order.mapIndexed { index, band ->
            ElevationStripLegendEntry(
                band = band,
                label = strings.resolve(labels.getValue(band)),
                share = shares[index],
                percent = percents[index],
            )
        }
    val pieces = stripPieces(state, known)
    if (pieces.isEmpty()) {
        return null
    }
    val summary =
        legend
            .sortedWith(compareByDescending<ElevationStripLegendEntry> { it.share }.thenBy { order.indexOf(it.band) })
            .joinToString(STRIP_SEPARATOR) { entry ->
                strings.resolve(SHARE_KEY, listOf(entry.percent.toString(), entry.label.lowercase(locale)))
            }
    return ElevationStripRow(
        title = strings.resolve(titleKey),
        legend = legend,
        pieces = pieces,
        summary = summary,
    )
}

/**
 * [known] as drawn pieces: adjacent stretches of one class make one piece, so a
 * route cut per graph edge does not become a ladder of 2 px gaps.
 */
private fun stripPieces(
    state: ElevationChartState,
    known: List<Pair<ElevationStripBand, ElevationChartStretch>>,
): List<ElevationStripPiece> {
    val merged = mutableListOf<MergedStretch>()
    for ((band, stretch) in known) {
        val last = merged.lastOrNull()
        if (last != null && last.band == band && stretch.startMeters <= last.endMeters + ADJACENT_METERS) {
            if (stretch.endMeters > last.endMeters) {
                merged[merged.lastIndex] = last.copy(endMeters = stretch.endMeters)
            }
        } else {
            merged += MergedStretch(band, stretch.startMeters, stretch.endMeters)
        }
    }
    return merged.mapNotNull { stretch ->
        val start = elevationChartXAtDistance(state, stretch.startMeters) ?: return@mapNotNull null
        val end = elevationChartXAtDistance(state, stretch.endMeters) ?: return@mapNotNull null
        if (end - start <= 0f) {
            return@mapNotNull null
        }
        ElevationStripPiece(startFraction = start, endFraction = end, band = stretch.band)
    }
}

/** Whole percentages of [shares], which sum to one, adding up to 100 too. */
private fun wholePercentages(shares: List<Double>): List<Int> {
    val scaled = shares.map { share -> share * PERCENT }
    val result = scaled.map { value -> floor(value).toInt() }.toMutableList()
    var left = PERCENT_TOTAL - result.sum()
    val byRemainder = scaled.indices.sortedByDescending { index -> scaled[index] - floor(scaled[index]) }
    for (index in byRemainder) {
        if (left <= 0) {
            break
        }
        result[index] += 1
        left -= 1
    }
    return result
}

private data class MergedStretch(
    val band: ElevationStripBand,
    val startMeters: Double,
    val endMeters: Double,
)

private val SURFACE_BANDS =
    mapOf(
        "asphalt" to ElevationStripBand.Asphalt,
        "paved" to ElevationStripBand.Asphalt,
        "concrete" to ElevationStripBand.Asphalt,
        "chipseal" to ElevationStripBand.Asphalt,
        "paving_stones" to ElevationStripBand.Paving,
        "sett" to ElevationStripBand.Paving,
        "cobblestone" to ElevationStripBand.Paving,
        "unhewn_cobblestone" to ElevationStripBand.Paving,
        "bricks" to ElevationStripBand.Paving,
        "gravel" to ElevationStripBand.Gravel,
        "fine_gravel" to ElevationStripBand.Gravel,
        "compacted" to ElevationStripBand.Gravel,
        "pebblestone" to ElevationStripBand.Gravel,
        "ground" to ElevationStripBand.Ground,
        "earth" to ElevationStripBand.Ground,
        "dirt" to ElevationStripBand.Ground,
        "mud" to ElevationStripBand.Ground,
        "grass" to ElevationStripBand.Ground,
        "sand" to ElevationStripBand.Ground,
    )

private val WAY_TYPE_BANDS =
    mapOf(
        "motorway" to ElevationStripBand.Street,
        "trunk" to ElevationStripBand.Street,
        "primary" to ElevationStripBand.Street,
        "secondary" to ElevationStripBand.Street,
        "tertiary" to ElevationStripBand.Street,
        "unclassified" to ElevationStripBand.Street,
        "residential" to ElevationStripBand.Street,
        "living_street" to ElevationStripBand.Street,
        "service" to ElevationStripBand.Street,
        "road" to ElevationStripBand.Street,
        "driveway" to ElevationStripBand.Street,
        "parking" to ElevationStripBand.Street,
        "pedestrian" to ElevationStripBand.Street,
        "footway" to ElevationStripBand.Footway,
        "sidewalk" to ElevationStripBand.Footway,
        "walkway" to ElevationStripBand.Footway,
        "crossing" to ElevationStripBand.Footway,
        "steps" to ElevationStripBand.Steps,
        "stairs" to ElevationStripBand.Steps,
        "track" to ElevationStripBand.Track,
        "path" to ElevationStripBand.Path,
        "cycleway" to ElevationStripBand.Path,
        "bridleway" to ElevationStripBand.Path,
    )

private val SURFACE_LABELS =
    mapOf(
        ElevationStripBand.Asphalt to "elevation_chart_surface_asphalt",
        ElevationStripBand.Paving to "elevation_chart_surface_paving",
        ElevationStripBand.Ground to "elevation_chart_surface_ground",
        ElevationStripBand.Gravel to "elevation_chart_surface_gravel",
    )

private val WAY_TYPE_LABELS =
    mapOf(
        ElevationStripBand.Street to "elevation_chart_way_street",
        ElevationStripBand.Footway to "elevation_chart_way_footway",
        ElevationStripBand.Path to "elevation_chart_way_path",
        ElevationStripBand.Track to "elevation_chart_way_track",
        ElevationStripBand.Steps to "elevation_chart_way_steps",
    )

private const val SURFACE_TITLE_KEY = "elevation_chart_surface_title"
private const val WAY_TYPE_TITLE_KEY = "elevation_chart_way_type_title"
private const val SHARE_KEY = "elevation_chart_strip_share"

/** The middle dot the panel and the strip summary join their parts with. */
internal const val STRIP_SEPARATOR = " · "

private const val PERCENT = 100.0
private const val PERCENT_TOTAL = 100
private const val ADJACENT_METERS = 0.001
