package nl.r1a.grassmaps.regions

/**
 * The component kind a logical map source reads from. The source ids of
 * T-0020 (`world`, `region`, `contours`) name zoom bands, not files: `world`
 * and `region` both read [MAP], and only their zoom range differs.
 */
enum class TileKind(
    val componentKind: String,
) {
    MAP("map"),
    CONTOURS("contours"),
}

/** One tile as the map receives it: the bytes, and the pack that supplied them. */
class ServedTile internal constructor(
    val bytes: ByteArray,
    val region: String,
)

/**
 * The installed packs as one logical source per component kind.
 *
 * Every installed pack contributes its `map` and `contours` components to the
 * kind they belong to, in region-id order. A shared border tile is present in
 * both neighbours byte for byte (one country build, split with `pmtiles
 * extract`), so the first pack in that order is the one that serves it and the
 * other copy is never requested. That is what keeps a border's labels from
 * being drawn twice: one source, one tile, one copy.
 *
 * The store is read only. A pack whose archive cannot be read is left out of
 * its kind; the other packs are unaffected.
 */
class RegionTileSources private constructor(
    /** The installed packs, in the order that decides tile ownership. */
    val packs: List<Pack>,
) {
    /** One installed pack and the archives it offers, by kind. */
    class Pack internal constructor(
        val region: String,
        val version: String,
        private val archives: Map<TileKind, PmtilesArchive>,
    ) {
        internal fun archive(kind: TileKind): PmtilesArchive? = archives[kind]
    }

    /** The installed region ids, in the order that decides tile ownership. */
    val regions: List<String> = packs.map { it.region }

    /** True when at least one pack has a component of [kind]. */
    fun has(kind: TileKind): Boolean = packs.any { it.archive(kind) != null }

    /**
     * The tile [zoom]/[x]/[y] of [kind], from exactly one pack, or null when no
     * installed pack holds it. The bytes are decompressed MVT.
     */
    fun tile(
        kind: TileKind,
        zoom: Int,
        x: Long,
        y: Long,
    ): ServedTile? {
        for (pack in packs) {
            val archive = pack.archive(kind) ?: continue
            if (!archive.hasTile(zoom, x, y)) {
                continue
            }
            val bytes = archive.tile(zoom, x, y) ?: return null
            return ServedTile(bytes, pack.region)
        }
        return null
    }

    companion object {
        /** An empty store: no pack, and every tile request answers nothing. */
        fun empty(): RegionTileSources = RegionTileSources(emptyList())

        /**
         * The logical sources of [installed]. Packs are ordered by region id so
         * that the same store always resolves a shared tile to the same pack.
         */
        fun open(installed: List<MountedRegion>): RegionTileSources {
            val packs =
                installed
                    .sortedBy { it.region }
                    .map { mounted ->
                        val archives =
                            buildMap {
                                PmtilesArchive.open(mounted.map)?.let { put(TileKind.MAP, it) }
                                mounted.contours?.let { contours ->
                                    PmtilesArchive.open(contours)?.let { put(TileKind.CONTOURS, it) }
                                }
                            }
                        Pack(mounted.region, mounted.version, archives)
                    }
            return RegionTileSources(packs)
        }
    }
}
