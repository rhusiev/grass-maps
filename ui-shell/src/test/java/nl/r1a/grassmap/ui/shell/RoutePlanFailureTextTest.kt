package nl.r1a.grassmap.ui.shell

import nl.r1a.grassmap.plugin.api.model.RoutePlanFailure
import nl.r1a.grassmap.plugin.api.model.RoutePlanState
import nl.r1a.grassmap.plugin.api.model.RoutePlanStatus
import nl.r1a.grassmap.plugin.api.service.Strings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The failed plan's status line, read from the app's own string files. */
class RoutePlanFailureTextTest {
    private val english = TestStringCatalog.read(APP_ENGLISH).resolver()
    private val ukrainian = TestStringCatalog.read(APP_UKRAINIAN).resolver()

    @Test
    fun aTooFarRouteNamesTheLimitInEnglish() {
        assertEquals(
            "This route is too far: the limit for this mode is 250.0 km",
            text(tooFar(PEDESTRIAN_LIMIT_METERS), english),
        )
    }

    @Test
    fun aTooFarRouteNamesTheLimitInUkrainian() {
        assertEquals(
            "Маршрут надто далекий: ліміт для цього режиму - 500.0 км",
            text(tooFar(BICYCLE_LIMIT_METERS), ukrainian),
        )
    }

    @Test
    fun aTooFarRouteWithoutALimitFallsBackToTheGenericText() {
        val state = tooFar(PEDESTRIAN_LIMIT_METERS).copy(maxDistanceMeters = null)

        assertEquals("No route found for these points", text(state, english))
    }

    @Test
    fun theOtherFailuresKeepTheirTexts() {
        val noRoute = RoutePlanState(status = RoutePlanStatus.Failed, failure = RoutePlanFailure.NoRoute)

        assertEquals("No route found for these points", text(noRoute, english))
    }

    private fun text(
        state: RoutePlanState,
        strings: Strings,
    ): String = routePlanFailureText(state, strings, Locale.US)

    private fun tooFar(limitMeters: Double): RoutePlanState =
        RoutePlanState(
            status = RoutePlanStatus.Failed,
            failure = RoutePlanFailure.TooFar,
            maxDistanceMeters = limitMeters,
        )

    private companion object {
        const val APP_ENGLISH = "../app/src/main/res/values/strings.xml"
        const val APP_UKRAINIAN = "../app/src/main/res/values-uk/strings.xml"
        const val PEDESTRIAN_LIMIT_METERS = 250_000.0
        const val BICYCLE_LIMIT_METERS = 500_000.0
    }
}
