package nl.r1a.grassmaps.regions

import nl.r1a.grassmaps.plugin.api.error.Outcome
import nl.r1a.grassmaps.plugin.api.host.NoRegionAccess
import nl.r1a.grassmaps.plugin.api.host.RegionAccess
import nl.r1a.grassmaps.plugin.api.model.GeoPoint
import nl.r1a.grassmaps.plugin.api.model.RouteNetworkNode
import nl.r1a.grassmaps.plugin.api.model.RouteRelation
import nl.r1a.grassmaps.plugin.api.model.RouteRelations
import nl.r1a.grassmaps.plugin.api.model.WayAlertElementType
import nl.r1a.grassmaps.plugin.api.model.WayAlertHit
import nl.r1a.grassmaps.plugin.api.provider.Route

/**
 * The installed packs as one logical [RegionAccess], the way-alert and
 * relation counterpart of [RegionTileSources] (T-0516).
 *
 * Every installed pack contributes its `way-alerts` and `relations`
 * components, in region-id order. Both packs come from one country build, so a
 * border feature or relation is present in both neighbours with the same OSM
 * identity. The first pack in that order is the one that serves it, exactly as
 * the first pack serves a shared map tile, and the other copy is never added to
 * the result: one feature, one corridor hit, one relation.
 *
 * The two components are independent, so a pack may carry either one. A pack
 * that cannot answer - a corrupt component or a failing native read - is left
 * out; the other packs still answer. The failure is reported only when no pack
 * answers, which keeps a single pack's typed failure exactly as [RegionReader]
 * returned it. A broken pack at mount time never reaches this class at all:
 * [RegionMounts.installed] leaves it out.
 */
class RegionReaders private constructor(
    /** The installed packs, in the order that decides feature ownership. */
    val packs: List<Pack>,
) : RegionAccess {
    /** One installed pack and the reader of its components. */
    class Pack internal constructor(
        val region: String,
        internal val reader: RegionReader,
    )

    /** The installed region ids, in the order that decides feature ownership. */
    val regions: List<String> = packs.map { it.region }

    override suspend fun wayAlertFeatures(
        route: Route,
        corridorMeters: Double,
    ): Outcome<List<WayAlertHit>> = mergeWayAlerts(packs.map { it.reader.wayAlertFeatures(route, corridorMeters) })

    override suspend fun routeRelations(): Outcome<RouteRelations> = mergeRelations(packs.map { it.reader.routeRelations() })

    companion object {
        /**
         * The logical reader of [installed]. Packs are ordered by region id so
         * that the same store always resolves a shared feature or relation to
         * the same pack. No installed pack is the inert source.
         */
        fun open(installed: List<MountedRegion>): RegionAccess {
            if (installed.isEmpty()) {
                return NoRegionAccess
            }
            val packs =
                installed
                    .sortedBy { it.region }
                    .map { mounted -> Pack(mounted.region, RegionReader(mounted.directory)) }
            return RegionReaders(packs)
        }
    }
}

/** The identity of one OSM feature across packs, as the way-alert latch uses it. */
private data class AlertIdentity(
    val elementType: WayAlertElementType,
    val osmId: Long,
)

/**
 * One corridor answer over every pack: the first pack's copy of a feature is
 * kept, and the hits keep the along-route order the native reader produces.
 */
private fun mergeWayAlerts(outcomes: List<Outcome<List<WayAlertHit>>>): Outcome<List<WayAlertHit>> {
    val hits = LinkedHashMap<AlertIdentity, WayAlertHit>()
    var answered = false
    var failure: Outcome.Failure? = null
    for (outcome in outcomes) {
        when (outcome) {
            is Outcome.Failure -> {
                if (failure == null) failure = outcome
            }

            is Outcome.Success -> {
                answered = true
                for (hit in outcome.value) {
                    hits.putIfAbsent(AlertIdentity(hit.feature.elementType, hit.feature.osmId), hit)
                }
            }
        }
    }
    return when {
        answered -> Outcome.Success(hits.values.sortedBy { it.alongRouteMeters })
        failure != null -> failure
        else -> Outcome.Success(emptyList())
    }
}

/**
 * One relation document over every pack. A way, relation or node id present in
 * more than one pack is taken from the first pack, so its geometry and fields
 * are read once. Packs keep their own order, and each relation's ways are in
 * the same document, so the merged value satisfies [RouteRelations].
 */
private fun mergeRelations(outcomes: List<Outcome<RouteRelations>>): Outcome<RouteRelations> {
    val ways = LinkedHashMap<Long, List<GeoPoint>>()
    val relations = LinkedHashMap<Long, RouteRelation>()
    val nodes = LinkedHashMap<Long, RouteNetworkNode>()
    var answered = false
    var failure: Outcome.Failure? = null
    for (outcome in outcomes) {
        when (outcome) {
            is Outcome.Failure -> {
                if (failure == null) failure = outcome
            }

            is Outcome.Success -> {
                answered = true
                val value = outcome.value
                value.ways.forEach { (id, points) -> ways.putIfAbsent(id, points) }
                value.relations.forEach { relation -> relations.putIfAbsent(relation.id, relation) }
                value.nodes.forEach { node -> nodes.putIfAbsent(node.id, node) }
            }
        }
    }
    return when {
        answered -> {
            Outcome.Success(
                RouteRelations(
                    ways = ways,
                    relations = relations.values.toList(),
                    nodes = nodes.values.toList(),
                ),
            )
        }

        failure != null -> {
            failure
        }

        else -> {
            Outcome.Success(RouteRelations.EMPTY)
        }
    }
}
