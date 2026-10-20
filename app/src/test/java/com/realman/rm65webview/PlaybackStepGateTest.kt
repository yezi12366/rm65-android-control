package com.realman.rm65webview

import org.junit.Assert.*
import org.junit.Test

class PlaybackStepGateTest {
    private val target = List(6) { 1.0 }
    private fun moving() = PlaybackStepGate().also {
        it.begin("movej", MotionCompletionKind.MOVE, target, 0)
        it.acknowledge("movej", true, 1)
    }

    @Test fun gripperAndOtherDeviceEventsCannotAdvanceOrAbortArmStep() {
        val gate = moving()
        assertNull(gate.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.GRIPPER, false, 1)))
        assertNull(gate.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.MOVE, true, 3)))
        gate.observeArm(target, 100); gate.observeArm(target, 300)
        assertFalse(gate.ready(300))
    }

    @Test fun lateArmCompletionCannotSkipAStillMovingNewTarget() {
        val gate = moving()
        gate.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.MOVE, true, 0))
        gate.observeArm(List(6) { 0.0 }, 100); gate.observeArm(List(6) { 0.0 }, 300)
        assertFalse(gate.ready(300))
        gate.observeArm(target, 400); gate.observeArm(target, 600)
        gate.observePlanning("none", 550)
        assertTrue(gate.ready(600))
    }

    @Test fun lostCompletionReplyCanBeRecoveredFromTargetAndIdlePlanning() {
        val gate = moving()
        gate.observeArm(target, 100); gate.observeArm(target, 300)
        gate.observePlanning("none", 250)
        assertTrue(gate.ready(300))
        assertFalse(gate.ready(1_400))
    }

    @Test fun crossingTargetWhilePlanningContinuesIsNotArrival() {
        val gate = moving()
        gate.observeArm(target, 100); gate.observeArm(target, 300)
        gate.observePlanning("movej", 250)
        assertFalse(gate.ready(300))
    }

    @Test fun evenALateCompletionCannotOverrideActivePlanningAtTarget() {
        val gate = moving()
        gate.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.MOVE, true, 0))
        gate.observeArm(target, 100); gate.observeArm(target, 300)
        gate.observePlanning("movej", 250)
        assertFalse(gate.ready(300))
    }

    @Test fun pauseLatchesCompletionWithoutExecutingNextStep() {
        val gate = moving()
        gate.pause(50)
        gate.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.MOVE, true, 0))
        gate.observeArm(target, 100); gate.observeArm(target, 300)
        gate.observePlanning("none", 350)
        assertFalse(gate.ready(300))
        gate.resume(400)
        assertTrue(gate.ready(400))
    }

    @Test fun rejectedAndUnacknowledgedMovesCannotAdvanceEvenAtTarget() {
        val gate = PlaybackStepGate()
        gate.begin("movej", MotionCompletionKind.MOVE, target, 0)
        gate.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.MOVE, true, 0))
        gate.observeArm(target, 100); gate.observeArm(target, 300)
        assertFalse(gate.ready(300))
        assertNotNull(gate.timeout(PlaybackStepGate.ACK_TIMEOUT_MS))
        assertNotNull(gate.acknowledge("movej", false, 500))
    }

    @Test fun unrelatedFailuresDoNotAbortCurrentMove() {
        val gate = moving()
        assertNull(gate.acknowledge("get_trajectory_file_list", false, 200))
        assertNull(gate.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.PROGRAM, false, 0)))
        assertNotNull(gate.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.MOVE, false, 0)))
    }

    @Test fun nativeFileCompletionIsNotInferredFromIdlePlanning() {
        val gate = PlaybackStepGate()
        gate.begin("set_run_trajectory_file", MotionCompletionKind.TRAJECTORY_FILE, emptyList(), 0)
        gate.acknowledge("set_run_trajectory_file", true, 1)
        gate.observePlanning("none", 100)
        assertFalse(gate.ready(100))
        gate.completion(RobotUpdate.MotionCompletion(MotionCompletionKind.TRAJECTORY_FILE, true, 0))
        assertTrue(gate.ready(100))
    }

    @Test fun pauseTimeDoesNotConsumeCommandTimeout() {
        val gate = PlaybackStepGate()
        gate.begin("movej", MotionCompletionKind.MOVE, target, 0)
        gate.pause(10)
        assertNull(gate.timeout(10_000))
        gate.resume(10_010)
        assertNull(gate.timeout(10_100))
        assertNotNull(gate.timeout(15_000))
    }
}
