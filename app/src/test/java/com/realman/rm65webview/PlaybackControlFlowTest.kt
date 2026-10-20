package com.realman.rm65webview
import org.junit.Assert.*
import org.junit.Test

class PlaybackControlFlowTest {
    @Test fun onlyMatchingReceiptConfirmsPauseAndResume() {
        val flow = PlaybackControlFlow()
        assertEquals("set_arm_pause", flow.begin(true, false, 0))
        assertNull(flow.acknowledge("set_gripper", true))
        assertFalse(flow.nativePaused)
        assertEquals("set_arm_pause", flow.pending)
        assertNull(flow.acknowledge("set_arm_pause", true))
        assertTrue(flow.nativePaused)
        assertEquals("set_arm_continue", flow.begin(false, false, 100))
        assertTrue(flow.nativePaused)
        assertNull(flow.acknowledge("set_arm_continue", true))
        assertFalse(flow.nativePaused)
    }

    @Test fun pauseFailureAndTimeoutAreNotReportedAsSuccessfulPause() {
        val flow = PlaybackControlFlow()
        flow.begin(true, true, 0)
        assertNotNull(flow.acknowledge("pause_drag_trajectory", false))
        assertFalse(flow.nativePaused)
        flow.begin(true, true, 10)
        assertNotNull(flow.timeout(10 + PlaybackControlFlow.TIMEOUT_MS))
    }

    @Test(expected = IllegalStateException::class)
    fun resumeCannotOvertakeAnUnconfirmedPause() {
        val flow = PlaybackControlFlow()
        flow.begin(true, false, 0)
        flow.begin(false, false, 1)
    }
}
