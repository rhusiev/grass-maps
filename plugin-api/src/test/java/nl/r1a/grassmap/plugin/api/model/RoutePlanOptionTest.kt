package nl.r1a.grassmap.plugin.api.model

import kotlinx.serialization.json.Json
import nl.r1a.grassmap.plugin.api.provider.RouteInstruction
import nl.r1a.grassmap.plugin.api.provider.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Test

/** The plan option's guidance model survives plugin-state serialization (T-0309). */
class RoutePlanOptionTest {
    @Test
    fun segmentsAndInstructionsRoundTripWithTheirAttributes() {
        val option =
            RoutePlanOption(
                geometry = listOf(GeoPoint(49.84, 24.03), GeoPoint(49.85, 24.04)),
                distanceMeters = 1_000.0,
                durationSeconds = 720.0,
                arrivalEpochMillis = 1_700_000_720_000L,
                segments =
                    listOf(
                        RouteSegment(
                            startIndex = 0,
                            endIndex = 2,
                            distanceMeters = 1_000.0,
                            durationSeconds = 720.0,
                            attributes =
                                mapOf(
                                    "surface" to AttributeValue.TextValue("gravel"),
                                    "grade" to AttributeValue.NumberValue(3.5),
                                    "access" to AttributeValue.BooleanValue(true),
                                ),
                        ),
                    ),
                instructions = listOf(RouteInstruction("Turn left", "Franka", 400.0, 60.0, "10")),
            )

        val encoded = Json.encodeToString(RoutePlanOption.serializer(), option)
        val decoded = Json.decodeFromString(RoutePlanOption.serializer(), encoded)

        assertEquals(option, decoded)
    }
}
