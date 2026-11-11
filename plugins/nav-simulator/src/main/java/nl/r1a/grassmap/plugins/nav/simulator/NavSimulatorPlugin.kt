package nl.r1a.grassmap.plugins.nav.simulator

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.r1a.grassmap.plugin.api.contract.Plugin
import nl.r1a.grassmap.plugin.api.contract.PluginContext
import nl.r1a.grassmap.plugin.api.error.Outcome
import nl.r1a.grassmap.plugin.api.host.StreamDescriptor

/**
 * The simulated navigation plugin (T-0315).
 *
 * It owns one [NavigationSimulator] for the process. Activation registers the
 * start and stop commands and the status stream; the simulator itself drives
 * the production session and follower, so this plugin adds no navigation
 * policy.
 */
class NavSimulatorPlugin(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Plugin {
    private var scope: CoroutineScope? = null

    override suspend fun activate(context: PluginContext): Outcome<Unit> {
        val pluginScope = CoroutineScope(SupervisorJob() + dispatcher)
        val simulator = NavigationSimulator(scope = pluginScope)
        scope?.cancel()
        scope = pluginScope
        context.addCommand(StartSimulationCommand(simulator, context.services.streams))
        context.addCommand(StopSimulationCommand(simulator))
        context.addStream(
            StreamDescriptor(
                id = NavSimulatorContract.statusStream,
                serializer = SimulatorStatus.serializer(),
                flow = simulator.status,
            ),
        )
        return Outcome.Success(Unit)
    }

    override fun deactivate() {
        scope?.cancel()
        scope = null
    }
}
