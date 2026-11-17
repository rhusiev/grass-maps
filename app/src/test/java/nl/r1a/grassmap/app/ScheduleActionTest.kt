package nl.r1a.grassmap.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleActionTest {
    @Test
    fun turningAutomaticUpdatesOffCancelsTheJobWhetherOrNotOneIsPending() {
        assertEquals(ScheduleAction.CANCEL, decideSchedule(enabled = false, wifiOnly = true, pendingWifiOnly = true))
        assertEquals(ScheduleAction.CANCEL, decideSchedule(enabled = false, wifiOnly = true, pendingWifiOnly = null))
    }

    @Test
    fun aPendingJobWithTheWantedNetworkStaysSoItsPeriodIsNotRestarted() {
        assertEquals(ScheduleAction.KEEP, decideSchedule(enabled = true, wifiOnly = true, pendingWifiOnly = true))
        assertEquals(ScheduleAction.KEEP, decideSchedule(enabled = true, wifiOnly = false, pendingWifiOnly = false))
    }

    @Test
    fun withoutAPendingJobOrWithAnotherNetworkTheJobIsScheduled() {
        assertEquals(ScheduleAction.SCHEDULE, decideSchedule(enabled = true, wifiOnly = true, pendingWifiOnly = null))
        assertEquals(ScheduleAction.SCHEDULE, decideSchedule(enabled = true, wifiOnly = false, pendingWifiOnly = true))
        assertEquals(ScheduleAction.SCHEDULE, decideSchedule(enabled = true, wifiOnly = true, pendingWifiOnly = false))
    }
}
