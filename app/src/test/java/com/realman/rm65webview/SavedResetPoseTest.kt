package com.realman.rm65webview

import org.junit.Assert.*
import org.junit.Test

class SavedResetPoseTest {
    private val origin = listOf(10.0,-40.0,-70.0,-5.0,-50.0,30.0)
    private val saved = SavedResetPose(listOf(20.0,-20.0,-50.0,5.0,-40.0,10.0),1_000L)
    private val low = List(6) { -160.0 }
    private val high = List(6) { 160.0 }
    private fun flow() = SavedResetFlow().also { it.begin(origin,saved,low,high,10,1_000L) }
    @Test fun savedPoseSurvivesSerializationWithoutGripperState() {
        val value=saved.toJson()
        assertEquals(saved,SavedResetPose.decode(value))
        assertFalse(value.contains("gripper"))
        assertTrue(saved.matches(saved.joints))
        assertFalse(saved.matches(origin))
        assertFalse(saved.matches(List(6){Double.NaN}))
        assertFalse(saved.matches(saved.joints.take(5)))
    }
    @Test fun missingCorruptOrUnsupportedSavedPoseDoesNotBecomeZeroPose() {
        for(value in listOf(null,"", "{}", "{\"version\":2,\"joint\":[0,0,0,0,0,0],\"saved_at\":1000}","{\"version\":1,\"joint\":[0,0],\"saved_at\":1000}")) assertNull(SavedResetPose.decode(value))
        assertTrue(runCatching{SavedResetPose(List(6){Double.NaN},1_000)}.isFailure)
        assertTrue(runCatching{SavedResetPose(List(6){0.0},0)}.isFailure)
    }
    @Test fun targetIsSavedPoseAndSpeedMatchesUserSelection() {
        val f=SavedResetFlow()
        assertEquals(30,f.begin(origin,saved,low,high,30,1_000L))
        assertEquals(saved.joints,f.target)
        assertTrue(f.active)
        assertTrue(runCatching{f.begin(origin,saved,low,high,1,1_100L)}.isFailure)
        f.reset()
        assertEquals(1,f.begin(origin,saved,low,high,1,1_100L))
    }
    @Test fun changedLimitsInvalidStateOrInvalidSpeedPreventMotion() {
        val f=SavedResetFlow()
        val tighter=high.toMutableList().also{it[0]=15.0}
        assertTrue(runCatching{f.begin(origin,saved,low,tighter,5,1_000L)}.isFailure)
        assertTrue(runCatching{f.begin(List(6){200.0},saved,low,high,5,1_000L)}.isFailure)
        assertTrue(runCatching{f.begin(origin,saved,low,high,31,1_000L)}.isFailure)
        assertFalse(f.active)
    }
    @Test fun requiresReceiptArmArrivalAndTwoDistinctPostArrivalFrames() {
        val f=flow()
        f.complete(0,true,1);f.acknowledge(true);assertFalse(f.reached(saved.joints,2))
        f.complete(1,true,2);assertFalse(f.reached(saved.joints,3))
        f.complete(0,false,3);assertFalse(f.reached(saved.joints,4))
        f.complete(0,true,5)
        assertFalse(f.reached(saved.joints,5));assertFalse(f.reached(saved.joints,6));assertFalse(f.reached(saved.joints,6))
        assertFalse(f.reached(origin,7));assertFalse(f.reached(saved.joints,8));assertTrue(f.reached(saved.joints,9))
    }
    @Test fun enforcedHeartbeatReceiptAndTotalTimeouts() {
        val f=flow()
        assertNull(f.problem(origin,low,high,1_200L,1_100L))
        assertTrue(f.problem(origin,low,high,2_600L,1_100L)!!.contains("按住"))
        assertTrue(f.problem(origin,low,high,6_000L,6_000L)!!.contains("确认"))
        f.acknowledge(true)
        assertTrue(f.problem(origin,low,high,121_000L,121_000L)!!.contains("到位"))
    }
    @Test fun safetyEnvelopeLimitsAndCancelledMoveFailClosed() {
        val f=flow()
        assertNull(f.problem(origin,low,high,1_100L,1_100L))
        assertNotNull(f.problem(origin.toMutableList().also{it[0]-=1},low,high,1_100L,1_100L))
        assertNotNull(f.problem(origin,low.map{it+1},high,1_100L,1_100L))
        assertNotNull(f.problem(List(6){Double.NaN},low,high,1_100L,1_100L))
        f.reset();assertFalse(f.active);assertTrue(f.target.isEmpty())
        f.complete(0,true,3);assertFalse(f.reached(saved.joints,4))
    }
}
