package com.realman.rm65webview

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AlarmClearFlowTest {
    private fun arm(code: Int = 0x1003, joints: List<Double> = List(6) { 0.0 }) =
        ArmSnapshot(joints, List(6) { 0.0 }, listOf(code))
    private fun safety(code: Int = 0x1003, joint: Int = 0) =
        SafetySnapshot(List(6) { 1 }, listOf(0, joint, 0, 0, 0, 0), listOf(code))
    private fun prepare(flow: AlarmClearFlow, target: Int = 0, joint: Int = 0): AlarmClearFlow.Event? {
        flow.begin(target, 100)
        for (t in listOf(150L, 300L, 500L)) flow.observeArm(arm(), t, t - 1, t)
        flow.observeSafety(safety(joint = joint), 499, 500)
        flow.observePlanning("none", 499, 500)
        return flow.advance(500)
    }

    @Test fun idleAndRepeatedPollingNeverSendClearCommands() {
        val flow = AlarmClearFlow()
        assertNull(flow.advance(10_000))
        assertEquals(AlarmClearFlow.Event.Send(0), prepare(flow))
        assertNull(flow.advance(501))
        assertNull(flow.advance(1_000))
    }
    @Test fun movingOrUnconfirmedPlanningCannotClearFaults() {
        for (type in listOf("movej", "", "pause")) {
            val flow = AlarmClearFlow(); prepare(flow)
            // Start a new attempt with fresh moving feedback.
            flow.cancel(""); flow.begin(0, 600)
            for (t in listOf(650L, 800L, 1_000L)) flow.observeArm(arm(), t, t - 1, t)
            flow.observeSafety(safety(), 999, 1_000)
            flow.observePlanning(type, 999, 1_000)
            assertNull(flow.advance(1_000))
            assertFalse((flow.advance(6_600) as AlarmClearFlow.Event.Finished).success)
        }
    }
    @Test fun driftingArmCannotPassStillnessCheck() {
        val flow = AlarmClearFlow(); flow.begin(0, 100)
        for (t in listOf(150L, 300L, 500L)) {
            flow.observeArm(arm(joints = List(6) { t / 1000.0 }), t, t - 1, t)
        }
        flow.observeSafety(safety(), 499, 500); flow.observePlanning("none", 499, 500)
        assertNull(flow.advance(500))
    }
    @Test fun missingErrorsAndStaleSamplesAreNeverHealthy() {
        val flow = AlarmClearFlow(); flow.begin(0, 100)
        for (t in listOf(150L, 300L, 500L)) flow.observeArm(arm().copy(systemErrors = emptyList()), t, t - 1, t)
        flow.observeSafety(safety(), 499, 500); flow.observePlanning("none", 499, 500)
        assertNull(flow.advance(500))
        flow.observeArm(arm(), 550, 549, 550)
        assertNull(flow.advance(2_100))
    }
    @Test fun noFaultRequiresNoClearCommand() {
        val flow = AlarmClearFlow(); flow.begin(0, 100)
        for (t in listOf(150L, 300L, 500L)) flow.observeArm(arm(0), t, t - 1, t)
        flow.observeSafety(safety(0), 499, 500); flow.observePlanning("none", 499, 500)
        assertTrue((flow.advance(500) as AlarmClearFlow.Event.Finished).success)
        assertFalse(flow.active)
    }
    @Test fun explicitReceiptAndNewReadbackAreBothRequired() {
        val flow = AlarmClearFlow(); prepare(flow)
        assertNull(flow.acknowledge("movej", true, 550))
        assertEquals(AlarmClearFlow.Phase.WAIT_ACK, flow.phase)
        assertNull(flow.acknowledge("clear_system_err", true, 600))
        assertNull(flow.advance(601))
        // Samples requested before the ACK, even if received later, cannot confirm clearing.
        flow.observeArm(arm(0), 650, 599, 650)
        flow.observeSafety(safety(0), 599, 650)
        assertNull(flow.advance(650))
        for (t in listOf(700L, 900L)) flow.observeArm(arm(0), t, t - 1, t)
        flow.observeSafety(safety(0), 699, 700)
        assertNull(flow.advance(900))
        flow.observeSafety(safety(0), 1_099, 1_100)
        assertTrue((flow.advance(1_100) as AlarmClearFlow.Event.Finished).success)
        assertFalse(flow.active)
    }
    @Test fun rejectedOrMalformedClearReceiptIsFailure() {
        for (success in listOf(false, null)) {
            val flow = AlarmClearFlow(); prepare(flow)
            assertFalse(flow.acknowledge("clear_system_err", success, 600)!!.success)
            assertFalse(flow.active)
        }
    }
    @Test fun positiveReceiptWithPersistentFaultStillFails() {
        val flow = AlarmClearFlow(); prepare(flow); flow.acknowledge("clear_system_err", true, 600)
        for (t in listOf(700L, 900L)) flow.observeArm(arm(), t, t - 1, t)
        flow.observeSafety(safety(), 699, 700); flow.observeSafety(safety(), 1_099, 1_100)
        assertFalse((flow.advance(1_100) as AlarmClearFlow.Event.Finished).success)
    }
    @Test fun clearingOneJointDoesNotPretendOtherAlarmsAreGoneOrEnableJoints() {
        val flow = AlarmClearFlow(); assertEquals(AlarmClearFlow.Event.Send(2), prepare(flow, 2, 4))
        assertNull(flow.acknowledge("clear_system_err", true, 550))
        flow.acknowledge("set_joint_clear_err", true, 600)
        for (t in listOf(700L, 900L)) flow.observeArm(arm(), t, t - 1, t)
        flow.observeSafety(safety(joint = 0).copy(enabled = List(6) { 0 }), 699, 700)
        flow.observeSafety(safety(joint = 0).copy(enabled = List(6) { 0 }), 1_099, 1_100)
        assertTrue((flow.advance(1_100) as AlarmClearFlow.Event.Finished).success)
        assertTrue(flow.message.contains("J2"))
        assertNull(flow.advance(1_200))
    }
    @Test fun timeoutAndDisconnectNeverRetryAutomatically() {
        val flow = AlarmClearFlow(); prepare(flow)
        assertFalse((flow.advance(6_500) as AlarmClearFlow.Event.Finished).success)
        assertNull(flow.advance(7_000))
        flow.begin(0, 8_000); flow.cancel("连接中断")
        assertNull(flow.acknowledge("clear_system_err", true, 8_100))
        assertNull(flow.advance(9_000)); assertEquals("连接中断", flow.message)
    }
    @Test fun clearProtocolUsesDocumentedKeysAndValidJointRange() {
        assertEquals("clear_system_err", JSONObject(RobotProtocol.clearSystemError()).getString("command"))
        assertEquals(2, JSONObject(RobotProtocol.clearJointError(2)).getInt("joint_clear_err"))
        for (joint in listOf(0, 7, -1)) assertTrue(runCatching { RobotProtocol.clearJointError(joint) }.isFailure)
        for ((command, key) in listOf("clear_system_err" to "clear_state", "set_joint_clear_err" to "joint_clear_err")) {
            fun parse(fields: String) = RobotResponseParser.parse("""{"command":"$command",$fields}""") as RobotUpdate.Acknowledgement
            assertEquals(true, parse("\"$key\":true").success)
            assertEquals(false, parse("\"$key\":false").success)
            assertNull(parse("\"unrelated\":true").success)
            assertNull(parse("\"$key\":\"true\"").success)
        }
    }
    @Test fun alarmDecoderPreservesUnknownCodesAndSpecialCommunicationCode() {
        assertEquals("不可达／奇异点", RobotAlarms.item(0x1003).getString("label"))
        assertEquals("严重", RobotAlarms.item(0x5008).getString("severity"))
        assertEquals("通信丢帧", RobotAlarms.item(0xF000, 2).getString("label"))
        assertEquals("未知错误", RobotAlarms.item(0x12345).getString("label"))
        assertEquals(0x12345, RobotAlarms.item(0x12345).getInt("code"))
        assertEquals("J2 关节", RobotAlarms.item(4, 2).getString("source"))
    }
}
