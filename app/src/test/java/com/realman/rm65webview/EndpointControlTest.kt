package com.realman.rm65webview

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EndpointControlTest {
    private val origin = listOf(0.3, -0.1, 0.4, 0.0, 0.0, 0.0)

    @Test fun convertsMillimetresToProtocolMicrometres() {
        val step = JSONObject(RobotProtocol.positionStep("z", -5.0, 5))
        assertEquals("set_pos_step", step.getString("command"))
        assertEquals("z_step", step.getString("step_type"))
        assertEquals(-5000, step.getInt("step"))
        assertEquals(5, step.getInt("v"))
        assertEquals(1000, JSONObject(RobotProtocol.positionStep("x", 1.0, 1)).getInt("step"))
        assertEquals(10000, JSONObject(RobotProtocol.positionStep("y", 10.0, 30)).getInt("step"))
    }

    @Test fun rejectsInvalidStepAndFrameInputs() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 20.0).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { RobotProtocol.positionStep("x", value, 5) }
        }
        assertThrows(IllegalArgumentException::class.java) { RobotProtocol.positionStep("rx", 1.0, 5) }
        assertThrows(IllegalArgumentException::class.java) { RobotProtocol.positionStep("x", 1.0, 0) }
        assertThrows(IllegalArgumentException::class.java) { RobotProtocol.setTeachFrame(2) }
    }

    @Test fun readsBothTeachFrameResponseEnvelopes() {
        assertEquals(1, (RobotResponseParser.parse("""{"command":"get_teach_frame","frame_type":1}""") as RobotUpdate.TeachFrame).type)
        assertEquals(0, (RobotResponseParser.parse("""{"state":"teach_frame","frame_type":0}""") as RobotUpdate.TeachFrame).type)
        assertEquals(1, JSONObject(RobotProtocol.setTeachFrame(1)).getInt("frame_type"))
    }

    @Test fun doesNotAcceptAnOldCompletionBeforeStepAcknowledgement() {
        val gate = EndpointStepGate()
        gate.begin(origin, 5.0, 10, 0)
        gate.complete(0, true, 11)
        gate.acknowledge(true)
        assertFalse(gate.reached(origin.toMutableList().apply { this[0] += 0.005 }, 12))
        gate.complete(0, true, 12)
        assertTrue(gate.reached(origin.toMutableList().apply { this[0] += 0.005 }, 13))
    }

    @Test fun requiresFreshMeasuredPositionAfterArmCompletion() {
        val gate = EndpointStepGate()
        gate.begin(origin, 1.0, 10, 0)
        gate.acknowledge(true)
        gate.complete(0, true, 12)
        val reached = origin.toMutableList().apply { this[2] += 0.001 }
        assertFalse(gate.reached(reached, 12))
        assertFalse(gate.reached(origin, 13))
        assertTrue(gate.reached(reached, 13))
    }

    @Test fun ignoresPeripheralCompletionAndUnexpectedDistance() {
        val gate = EndpointStepGate()
        gate.begin(origin, 10.0, 10, 0)
        gate.acknowledge(true)
        gate.complete(1, true, 11)
        val reached = origin.toMutableList().apply { this[1] -= 0.010 }
        assertFalse(gate.reached(reached, 12))
        gate.complete(0, true, 12)
        assertFalse(gate.reached(origin.toMutableList().apply { this[1] -= 0.020 }, 13))
        assertTrue(gate.reached(reached, 13))
    }

    @Test fun measuresToolFrameDistanceWithoutUsingWorldAxisDifference() {
        val gate = EndpointStepGate()
        gate.begin(origin, 5.0, 10, 0)
        gate.acknowledge(true)
        gate.complete(0, true, 11)
        val reached = origin.toMutableList().apply { this[0] += 0.003; this[1] -= 0.004 }
        assertTrue(gate.reached(reached, 12))
    }

    @Test fun cannotQueueStepsAndTimeoutIsIndependentOfTouchHeartbeat() {
        val gate = EndpointStepGate()
        gate.begin(origin, 1.0, 10, 100)
        assertThrows(IllegalArgumentException::class.java) { gate.begin(origin, 1.0, 10, 100) }
        assertFalse(gate.timedOut(15099))
        assertTrue(gate.timedOut(15100))
        gate.reset()
        assertFalse(gate.active)
        assertFalse(gate.timedOut(20000))
    }

    @Test fun unknownDeviceCannotConfirmStepAndResetIgnoresLateReplies() {
        val gate = EndpointStepGate()
        gate.begin(origin, 5.0, 10, 0)
        gate.acknowledge(true)
        val reached = origin.toMutableList().apply { this[0] += 0.005 }
        val completion = RobotResponseParser.parse("""{"state":"current_trajectory_state","trajectory_state":true}""") as RobotUpdate.MotionCompletion
        gate.complete(completion.device, completion.success, 11)
        assertFalse(gate.reached(reached, 12))
        gate.reset()
        gate.complete(0, true, 13)
        gate.acknowledge(true)
        assertFalse(gate.reached(reached, 14))
    }

    @Test fun stepAndFrameReceiptsUseTheirDocumentedFields() {
        val step = RobotResponseParser.parse("""{"command":"set_pos_step","receive_state":false,"other":true}""") as RobotUpdate.Acknowledgement
        val frame = RobotResponseParser.parse("""{"command":"set_teach_frame","set_state":true,"other":false}""") as RobotUpdate.Acknowledgement
        assertEquals(false, step.success)
        assertEquals(true, frame.success)
    }
}
