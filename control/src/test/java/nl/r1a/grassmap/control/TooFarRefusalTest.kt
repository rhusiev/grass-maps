package nl.r1a.grassmap.control

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import nl.r1a.grassmap.plugin.api.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

class TooFarRefusalTest {
    @Test
    fun aTooFarRouteIsATypedRefusalWithItsLimit() {
        val error = JsonRpcCodec.fromAppError(AppError.TooFar(LIMIT_METERS))

        assertEquals(RpcCodes.INVALID_PARAMS, error.code)
        assertEquals("too_far", (error.data["kind"] as JsonPrimitive).content)
        assertEquals(LIMIT_METERS, (error.data["limit_meters"] as JsonPrimitive).doubleOrNull)
    }

    private companion object {
        const val LIMIT_METERS = 250_000.0
    }
}
