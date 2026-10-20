package com.realman.rm65webview

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ManualStopGateTest {
    private val joints = List(6) { 10.0 }
    private fun gate(quick: Boolean = false) = ManualStopGate().apply { begin(1_000L, quick) }
    private fun accepted(g: ManualStopGate, now: Long = 1_100L) {
        g.acknowledge("set_stop_teach", true, now)
        g.acknowledge(if (g.quick) "set_arm_stop" else "set_arm_slow_stop", true, now)
    }
    private fun settle(g: ManualStopGate, start: Long = 1_200L) {
        for (i in 0..6) g.observeArm(joints, start + i, start + i * 50)
    }
    @Test fun requiresBothReceiptsIdlePlanningAndStillness() {
        val g = gate()
        g.acknowledge("set_arm_slow_stop", true, 1_100)
        settle(g);g.observePlanning("none",1_110,1_120);assertFalse(g.ready(1_500))
        g.acknowledge("set_stop_teach",true,1_600)
        settle(g,1_700);assertFalse(g.ready(2_000))
        g.observePlanning("movej",1_800,2_000);assertFalse(g.ready(2_000))
        g.observePlanning("none",2_010,2_020);settle(g,2_100);assertTrue(g.ready(2_400))
    }
    @Test fun unrelatedNegativeAndMissingReceiptsNeverUnlock() {
        val g = gate();assertNull(g.acknowledge("movej",true,1_100))
        assertNotNull(g.acknowledge("set_stop_teach",null,1_100))
        assertNotNull(g.acknowledge("set_arm_slow_stop",false,1_100))
        settle(g);g.observePlanning("none",1_200,1_300);assertFalse(g.ready(1_500))
    }
    @Test fun planningMustHaveBeenRequestedAfterBothStopReceipts() {
        val g=gate();accepted(g);settle(g)
        g.observePlanning("none",1_099,1_500);assertFalse(g.ready(1_500))
        g.observePlanning("none",1_200,1_500);assertTrue(g.ready(1_500))
    }
    @Test fun slowDriftDuplicatesInvalidAndStaleFramesNeverCountAsStillness() {
        val g=gate();accepted(g);g.observePlanning("none",1_110,1_120)
        for(i in 0..10)g.observeArm(List(6){10.0+i*.02},i+1L,1_200+i*50L)
        assertFalse(g.ready(1_700))
        val d=gate();accepted(d);d.observePlanning("none",1_110,1_120)
        repeat(10){d.observeArm(joints,1L,1_200+it*50L)};assertFalse(d.ready(1_650))
        val v=gate();accepted(v);v.observePlanning("none",1_110,1_120);settle(v)
        assertTrue(v.ready(1_500));assertFalse(v.ready(1_800))
        v.observeArm(List(6){Double.NaN},8,1_800);assertFalse(v.ready(1_800))
    }
    @Test fun timeoutPreservesLockAndRetriesOnlyStopsWithoutAnyDisconnectEvent() {
        val g=gate();assertNull(g.timeout(1_999));assertNotNull(g.timeout(2_000))
        g.markUnconfirmed();assertTrue(g.pending);assertTrue(g.unconfirmed);assertTrue(g.quick)
        val commands=g.commandsDue(2_000).map{JSONObject(it).getString("command")}
        assertEquals(listOf("set_stop_teach","set_arm_stop"),commands)
        assertTrue(g.commandsDue(2_050).isEmpty());assertNull(g.timeout(99_000))
        assertFalse(g.ready(99_000))
        assertTrue(g.commandsDue(99_000).isNotEmpty());assertTrue(g.pending)
    }
    @Test fun aLateConfirmedStopRecoversOnTheSameAttempt() {
        val g=gate();g.markUnconfirmed();accepted(g,3_100)
        g.observePlanning("none",3_110,3_120);settle(g,3_200)
        assertTrue(g.ready(3_500));assertTrue(g.pending)
        g.reset();assertFalse(g.pending);assertFalse(g.unconfirmed);assertTrue(g.commandsDue(9_000).isEmpty())
    }
    @Test fun explicitStopUpgradesToFastStopEvenWhileAlreadyStopping() {
        val g=gate();accepted(g);settle(g);g.begin(1_600,quick=true)
        assertTrue(g.quick);assertFalse(g.ready(1_600))
        g.acknowledge("set_arm_slow_stop",true,1_700);assertFalse(g.ready(1_700))
        g.acknowledge("set_arm_stop",true,1_800);g.observePlanning("none",1_810,1_820)
        settle(g,1_900);assertTrue(g.ready(2_200))
    }
    @Test fun repeatedReceiptsDoNotResetStillnessButRepeatedStopDoesNotExtendDeadline() {
        val g=gate();accepted(g);g.observePlanning("none",1_110,1_120)
        settle(g);accepted(g,1_400);assertTrue(g.ready(1_500))
        val waiting=gate();waiting.begin(1_900);assertNotNull(waiting.timeout(2_000))
    }
    @Test fun everyStopParserUsesOnlyItsDocumentedBoolean() {
        for((command,key) in listOf("set_stop_teach" to "stop_teach","set_arm_stop" to "arm_stop","set_arm_slow_stop" to "arm_slow_stop")) {
            fun parse(fields:String)=(RobotResponseParser.parse("""{"command":"$command",$fields}""") as RobotUpdate.Acknowledgement).success
            assertEquals(true,parse("\"$key\":true"));assertEquals(false,parse("\"$key\":false,\"other\":true"))
            assertNull(parse("\"state\":true"));assertNull(parse("\"$key\":\"true\""))
        }
    }
}
