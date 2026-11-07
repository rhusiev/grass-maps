package nl.r1a.grassmap.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.r1a.grassmap.plugin.api.contract.StreamId
import nl.r1a.grassmap.plugin.api.contract.WidgetId
import nl.r1a.grassmap.plugin.api.error.AppError
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.model.PluginState
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRegistryTest {
    @Test
    fun aDeclaredWidgetResolvesAndActivatesItsOwner() =
        runTest {
            val host =
                testHost(
                    scheduler = testScheduler,
                    manifest = testManifest(streams = listOf(testStreamDeclaration()), widgets = listOf(testWidgetDeclaration())),
                    streams = listOf(testGeneratedStream()),
                ) {
                    TestPlugin(
                        streams = listOf(TestStream(MutableStateFlow(0)).descriptor),
                        widgets = listOf(TestWidget.descriptor()),
                    )
                }

            assertEquals(PluginState.DECLARED, host.statusOf(host.manifests().single().id)?.state)
            assertEquals(Outcome.Success(TestWidget.descriptor()), host.widget(TestWidget.WIDGET_ID))
            assertEquals(PluginState.RUNNING, host.statusOf(host.manifests().single().id)?.state)
        }

    @Test
    fun anUndeclaredWidgetFailsActivation() =
        runTest {
            val host =
                testHost(
                    scheduler = testScheduler,
                    manifest = testManifest(streams = listOf(testStreamDeclaration())),
                    streams = listOf(testGeneratedStream()),
                ) {
                    TestPlugin(
                        streams = listOf(TestStream(MutableStateFlow(0)).descriptor),
                        widgets = listOf(TestWidget.descriptor()),
                    )
                }

            assertEquals(
                Outcome.Failure(AppError.PluginFailed(host.manifests().single().id)),
                host.stream(TestStream.STREAM_ID),
            )
        }

    @Test
    fun aWidgetWhoseValueTypeDisagreesWithItsStreamFailsActivation() =
        runTest {
            val host =
                testHost(
                    scheduler = testScheduler,
                    manifest =
                        testManifest(
                            streams = listOf(testStreamDeclaration()),
                            widgets = listOf(testWidgetDeclaration(valueType = "kotlin.String")),
                        ),
                    streams = listOf(testGeneratedStream()),
                ) {
                    TestPlugin(
                        streams = listOf(TestStream(MutableStateFlow(0)).descriptor),
                        widgets = listOf(TestWidget.descriptor(valueType = "kotlin.String")),
                    )
                }

            assertEquals(
                Outcome.Failure(AppError.PluginFailed(host.manifests().single().id)),
                host.widget(TestWidget.WIDGET_ID),
            )
        }

    @Test
    fun aWidgetOverAMissingStreamFailsActivation() =
        runTest {
            val host =
                testHost(
                    scheduler = testScheduler,
                    manifest =
                        testManifest(
                            streams = listOf(testStreamDeclaration()),
                            widgets = listOf(testWidgetDeclaration(streamId = StreamId("test.missing"))),
                        ),
                    streams = listOf(testGeneratedStream()),
                ) {
                    TestPlugin(
                        streams = listOf(TestStream(MutableStateFlow(0)).descriptor),
                        widgets = listOf(TestWidget.descriptor(streamId = StreamId("test.missing"))),
                    )
                }

            assertEquals(
                Outcome.Failure(AppError.PluginFailed(host.manifests().single().id)),
                host.widget(TestWidget.WIDGET_ID),
            )
        }

    @Test
    fun anUnknownWidgetIsNotFound() =
        runTest {
            val host = testHost(testScheduler, testManifest()) { TestPlugin() }

            assertEquals(
                Outcome.Failure(AppError.NotFound),
                host.widget(WidgetId("test.unknown")),
            )
        }

    @Test
    fun declaredWidgetsAreReadableWithoutActivatingThePlugin() =
        runTest {
            val host = testHost(testScheduler, testManifest(widgets = listOf(testWidgetDeclaration()))) { TestPlugin() }

            assertEquals(listOf(testWidgetDeclaration()), host.declaredWidgets())
            assertEquals(PluginState.DECLARED, host.statusOf(host.manifests().single().id)?.state)
        }

    @Test
    fun aWidgetWithADifferentDescriptorThanItsDeclarationFailsActivation() =
        runTest {
            val host =
                testHost(
                    scheduler = testScheduler,
                    manifest =
                        testManifest(
                            streams = listOf(testStreamDeclaration()),
                            widgets = listOf(testWidgetDeclaration(title = "plugin.test.other")),
                        ),
                    streams = listOf(testGeneratedStream()),
                ) {
                    TestPlugin(
                        streams = listOf(TestStream(MutableStateFlow(0)).descriptor),
                        widgets = listOf(TestWidget.descriptor()),
                    )
                }

            assertEquals(
                Outcome.Failure(AppError.PluginFailed(host.manifests().single().id)),
                host.widget(TestWidget.WIDGET_ID),
            )
        }
}
