package com.realman.rm65webview

import org.junit.Assert.*
import org.junit.Test

class CoupledBendFlowTest {
    private val seed = listOf(10.0, -40.0, -70.0, -5.0, -50.0, 30.0)
    private val lower = List(6) { -160.0 }
    private val upper = List(6) { 160.0 }
    private fun plan(direction: Int = 1, speed: Int = 20) = CoupledBendFlow.plan(seed, lower, upper, direction, speed)
    private fun flow() = CoupledBendFlow().apply { begin(plan(), 1_000L) }
    @Test fun bothDirectionsOnlyChangeJ2J3J5AndRespectSelectedSpeed() {
        for (direction in listOf(-1, 1)) {
            val p = plan(direction)
            assertEquals(20, p.speed)
            assertEquals(10.0, p.travelDeg, 0.0)
            for (axis in 0..5) assertEquals(seed[axis] + if(axis in setOf(1,2,4)) direction * 10.0 else 0.0, p.target[axis], 1e-10)
        }
        assertEquals(1,plan(speed=1).speed)
    }
    @Test fun nearestLimitReducesAllThreeAxesByTheSameAmount() {
        val high=upper.toMutableList().also { it[2]=seed[2]+2.0 }
        val p=CoupledBendFlow.plan(seed,lower,high,1,5)
        assertEquals(1.95,p.travelDeg,0.001)
        assertTrue(p.target[2] <= high[2]-.049)
        for(axis in setOf(1,2,4)) assertEquals(p.travelDeg,p.target[axis]-seed[axis],1e-10)
        val low=lower.toMutableList().also { it[4]=seed[4]-1.0 }
        val negative=CoupledBendFlow.plan(seed,low,upper,-1,5)
        assertEquals(.95,negative.travelDeg,.001)
    }
    @Test fun invalidOrTooTightLimitsAndInvalidParametersRejectWithoutStarting() {
        for (bad in listOf(seed.take(5),List(6){Double.NaN},List(6){200.0})) {
            assertTrue(runCatching { CoupledBendFlow.plan(bad,lower,upper,1,5) }.isFailure)
        }
        assertTrue(runCatching { CoupledBendFlow.plan(seed,lower,upper,0,5) }.isFailure)
        assertTrue(runCatching { CoupledBendFlow.plan(seed,lower,upper,1,31) }.isFailure)
        val high=upper.toMutableList().also { it[1]=seed[1]+.4 }
        assertTrue(runCatching { CoupledBendFlow.plan(seed,lower,high,1,5) }.isFailure)
        assertFalse(CoupledBendFlow().active)
    }
    @Test fun arbitraryTargetAndOverlappingMovesAreRejected() {
        val f=CoupledBendFlow(); val p=plan()
        assertTrue(runCatching{f.begin(p.copy(target=p.target.map{it+1}),1_000L)}.isFailure)
        assertFalse(f.active)
        f.begin(p,1_000L)
        assertTrue(runCatching{f.begin(p,1_100L)}.isFailure)
    }
    @Test fun ackArmCompletionAndTwoDistinctFreshFramesAreAllRequired() {
        val f=flow(); val target=f.plan!!.target
        f.complete(0,true,1); f.acknowledge(true)
        assertFalse(f.reached(target,2))
        f.complete(1,true,2); assertFalse(f.reached(target,3))
        f.complete(0,false,3); assertFalse(f.reached(target,4))
        f.complete(0,true,5)
        assertFalse(f.reached(target,5)); assertFalse(f.reached(target,6)); assertFalse(f.reached(target,6))
        assertTrue(f.reached(target,7))
    }
    @Test fun arrivalMustRemainStableAndResetConsumesTheTarget() {
        val f=flow();val target=f.plan!!.target
        f.acknowledge(true);f.complete(0,true,1)
        assertFalse(f.reached(target,2));assertFalse(f.reached(seed,3))
        assertFalse(f.reached(target,4));assertTrue(f.reached(target,5))
        f.reset();assertNull(f.plan);assertFalse(f.active)
        f.complete(0,true,6);assertFalse(f.reached(target,7))
    }
    @Test fun mandatoryHeartbeatAckAndExecutionTimeoutsFailClosed() {
        val f=flow()
        assertNull(f.problem(seed,lower,upper,1_200L,1_100L))
        assertTrue(f.problem(seed,lower,upper,2_600L,1_100L)!!.contains("按住"))
        assertTrue(f.problem(seed,lower,upper,6_000L,6_000L)!!.contains("确认"))
        f.acknowledge(true)
        assertTrue(f.problem(seed,lower,upper,31_000L,31_000L)!!.contains("到位"))
    }
    @Test fun unchangedAxesSynchronizationAndSafetyLimitsAreMonitored() {
        val f=flow()
        val consistent=seed.mapIndexed { i,v -> v + if(i in setOf(1,2,4)) 2.0 else 0.0 }
        assertNull(f.problem(consistent,lower,upper,1_200L,1_100L))
        assertNotNull(f.problem(seed.toMutableList().also{it[0]+=.3},lower,upper,1_200L,1_100L))
        assertTrue(f.problem(seed.toMutableList().also{it[1]+=1.0},lower,upper,1_200L,1_100L)!!.contains("进度"))
        assertNotNull(f.problem(consistent,lower.map{it+.1},upper,1_200L,1_100L))
        assertNotNull(f.problem(List(6){Double.NaN},lower,upper,1_200L,1_100L))
        assertNotNull(f.problem(seed.map{it+20},lower,upper,1_200L,1_100L))
    }
}
