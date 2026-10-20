package com.realman.rm65webview

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GripperConnectionFlowTest {
    private val healthy = GripperSnapshot(1, 1, 0, 500, 100, 3)
    private fun connected() = GripperConnectionFlow().also { it.connect(0) }
    private fun ready() = connected().also { it.observeVoltage(3, 0); it.observe(healthy, 0) }
    private fun commands(lines: List<String>) = lines.map { JSONObject(it).getString("command") }

    @Test fun anAlreadyPoweredAttachedGripperNeedsNoPowerRewriteOrJawMovement() {
        val flow = connected()
        assertEquals(listOf("get_tool_voltage"), commands(flow.poll(0, true)))
        flow.observeVoltage(3, 10)
        flow.observe(healthy, 20)
        assertTrue(flow.health(100).ready)
        assertFalse(flow.canInitialize(100))
        assertTrue(flow.poll(100, true).isEmpty())
        assertEquals(listOf("get_tool_voltage"), commands(flow.poll(5_010, true)))
    }

    @Test fun powerOnIsDeferredDuringMotionAndVerifiedByReadbackNotReceipt() {
        val flow = connected()
        flow.observeVoltage(0, 10)
        assertTrue(flow.poll(20, false).isEmpty())
        assertFalse(flow.canInitialize(20))
        val power = flow.poll(30, true).single()
        assertEquals("set_tool_voltage", JSONObject(power).getString("command"))
        assertEquals(3, JSONObject(power).getInt("voltage_type"))
        flow.powerAcknowledgement(true, 40)
        assertFalse(flow.powerReady(40))
        assertTrue(flow.poll(339, true).isEmpty())
        assertEquals(listOf("get_tool_voltage"), commands(flow.poll(340, true)))
        flow.observeVoltage(3, 350)
        assertTrue(flow.powerReady(350))
    }

    @Test fun aLostPowerReceiptCanRecoverFromActualVoltageWithoutReplayingTheWrite() {
        val flow = connected()
        flow.observeVoltage(0, 0)
        flow.poll(1, true)
        assertEquals(listOf("get_tool_voltage"), commands(flow.poll(501, true)))
        flow.observeVoltage(3, 600)
        flow.observe(healthy, 600)
        assertTrue(flow.health(600).ready)
    }

    @Test fun aRejectedPowerWriteIsNotRetriedIndefinitelyOrTurnedOff() {
        val flow = connected()
        flow.observeVoltage(0, 0)
        flow.poll(1, true)
        flow.powerAcknowledgement(false, 2)
        flow.observeVoltage(0, 400)
        for (now in listOf(500L, 1_500L, 3_000L, 6_000L)) {
            assertFalse(commands(flow.poll(now, true)).contains("set_tool_voltage"))
        }
        assertFalse(flow.powerReady(6_000))
        assertEquals("power", flow.health(6_000).code)
    }

    @Test fun missingVoltageRepliesHaveBoundedQueriesAndRecoverWithoutReconnect() {
        val flow = connected()
        assertEquals(1, flow.poll(0, true).size)
        for (now in listOf(25L, 250L, 999L)) assertTrue(flow.poll(now, true).isEmpty())
        assertEquals(1, flow.poll(1_000, true).size)
        assertEquals(1, flow.poll(2_000, true).size)
        assertTrue(flow.poll(3_000, true).isEmpty())
        assertTrue(flow.poll(7_999, true).isEmpty())
        assertEquals(1, flow.poll(8_000, true).size)
        flow.observeVoltage(3, 8_010)
        flow.observe(healthy, 8_020)
        assertTrue(flow.health(8_020).ready)
    }

    @Test fun coldBootFeedbackAllowsExplicitInitializationInsteadOfDeadlocking() {
        val flow = connected()
        flow.observeVoltage(3, 10)
        assertEquals("waiting", flow.health(20).code)
        assertTrue(flow.canInitialize(20))
        flow.observe(healthy.copy(online = 0, enabled = 0, mode = 0), 30)
        assertEquals("offline", flow.health(40).code)
        assertTrue(flow.canInitialize(40))
        flow.observe(healthy, 500)
        assertTrue(flow.health(500).ready)
        assertFalse(flow.canInitialize(500))
    }

    @Test fun disabledAndInvalidModeAreNotMistakenForAHealthyGripper() {
        val flow = ready()
        flow.observe(healthy.copy(enabled = 0), 10)
        assertEquals("disabled", flow.health(10).code)
        assertTrue(flow.canInitialize(10))
        flow.observe(healthy.copy(mode = 0), 20)
        assertEquals("uninitialized", flow.health(20).code)
    }

    @Test fun missingRepliesExpireButDoNotEraseTheLastReadingAndNextReplyRecovers() {
        val flow = ready()
        assertTrue(flow.health(3_000).ready)
        assertEquals("stale", flow.health(3_001).code)
        assertEquals(500, flow.snapshot!!.position)
        flow.observe(healthy.copy(position = 600), 3_500)
        assertTrue(flow.health(3_500).ready)
        assertEquals(0L, flow.age(3_500))
    }

    @Test fun malformedFeedbackIsVisibleAndCannotUseTheCachedOnlineFlagToAuthorizeCommands() {
        val flow = ready()
        flow.invalidFeedback("缺少 error")
        assertEquals("invalid_reply", flow.health(100).code)
        assertFalse(flow.health(100).ready)
        assertEquals(healthy, flow.snapshot)
        flow.observe(healthy, 200)
        assertTrue(flow.health(200).ready)
    }

    @Test fun actualFaultsCannotBeClearedByRefreshOrInitialization() {
        val flow = ready()
        flow.observe(healthy.copy(error = 20), 10)
        assertEquals("fault", flow.health(10).code)
        assertTrue(flow.health(10).detail.contains("过流、内部通信"))
        assertFalse(flow.canInitialize(10))
        flow.refresh(20)
        flow.observeVoltage(3, 30)
        assertFalse(flow.canInitialize(30))
        assertEquals("fault", flow.health(30).code)
        flow.observe(healthy, 40)
        assertTrue(flow.health(40).ready)
    }

    @Test fun initializationCannotInterruptAReportedJawMovement() {
        val flow = ready()
        flow.observe(healthy.copy(enabled = 0, mode = 4), 10)
        assertFalse(flow.canInitialize(10))
    }

    @Test fun expiredPowerReadbackIsNotPermissionToMoveOrInitialize() {
        val flow = ready()
        flow.observe(healthy, 12_001)
        assertFalse(flow.health(12_001).ready)
        assertFalse(flow.canInitialize(12_001))
        flow.observeVoltage(3, 12_002)
        assertTrue(flow.health(12_002).ready)
    }

    @Test fun reconnectAndDisconnectCannotReuseOldSessionReadiness() {
        val flow = ready()
        flow.connect(100)
        assertNull(flow.snapshot)
        assertFalse(flow.health(100).ready)
        flow.reset()
        flow.observeVoltage(3, 200)
        flow.observe(healthy, 200)
        assertEquals("disconnected", flow.health(200).code)
        assertTrue(flow.poll(300, true).isEmpty())
        assertFalse(flow.canInitialize(300))
    }
}
