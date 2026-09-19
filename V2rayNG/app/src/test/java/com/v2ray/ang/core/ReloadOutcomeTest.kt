package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadOutcomeTest {

    @Test
    fun aReloadThatBroughtXrayBackLeavesTheServiceRunning() {
        assertEquals(ReloadOutcome.KEEP_RUNNING, ReloadOutcome.of(coreRunning = true, stoppedMeanwhile = false))
    }

    @Test
    fun aReloadThatLeftXrayStoppedStopsTheWholeService() {
        // Otherwise the tunnel and the Aether core stay up around a core that is gone.
        assertEquals(ReloadOutcome.STOP_SERVICE, ReloadOutcome.of(coreRunning = false, stoppedMeanwhile = false))
    }

    @Test
    fun aStopThatRacedTheReloadReleasesWhatTheReloadStarted() {
        // The teardown found no Xray to stop; the reload then started one, and maybe an Aether core, for a service that is gone.
        assertEquals(ReloadOutcome.RELEASE_CORES, ReloadOutcome.of(coreRunning = true, stoppedMeanwhile = true))
        // A reload that also failed has nothing of its own left, and releasing is harmless then.
        assertEquals(ReloadOutcome.RELEASE_CORES, ReloadOutcome.of(coreRunning = false, stoppedMeanwhile = true))
    }

    @Test
    fun aClientThatAsksDuringAReloadIsToldTheServiceRuns() {
        assertTrue(ReloadOutcome.serviceRuns(coreRunning = true, reloading = false))
        // Xray is stopped for a moment, the service is not; nothing would correct a "not running" answer later.
        assertTrue(ReloadOutcome.serviceRuns(coreRunning = false, reloading = true))
        assertTrue(ReloadOutcome.serviceRuns(coreRunning = true, reloading = true))
        assertFalse(ReloadOutcome.serviceRuns(coreRunning = false, reloading = false))
    }
}
