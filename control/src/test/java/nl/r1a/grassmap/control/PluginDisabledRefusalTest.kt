package nl.r1a.grassmaps.control

import kotlinx.serialization.json.JsonPrimitive
import nl.r1a.grassmaps.plugin.api.contract.PluginId
import nl.r1a.grassmaps.plugin.api.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

/** A switch the user turned off reaches the control channel as its own refusal kind (T-0607). */
class PluginDisabledRefusalTest {
    @Test
    fun aDisabledPluginIsATypedRefusalWithItsPluginId() {
        val error = JsonRpcCodec.fromAppError(AppError.PluginDisabled(PluginId("map.contours")))

        assertEquals(RpcCodes.INTERNAL, error.code)
        assertEquals("plugin_disabled", (error.data["kind"] as JsonPrimitive).content)
        assertEquals("map.contours", (error.data["plugin"] as JsonPrimitive).content)
    }
}
