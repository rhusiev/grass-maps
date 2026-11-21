package nl.r1a.grassmaps.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.r1a.grassmaps.plugin.api.model.InstalledPack
import nl.r1a.grassmaps.regions.MountedRegion
import nl.r1a.grassmaps.regions.RegionMountResolver
import nl.r1a.grassmaps.regions.RegionMounts
import java.io.File

/**
 * One mounted pack's search section. [name] is the region id every hit and
 * history entry carries; [directory] is the version directory the reader maps.
 */
internal data class SearchIndex(
    val name: String,
    val directory: File,
)

/** The search sections of the mounted packs, in region-id order. */
internal data class SearchIndexSet(
    val indexes: List<SearchIndex> = emptyList(),
) {
    /**
     * The pack the single-region readers treat as active: the first mounted
     * pack in region-id order, whose tiles the map serves a shared tile from,
     * and whose components the elevation, routing and proximity readers mount.
     * Search visits every [indexes] entry instead (T-0633). A pack without a
     * section is skipped.
     */
    val active: SearchIndex? get() = indexes.firstOrNull()
}

/** The section of every mounted pack, in region-id order; a pack without one is left out. */
internal fun searchIndexesOf(mounted: List<MountedRegion>): SearchIndexSet =
    SearchIndexSet(
        mounted
            .sortedBy { it.region }
            .mapNotNull { region -> region.search?.let { SearchIndex(region.region, it) } },
    )

/**
 * Keeps the search sections in step with the packs the region store publishes
 * (T-0620). The screen searches every section (T-0633); [SearchIndexSet.active]
 * remains the pack the single-region readers use.
 *
 * [start] mounts the store once and then re-mounts it only when the published
 * installed list names a different set of region versions, so a keystroke or a
 * frame never reads the disk. The mount reads manifests and file sizes only:
 * no index is opened here. The reader opens a section when a query first
 * reaches [nl.r1a.grassmap.places.PlacesAccess], so the privacy and plugin
 * switches see the same call path as before.
 */
internal class SearchIndexes(
    private val root: () -> File?,
    private val installed: () -> Flow<List<InstalledPack>>?,
    private val scope: CoroutineScope,
    private val mount: suspend (File) -> RegionMounts = { file ->
        withContext(Dispatchers.IO) { RegionMountResolver(file).resolveAll() }
    },
    private val log: (String) -> Unit = {},
) {
    private val mutable = MutableStateFlow(SearchIndexSet())
    private val startMutex = Mutex()

    @Volatile
    private var mountedRoot: File? = null

    @Volatile
    private var started = false

    val state: StateFlow<SearchIndexSet> = mutable.asStateFlow()

    /** Resolves the mounted sections and follows the store; call off Main. */
    suspend fun start() {
        if (started) {
            return
        }
        startMutex.withLock {
            if (started) {
                return
            }
            val root =
                root() ?: run {
                    started = true
                    return
                }
            mountedRoot = root
            val mounts = mount(root)
            publish(mounts.installed)
            started = true
            val published = installed() ?: return
            scope.launch { follow(published, mountedKeys(mounts.installed)) }
        }
    }

    /** The section of the mounted pack named [name], or null. It never reads the disk. */
    fun directory(name: String): File? =
        mutable.value.indexes
            .firstOrNull { it.name == name }
            ?.directory

    private suspend fun follow(
        published: Flow<List<InstalledPack>>,
        initial: List<Pair<String, String>>,
    ) {
        var served = initial
        published
            .map { packs -> packKeys(packs) }
            .distinctUntilChanged()
            .collect { packs ->
                if (packs == served) {
                    return@collect
                }
                val root = mountedRoot ?: return@collect
                val mounts = mount(root)
                publish(mounts.installed)
                served = mountedKeys(mounts.installed)
            }
    }

    private fun publish(mounted: List<MountedRegion>) {
        val next = searchIndexesOf(mounted)
        if (next == mutable.value) {
            return
        }
        mutable.value = next
        val active = next.active
        if (active == null) {
            log("search index: no mounted pack has a section")
        } else {
            log(
                "search indexes: ${next.indexes.size} section(s), " +
                    "first=${active.name} dir=${active.directory.absolutePath}",
            )
        }
    }

    private fun packKeys(packs: List<InstalledPack>): List<Pair<String, String>> =
        packs.map { pack -> pack.region to pack.version }.sortedWith(PACK_ORDER)

    private fun mountedKeys(mounted: List<MountedRegion>): List<Pair<String, String>> =
        mounted.map { region -> region.region to region.version }.sortedWith(PACK_ORDER)

    private companion object {
        val PACK_ORDER: Comparator<Pair<String, String>> = compareBy({ it.first }, { it.second })
    }
}
