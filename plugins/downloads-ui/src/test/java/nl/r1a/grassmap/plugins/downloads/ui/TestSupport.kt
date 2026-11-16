package nl.r1a.grassmap.plugins.downloads.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import nl.r1a.grassmap.plugin.api.command.Command
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.ElevationAccess
import nl.r1a.grassmap.plugin.api.host.PluginServices
import nl.r1a.grassmap.plugin.api.host.RegionDownloadsAccess
import nl.r1a.grassmap.plugin.api.host.StreamAccess
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.DownloadProblem
import nl.r1a.grassmap.plugin.api.model.DownloadTransfer
import nl.r1a.grassmap.plugin.api.model.DownloadTransferState
import nl.r1a.grassmap.plugin.api.model.DownloadsData
import nl.r1a.grassmap.plugin.api.model.DownloadsState
import nl.r1a.grassmap.plugin.api.model.InstalledPack
import nl.r1a.grassmap.plugin.api.model.LocationFix
import nl.r1a.grassmap.plugin.api.model.MobileDataPolicy
import nl.r1a.grassmap.plugin.api.model.RegionCatalog
import nl.r1a.grassmap.plugin.api.model.RegionProblem
import nl.r1a.grassmap.plugin.api.provider.Provider
import nl.r1a.grassmap.plugin.api.service.Clock
import nl.r1a.grassmap.plugin.api.service.LocationService
import nl.r1a.grassmap.plugin.api.widget.WidgetDescriptor

internal class RecordingContext(
    override val services: PluginServices,
) : PluginContext {
    override val pluginId: PluginId = PluginId(DownloadsUiContract.PLUGIN_ID)

    val commands = mutableListOf<Command<*, *>>()
    val streams = mutableListOf<StreamDescriptor<*>>()
    val providers = mutableListOf<Provider>()
    val widgets = mutableListOf<WidgetDescriptor>()

    override fun addCommand(command: Command<*, *>) {
        commands += command
    }

    override fun addStream(stream: StreamDescriptor<*>) {
        streams += stream
    }

    override fun addProvider(provider: Provider) {
        providers += provider
    }

    override fun addWidget(widget: WidgetDescriptor) {
        widgets += widget
    }

    @Suppress("UNCHECKED_CAST")
    fun stateFlow(): Flow<DownloadsState> = streams.single { it.id == DownloadsUiContract.stateStream }.flow as Flow<DownloadsState>
}

internal class FakeServices(
    override val regionDownloads: RegionDownloadsAccess,
    fixesFlow: Flow<LocationFix?> = emptyFlow(),
) : PluginServices {
    override val streams: StreamAccess =
        object : StreamAccess {
            override fun <T : Any> subscribe(streamId: StreamId): Flow<T> = emptyFlow()
        }

    override val location: LocationService =
        object : LocationService {
            override val fixes: Flow<LocationFix?> = fixesFlow

            override fun hasPermission(): Boolean = false
        }

    override val elevation: ElevationAccess = ElevationAccess { Outcome.Success(null) }

    override val camera: Flow<Camera> = emptyFlow()

    override val clock: Clock = Clock { 0L }
}

/**
 * A typed fake of the store. It records what the plugin asked for and mutates
 * its own data the way [nl.r1a.grassmap.regions.RegionStore] does, so the
 * plugin's published stream is testable without a socket or a file.
 *
 * The token is recorded as present or absent and never as a value.
 */
internal class FakeDownloadsAccess(
    initial: DownloadsData = DownloadsData(serverUrl = SERVER),
) : RegionDownloadsAccess {
    private val mutable = MutableStateFlow(initial)
    override val data: StateFlow<DownloadsData> = mutable.asStateFlow()

    val calls = mutableListOf<String>()
    var catalog: RegionCatalog? = null
    var refreshProblem: DownloadProblem? = null

    override suspend fun setServer(
        url: String,
        token: String?,
    ): Outcome<Unit> {
        calls += "server:$url:${if (token.isNullOrBlank()) "none" else "present"}"
        mutable.update { it.copy(serverUrl = url, tokenPresent = !token.isNullOrBlank()) }
        return Outcome.Success(Unit)
    }

    override suspend fun refresh(): Outcome<Unit> {
        calls += "refresh"
        val problem = refreshProblem
        mutable.update {
            it.copy(
                catalog = if (problem == null) catalog else it.catalog,
                catalogProblem = problem,
            )
        }
        return if (problem == null) {
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(AppError.Server(problem.status))
        }
    }

    override suspend fun startDownload(region: String): Outcome<Unit> {
        calls += "download:$region"
        val bytes = catalog?.regions?.firstOrNull { it.id == region }?.bytes ?: 0L
        mutable.update {
            it.copy(
                transfers =
                    it.transfers +
                        DownloadTransfer(
                            region = region,
                            state = DownloadTransferState.WAITING,
                            bytesTotal = bytes,
                        ),
            )
        }
        return Outcome.Success(Unit)
    }

    override suspend fun cancel(region: String): Outcome<Unit> {
        calls += "cancel:$region"
        mutable.update { data -> data.copy(transfers = data.transfers.filterNot { it.region == region }) }
        return Outcome.Success(Unit)
    }

    override suspend fun remove(region: String): Outcome<Unit> {
        calls += "remove:$region"
        mutable.update { data -> data.copy(installed = data.installed.filterNot { it.region == region }) }
        return Outcome.Success(Unit)
    }

    override suspend fun setMobileDataPolicy(policy: MobileDataPolicy): Outcome<Unit> {
        calls += "mobile:${policy.name}"
        mutable.update { it.copy(mobileDataPolicy = policy) }
        return Outcome.Success(Unit)
    }

    override suspend fun setAutoUpdate(enabled: Boolean): Outcome<Unit> {
        calls += "auto:$enabled"
        mutable.update { it.copy(autoUpdate = enabled) }
        return Outcome.Success(Unit)
    }

    override suspend fun updateAll(): Outcome<Unit> {
        calls += "update-all:${mutable.value.updates.joinToString(",") { it.id }}"
        return Outcome.Success(Unit)
    }

    override suspend fun useMobileDataNow(): Outcome<Unit> {
        calls += "mobile-now"
        return Outcome.Success(Unit)
    }

    fun useCatalog(value: RegionCatalog) {
        catalog = value
        mutable.update { it.copy(catalog = value) }
    }

    fun transfer(
        region: String,
        transfer: DownloadTransfer,
    ) {
        mutable.update { data ->
            data.copy(transfers = data.transfers.filterNot { it.region == region } + transfer)
        }
    }

    fun recordProblem(
        region: String,
        problem: DownloadProblem,
    ) {
        mutable.update { data ->
            data.copy(
                transfers = data.transfers.filterNot { it.region == region },
                problems =
                    data.problems.filterNot { it.region == region } +
                        RegionProblem(region, problem),
            )
        }
    }

    /** Completes a queued download the way the store would after a good archive. */
    fun complete(
        region: String,
        version: String,
    ) {
        val entry = catalog?.regions?.firstOrNull { it.id == region }
        mutable.update { data ->
            data.copy(
                transfers = data.transfers.filterNot { it.region == region },
                installed =
                    data.installed.filterNot { it.region == region } +
                        InstalledPack(
                            region = region,
                            version = version,
                            bytes = entry?.bytes ?: 0L,
                            parts = entry?.parts?.map { it.kind }.orEmpty(),
                            dataDate = entry?.dataDate,
                        ),
            )
        }
    }

    companion object {
        const val SERVER: String = "https://maps.test"
    }
}
