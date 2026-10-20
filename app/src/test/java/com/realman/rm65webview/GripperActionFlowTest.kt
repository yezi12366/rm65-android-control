package com.realman.rm65webview

import org.junit.Assert.*
import org.junit.Test

class GripperActionFlowTest {
    private fun state(mode: Int, position: Int = 100) = GripperSnapshot(1, 1, 0, position, 200, mode)
    private fun closing() = GripperActionFlow().also {
        it.begin(GripperNode(GripperOperation.CLOSE), 0)
        it.acknowledge("set_gripper", true, 10)
    }

    @Test fun closeLongerThanEightHundredMillisecondsDoesNotAdvanceToOpen() {
        val flow = closing()
        val action = ActionRecord("a", "夹取", RecordingType.REMOTE, 5,
            listOf(GripperNode(GripperOperation.CLOSE), GripperNode(GripperOperation.OPEN)), 1, 1)
        val cursor = PlaybackCursor(action)
        for (now in listOf(100L, 800L, 1_500L, 2_000L)) {
            assertNull(flow.observe(state(4), now))
            assertTrue((cursor.current as GripperNode).operation == GripperOperation.CLOSE)
        }
        assertNull(flow.observe(state(6), 3_000))
        assertTrue(flow.observe(state(6), 3_200) is GripperActionResult.Completed)
        cursor.advance()
        assertEquals(GripperOperation.OPEN, (cursor.current as GripperNode).operation)
    }

    @Test fun twoClicksAndARepeatedConfirmedCloseProduceOneRecordedNode() {
        val session = ActionSession()
        session.begin("a", "录制", RecordingType.REMOTE, 5, 0)
        val flow = closing()
        assertFalse(flow.begin(GripperNode(GripperOperation.CLOSE), 20))
        assertFalse(flow.begin(GripperNode(GripperOperation.OPEN), 30))
        assertEquals(0, session.draft!!.nodes.size)
        flow.observe(state(6), 1_000)
        val result = flow.observe(state(6), 1_200) as GripperActionResult.Completed
        assertTrue(session.addGripper(result.node.operation))
        assertFalse(session.addGripper(result.node.operation))
        assertEquals(1, session.draft!!.nodes.size)
        session.addWait(1_000)
        assertTrue(session.addGripper(result.node.operation))
        assertEquals(3, session.draft!!.nodes.size)
    }

    @Test fun receiptWithoutTerminalFeedbackIsNotCompletion() {
        val flow = closing()
        assertNull(flow.observe(state(1), 100))
        assertNull(flow.observe(state(4), 800))
        assertTrue(flow.busy)
        assertTrue(flow.tick(800 + GripperActionFlow.FEEDBACK_TIMEOUT_MS) is GripperActionResult.Failed)
    }

    @Test fun failedAttemptIsNotRecordedAndRetryCanCompleteOnce() {
        val flow = GripperActionFlow()
        val node = GripperNode(GripperOperation.CLOSE)
        flow.begin(node, 0)
        assertTrue(flow.acknowledge("set_gripper", false, 1) is GripperActionResult.Failed)
        assertNull(flow.observe(state(6), 2))
        assertTrue(flow.begin(node, 3))
        flow.acknowledge("set_gripper", true, 4)
        flow.observe(state(6), 100)
        assertTrue(flow.observe(state(6), 300) is GripperActionResult.Completed)
    }

    @Test fun staleAndUnacknowledgedTerminalStatesCannotComplete() {
        val flow = GripperActionFlow()
        flow.begin(GripperNode(GripperOperation.OPEN), 0)
        assertNull(flow.observe(state(1), 100))
        assertNull(flow.observe(state(1), 300))
        assertTrue(flow.tick(GripperActionFlow.ACK_TIMEOUT_MS) is GripperActionResult.Failed)
        assertNull(flow.acknowledge("set_gripper", true, 5_001))
        assertNull(flow.observe(state(1), 5_100))
    }

    @Test fun wrongPositionOrContinuedMovementCannotCompletePositionNode() {
        val flow = GripperActionFlow()
        flow.begin(GripperNode(GripperOperation.POSITION, 500), 0)
        flow.acknowledge("set_gripper", true, 1)
        assertNull(flow.observe(state(3, 400), 100))
        assertNull(flow.observe(state(4, 500), 300))
        assertNull(flow.observe(state(3, 500), 500))
        assertTrue(flow.observe(state(3, 500), 700) is GripperActionResult.Completed)
    }

    @Test fun faultsAndGripperCompletionFailuresStopOnlyThePendingGripper() {
        assertTrue(closing().observe(state(4).copy(error = 4), 100) is GripperActionResult.Failed)
        val flow = closing()
        assertNull(flow.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.MOVE, false, 0)))
        assertTrue(flow.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.GRIPPER, false, 1)) is GripperActionResult.Failed)
    }

    @Test fun operationTimeoutAppliesEvenWhenClosingFeedbackKeepsArriving() {
        val flow = closing()
        flow.observe(state(4), GripperActionFlow.OPERATION_TIMEOUT_MS - 1)
        assertTrue(flow.tick(GripperActionFlow.OPERATION_TIMEOUT_MS) is GripperActionResult.Failed)
    }

    @Test fun explicitInitializationAcceptsColdFeedbackThenWaitsForConfirmedOpen() {
        val flow = GripperActionFlow()
        val open = GripperNode(GripperOperation.OPEN)
        assertTrue(flow.begin(open, 0, initialize = true))
        assertFalse(flow.begin(open, 1, initialize = true))
        assertNull(flow.observe(state(1), 10)) // An earlier healthy query can race the command receipt.
        assertNull(flow.observe(state(0).copy(enabled = 0, online = 0), 20))
        assertNull(flow.acknowledge("set_gripper", true, 100))
        assertNull(flow.observe(state(0).copy(enabled = 0, online = 0), 2_500))
        assertNull(flow.tick(2_600))
        assertNull(flow.observe(state(5), 3_000))
        assertNull(flow.observe(state(1), 4_000))
        assertTrue(flow.observe(state(1), 4_200) is GripperActionResult.Completed)
        assertFalse(flow.busy)
    }

    @Test fun initializationNeverHidesFaultsOrKeepsWaitingIndefinitely() {
        val flow = GripperActionFlow()
        flow.begin(GripperNode(GripperOperation.OPEN), 0, initialize = true)
        flow.acknowledge("set_gripper", true, 10)
        assertTrue(flow.observe(state(0).copy(enabled = 0, online = 0, error = 4), 100) is GripperActionResult.Failed)
        flow.begin(GripperNode(GripperOperation.OPEN), 200, initialize = true)
        flow.acknowledge("set_gripper", true, 210)
        assertTrue(flow.tick(5_200) is GripperActionResult.Failed)
    }

    @Test fun normalPlaybackDoesNotGetTheInitializationGracePeriod() {
        val flow = closing()
        assertTrue(flow.observe(state(0).copy(online = 0), 20) is GripperActionResult.Failed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun initializationCannotBeUsedToBypassTheGateForClosing() {
        GripperActionFlow().begin(GripperNode(GripperOperation.CLOSE), 0, initialize = true)
    }
}
