package com.realman.rm65webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ActionModelsTest {
    @Test
    fun legacyFalseSuccessCannotRunWithoutARealControllerFile() {
        val node = DragTrajectoryNode("Missing", 0, List(6) { 0.0 }, List(6) { 1.0 },
            List(6) { 0.0 }, List(6) { 1.0 }, true)
        assertTrue(ActionSafety.controllerTrajectoryProblem(listOf(node), emptyList())!!.contains("不存在"))
        assertTrue(ActionSafety.controllerTrajectoryProblem(listOf(node),
            listOf(TrajectoryFileInfo("Missing", 0, 1)))!!.contains("没有有效轨迹点"))
        assertNull(ActionSafety.controllerTrajectoryProblem(listOf(node),
            listOf(TrajectoryFileInfo("Missing", 100, 1))))
    }

    @Test
    fun actionRecordRoundTripsAllNodeTypes() {
        val action = ActionRecord(
            id = "action-1",
            displayName = "抓取培养皿",
            recordingType = RecordingType.REMOTE,
            defaultSpeed = 8,
            createdAt = 100,
            updatedAt = 200,
            nodes = listOf(
                WaypointNode(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), "A1P1", 8, true),
                GripperNode(GripperOperation.CLOSE),
                WaitNode(1_000),
                DragTrajectoryNode(
                    "A1S1", 120,
                    List(6) { 0.0 }, List(6) { 1.0 },
                    List(6) { -1.0 }, List(6) { 2.0 }, true
                )
            )
        )

        assertEquals(action, ActionRecord.fromJson(action.toJson()))
    }

    @Test
    fun remoteDraftDropsWaypointInsideTolerance() {
        val draft = ActionDraft("a", "测试", RecordingType.REMOTE, 5, 100)
        assertTrue(draft.appendWaypoint(List(6) { 1.0 }, "A1P1", 5))
        assertFalse(draft.appendWaypoint(listOf(1.01, 1.0, 1.0, 1.0, 1.0, 1.0), "A1P2", 5))
        assertTrue(draft.appendWaypoint(listOf(1.06, 1.0, 1.0, 1.0, 1.0, 1.0), "A1P3", 5))
        assertEquals(2, draft.nodes.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun waitNodeRejectsDurationBelowOneHundredMilliseconds() {
        WaitNode(99)
    }

    @Test
    fun safetyEnvelopeIncludesWaypointAndDragEnvelope() {
        val nodes = listOf<ActionNode>(
            WaypointNode(listOf(0.0, 2.0, 3.0, 4.0, 5.0, 6.0), "P1", 5, true),
            DragTrajectoryNode(
                "T1", 30, List(6) { 0.0 }, List(6) { 1.0 },
                listOf(-4.0, -3.0, -2.0, -1.0, 0.0, 1.0),
                listOf(4.0, 5.0, 6.0, 7.0, 8.0, 9.0), true
            )
        )

        val envelope = ActionSafety.envelope(nodes)

        assertEquals(listOf(-4.0, -3.0, -2.0, -1.0, 0.0, 1.0), envelope.minimum)
        assertEquals(listOf(4.0, 5.0, 6.0, 7.0, 8.0, 9.0), envelope.maximum)
    }
}
