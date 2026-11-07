package nl.r1a.grassmap.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmap.app.generated.GeneratedPlugins
import nl.r1a.grassmap.core.PluginHost
import nl.r1a.grassmap.core.ProviderRegistry
import nl.r1a.grassmap.plugin.api.contract.CancellationHandle
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.contract.PluginId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.manifest.PluginContributions
import nl.r1a.grassmap.plugin.api.manifest.PluginFactory
import nl.r1a.grassmap.plugin.api.manifest.PluginManifest
import nl.r1a.grassmap.plugin.api.manifest.ProviderDeclaration
import nl.r1a.grassmap.plugin.api.model.BoundingBox
import nl.r1a.grassmap.plugin.api.model.Camera
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LayerSlot
import nl.r1a.grassmap.plugin.api.model.LayerType
import nl.r1a.grassmap.plugin.api.model.MapSourceIds
import nl.r1a.grassmap.plugin.api.model.SlottedLayer
import nl.r1a.grassmap.plugin.api.provider.AttributePage
import nl.r1a.grassmap.plugin.api.provider.AttributeRequest
import nl.r1a.grassmap.plugin.api.provider.CostModifier
import nl.r1a.grassmap.plugin.api.provider.CostModifierRequest
import nl.r1a.grassmap.plugin.api.provider.DataSource
import nl.r1a.grassmap.plugin.api.provider.DataSourceContract
import nl.r1a.grassmap.plugin.api.provider.DataSourceDescription
import nl.r1a.grassmap.plugin.api.provider.ProviderContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSourceAccessTest {
    @Test
    fun theSyntheticSourceResolvesThroughTheRegistry() =
        runTest {
            val dataSources = DataSourceAccess(registry(GeneratedPlugins.plugins))

            val page =
                dataSources.attributes(
                    AttributeRequest(AREA, timeMillis = 0L, limit = 2),
                    CancellationHandle.None,
                )
            val description = dataSources.describe()

            assertTrue(page is Outcome.Success)
            val kinds =
                when (val resolved = description) {
                    is Outcome.Success -> resolved.value.attributeKinds
                    is Outcome.Failure -> throw AssertionError("describe failed: ${resolved.error}")
                }
            assertEquals(setOf("elevation"), kinds)
        }

    @Test
    fun theMapLayerIsAddedUnderTheProvidersOwnPluginId() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val dataSources = DataSourceAccess(registry(GeneratedPlugins.plugins))

            assertEquals(Outcome.Success(Unit), installDataSourceLayer(dataSources, host))

            val layer = host.layers.single()
            assertEquals(PluginId("data.synthetic"), layer.owner)
            assertEquals(LayerSlot.Overlays, layer.slot)
            assertEquals(LayerType.Line, layer.spec.type)
            assertEquals(MapSourceIds.CONTOURS, layer.spec.source)
            assertEquals(
                JsonArray(listOf(JsonPrimitive("=="), JsonPrimitive("idx"), JsonPrimitive(true))),
                layer.spec.filter,
            )
        }

    @Test
    fun anAbsentProviderLeavesTheMapUntouched() =
        runTest {
            val host = FakeMapHost(CAMERA)
            val dataSources = DataSourceAccess(registry(emptyList()))

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                installDataSourceLayer(dataSources, host),
            )
            assertTrue(host.layers.isEmpty())
            assertEquals(
                Outcome.Failure(AppError.NotFound),
                dataSources.mapLayer(),
            )
        }

    @Test
    fun aThrowingProviderIsATypedFailureForEveryContractCall() =
        runTest {
            val pluginId = PluginId("data.throwing")
            val dataSources = DataSourceAccess(registry(listOf(throwingPlugin(pluginId))), pluginId)
            val expected = Outcome.Failure(AppError.PluginFailed(pluginId))

            assertTrue(dataSources.describe() == expected)
            assertTrue(
                dataSources.attributes(
                    AttributeRequest(AREA, timeMillis = 0L, limit = 2),
                    CancellationHandle.None,
                ) == expected,
            )
            assertTrue(
                dataSources.costModifiers(
                    CostModifierRequest(waypoints = emptyList()),
                    CancellationHandle.None,
                ) == expected,
            )
            assertTrue(dataSources.mapLayer() == expected)
        }

    private fun registry(plugins: List<PluginFactory>): ProviderRegistry {
        val host =
            PluginHost(
                plugins = plugins,
                streams = emptyList(),
                commands = emptyList(),
                dispatcher = Dispatchers.Unconfined,
            )
        return ProviderRegistry(host, failureLimit = 10)
    }

    private companion object {
        val CAMERA = Camera(center = GeoPoint(49.95, 23.945), zoom = 13.0)
        val AREA =
            BoundingBox(
                southWest = GeoPoint(49.94, 23.94),
                northEast = GeoPoint(49.96, 23.96),
            )
    }
}

private fun throwingPlugin(pluginId: PluginId): PluginFactory =
    PluginFactory(
        manifest =
            PluginManifest(
                id = pluginId,
                name = "plugin.throwing.name",
                description = "plugin.throwing.description",
                version = "1.0.0",
                api = "1.0.0",
                implementation = "test.ThrowingPlugin",
                contributes =
                    PluginContributions(
                        providers =
                            listOf(
                                ProviderDeclaration(
                                    contract = DataSourceContract.NAME,
                                    version = DataSourceContract.VERSION,
                                ),
                            ),
                    ),
                activation = listOf("provider"),
            ),
        create = { ThrowingPlugin() },
    )

private class ThrowingPlugin : Plugin {
    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        context.addProvider(ThrowingDataSource())
        return Outcome.Success(Unit)
    }
}

private class ThrowingDataSource : DataSource {
    override val contract: ProviderContract = DataSourceContract.REFERENCE

    override fun describe(): DataSourceDescription = throw IllegalStateException("describe")

    override suspend fun attributes(
        request: AttributeRequest,
        cancellation: CancellationHandle,
    ): Outcome<AttributePage> = throw IllegalStateException("attributes")

    override suspend fun costModifiers(
        request: CostModifierRequest,
        cancellation: CancellationHandle,
    ): Outcome<List<CostModifier>> = throw IllegalStateException("costModifiers")

    override fun mapLayer(): SlottedLayer? = throw IllegalStateException("mapLayer")
}
