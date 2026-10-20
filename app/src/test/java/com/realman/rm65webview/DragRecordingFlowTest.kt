package com.realman.rm65webview

import org.junit.Assert.*
import org.junit.Test

class DragRecordingFlowTest {
    private fun ack(flow: DragRecordingFlow, json: String, now: Long): DragRecordingEvent? {
        val update = RobotResponseParser.parse(json) as RobotUpdate.Acknowledgement
        return flow.acknowledge(update.command, update.success, now)
    }

    private fun recording(): DragRecordingFlow = DragRecordingFlow().also {
        it.start("A1S1", 0)
        ack(it, """{"command":"start_drag_teach","drag_teach":true}""", 1)
    }

    private fun verifying(finishAction: Boolean = false): DragRecordingFlow = recording().also {
        it.finish(finishAction, 2)
        ack(it, """{"command":"stop_drag_teach","drag_teach":true}""", 3)
        ack(it, """{"command":"save_trajectory_file","save_state":true}""", 4)
    }

    @Test
    fun endAndSaveWaitsForStopThenSaveThenARealFile() {
        val flow = recording()
        val session = ActionSession()
        session.begin("A1", "拖动测试", RecordingType.DRAG, 5, 0)
        assertEquals(DragRecordingEvent.Send("stop_drag_teach"), flow.finish(true, 2))
        assertEquals(DragRecordingEvent.Send("save_trajectory_file", "A1S1"),
            ack(flow, """{"command":"stop_drag_teach","drag_teach":true}""", 3))
        assertEquals(DragRecordingEvent.Send("get_trajectory_file_list"),
            ack(flow, """{"command":"save_trajectory_file","save_state":true}""", 4))
        assertNull(flow.receiveList(emptyList()))
        assertEquals(0, session.draft!!.nodes.size)
        val update = RobotResponseParser.parse(
            """{"command":"get_trajectory_file_list","list":[{"name":"A1S1","point_num":501,"create_time":123}],"total_size":1}"""
        ) as RobotUpdate.TrajectoryList
        val saved = flow.receiveList(update.value) as DragRecordingEvent.Saved
        assertTrue(saved.finishAction)
        assertEquals(501, saved.file.pointCount)
        session.addDragTrajectory(DragTrajectoryNode(saved.file.name, saved.file.pointCount,
            List(6) { 0.0 }, List(6) { 1.0 }, List(6) { 0.0 }, List(6) { 1.0 }, true))
        assertEquals(1, session.finish(10).nodes.size)
        assertEquals(DragRecordingPhase.IDLE, flow.phase)
    }

    @Test
    fun waitsForStartAcknowledgementAndRejectsFailure() {
        val flow = DragRecordingFlow()
        flow.start("A1S1", 0)
        assertEquals(DragRecordingPhase.STARTING, flow.phase)
        assertTrue(ack(flow, """{"command":"start_drag_teach","drag_teach":false}""", 1)
            is DragRecordingEvent.Failed)
        assertFalse(flow.segmentActive)
        assertTrue(flow.blocksNodes)
    }

    @Test
    fun failedStopCannotSendSaveAndFailedSaveCannotAddFile() {
        val stop = recording()
        stop.finish(false, 2)
        assertTrue(ack(stop, """{"command":"stop_drag_teach","drag_teach":false}""", 3)
            is DragRecordingEvent.Failed)
        assertNull(ack(stop, """{"command":"stop_drag_teach","drag_teach":true}""", 4))

        val save = recording()
        save.finish(false, 2)
        ack(save, """{"command":"stop_drag_teach","drag_teach":true}""", 3)
        assertTrue(ack(save, """{"command":"save_trajectory_file","save_state":false}""", 4)
            is DragRecordingEvent.Failed)
        assertNull(save.receiveList(listOf(TrajectoryFileInfo("A1S1", 100, 5))))
    }

    @Test
    fun retriesDelayedListButNeverAcceptsAnUnrelatedOrMissingFile() {
        val flow = verifying()
        assertNull(flow.receiveList(listOf(TrajectoryFileInfo("Other", 501, 123))))
        assertEquals(DragRecordingEvent.Send("get_trajectory_file_list"), flow.tick(504))
        assertNull(flow.receiveList(emptyList()))
        assertTrue(flow.tick(4 + DragRecordingFlow.VERIFY_TIMEOUT_MS) is DragRecordingEvent.Failed)
        assertFalse(flow.saving)
        assertTrue(flow.error.contains("找不到"))
        assertNull(flow.receiveList(listOf(TrajectoryFileInfo("A1S1", 100, 5))))
    }

    @Test
    fun acceptsDelayedFileAndRejectsZeroPoints() {
        val delayed = verifying()
        delayed.receiveList(emptyList())
        delayed.tick(504)
        assertTrue(delayed.receiveList(listOf(TrajectoryFileInfo("A1S1", 100, 5))) is DragRecordingEvent.Saved)
        assertTrue(verifying().receiveList(listOf(TrajectoryFileInfo("A1S1", 0, 5))) is DragRecordingEvent.Failed)
    }

    @Test
    fun duplicateClicksAndUnrelatedRepliesDoNotAdvanceTheFlow() {
        val flow = recording()
        flow.finish(false, 2)
        assertNull(flow.finish(true, 3))
        assertNull(flow.acknowledge("set_gripper_release", true, 4))
        flow.acknowledge("stop_drag_teach", true, 5)
        assertNull(flow.acknowledge("stop_drag_teach", true, 6))
        flow.acknowledge("save_trajectory_file", true, 7)
        val saved = flow.receiveList(listOf(TrajectoryFileInfo("A1S1", 100, 5))) as DragRecordingEvent.Saved
        assertTrue(saved.finishAction)
    }

    @Test
    fun everyControllerCommandTimesOutAndCancellationIgnoresLateReplies() {
        val start = DragRecordingFlow()
        start.start("A1S1", 0)
        assertTrue(start.tick(DragRecordingFlow.COMMAND_TIMEOUT_MS) is DragRecordingEvent.Failed)
        val stop = recording()
        stop.finish(false, 2)
        assertTrue(stop.tick(2 + DragRecordingFlow.COMMAND_TIMEOUT_MS) is DragRecordingEvent.Failed)
        val save = recording()
        save.finish(false, 2)
        save.acknowledge("stop_drag_teach", true, 3)
        assertTrue(save.tick(3 + DragRecordingFlow.SAVE_TIMEOUT_MS) is DragRecordingEvent.Failed)
        val cancelled = verifying(true)
        cancelled.reset()
        assertNull(cancelled.acknowledge("save_trajectory_file", true, 5))
        assertNull(cancelled.receiveList(listOf(TrajectoryFileInfo("A1S1", 100, 5))))
    }

    @Test
    fun listReadFailureEndsVerificationWithAnExplicitError() {
        val flow = verifying()
        assertTrue(flow.listFailed("列表接口不支持") is DragRecordingEvent.Failed)
        assertFalse(flow.saving)
        assertEquals("列表接口不支持", flow.error)
    }
}
