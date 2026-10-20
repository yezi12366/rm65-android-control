package com.realman.rm65webview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RobotActionProtocolTest {
    @Test fun toolPowerReadbackAcceptsDocumentedAndCommandReplyEnvelopes() {
        assertEquals("get_tool_voltage", JSONObject(RobotProtocol.toolVoltageState()).getString("command"))
        for (field in listOf("state", "command")) {
            val name = if (field == "state") "tool_voltage_state" else "get_tool_voltage"
            val update = RobotResponseParser.parse("""{"$field":"$name","voltage_type":3}""") as RobotUpdate.ToolVoltage
            assertEquals(3, update.type)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownPowerTypeIsNotAssumedToBe24Volts() {
        RobotResponseParser.parse("""{"state":"tool_voltage_state","voltage_type":24}""")
    }

    @Test fun nativeFileReceiptSupportsBothDocumentedBooleanKeys() {
        for (key in listOf("run_state", "start_state")) {
            val update = RobotResponseParser.parse("{\"command\":\"set_run_trajectory_file\",\"$key\":true}") as RobotUpdate.Acknowledgement
            assertEquals(true, update.success)
        }
    }

    @Test(expected = org.json.JSONException::class)
    fun missingGripperErrorFieldIsNotAssumedHealthy() {
        RobotResponseParser.parse("""{"command":"get_gripper_state","enable":1,"status":1,"mode":6,"actpos":400}""")
    }

    @Test fun completionRepliesPreserveDeviceAndMissingDeviceIsUnknown() {
        val gripper = RobotResponseParser.parse(
            """{"state":"current_trajectory_state","trajectory_state":false,"device":1}"""
        ) as RobotUpdate.MotionCompletion
        assertEquals(MotionCompletionKind.GRIPPER, gripper.kind)
        assertEquals(1, gripper.device)
        val missing = RobotResponseParser.parse("""{"state":"current_trajectory_state","trajectory_state":true}""")
            as RobotUpdate.MotionCompletion
        assertEquals(-1, missing.device)
    }

    @Test fun readsGripperWorkingModeAndGenericReceipt() {
        val gripper = RobotResponseParser.parse(
            """{"command":"get_gripper_state","enable":1,"status":1,"error":0,"mode":6,"actpos":400,"current_force":200,"temperature":40}"""
        ) as RobotUpdate.Gripper
        assertEquals(6, gripper.value.mode)
        assertEquals(40, gripper.value.temperature)
        val ack = RobotResponseParser.parse("""{"command":"set_gripper","state":true}""") as RobotUpdate.Acknowledgement
        assertEquals(true, ack.success)
    }

    @Test fun supportsBothControllerPlanningReplyFormats() {
        val third = RobotResponseParser.parse("""{"state":"arm_current_trajectory","type":"none","data":[0,0,0,0,0,0]}""")
            as RobotUpdate.Planning
        val fourth = RobotResponseParser.parse("""{"command":"get_arm_current_trajectory","type":"movej","data":[0,0,0,0,0,0]}""")
            as RobotUpdate.Planning
        assertEquals("none", third.type)
        assertEquals("movej", fourth.type)
    }

    @Test
    fun rejectedListQueryIsNotAnEmptyTrajectoryList() {
        val update = RobotResponseParser.parse(
            """{"command":"get_trajectory_file_list","receive_state":false}"""
        )
        assertTrue(update is RobotUpdate.Acknowledgement)
        assertFalse((update as RobotUpdate.Acknowledgement).success!!)
    }

    @Test
    fun failedListReplyCannotConfirmInterfaceSupportEvenWithAnEmptyArray() {
        val update = RobotResponseParser.parse(
            """{"command":"get_trajectory_file_list","receive_state":false,"list":[]}"""
        ) as RobotUpdate.Acknowledgement
        assertFalse(update.success!!)
    }

    @Test
    fun missingListIsNotAnEmptyTrajectoryList() {
        assertTrue(RobotResponseParser.parse("""{"command":"get_trajectory_file_list"}""")
            is RobotUpdate.Acknowledgement)
        val empty = RobotResponseParser.parse("""{"command":"get_trajectory_file_list","list":[],"total_size":0}""")
            as RobotUpdate.TrajectoryList
        assertTrue(empty.value.isEmpty())
    }

    @Test(expected = org.json.JSONException::class)
    fun malformedListIsNotAnEmptyTrajectoryList() {
        RobotResponseParser.parse("""{"command":"get_trajectory_file_list","list":null}""")
    }

    @Test
    fun dragAcknowledgementsRequireTheirDocumentedSuccessField() {
        val update = RobotResponseParser.parse(
            """{"command":"save_trajectory_file","unrelated_flag":true}"""
        ) as RobotUpdate.Acknowledgement
        assertNull(update.success)
    }

    @Test
    fun readsControllerSoftwareWithoutGuessingTheGeneration() {
        val update = RobotResponseParser.parse(
            """{"command":"arm_software_info","Product_version":"RM65-BI","ctrl_info":{"version":"V1.4.6"},"plan_info":{"version":"V1.4.6"}}"""
        ) as RobotUpdate.SoftwareInfo
        assertEquals("V1.4.6", update.value.getJSONObject("ctrl_info").getString("version"))
        assertFalse(update.value.has("robot_controller_version"))
    }

    @Test
    fun buildsNativeDragCommands() {
        assertEquals(1, JSONObject(RobotProtocol.startDragTeach()).getInt("trajectory_record"))
        assertEquals("stop_drag_teach", JSONObject(RobotProtocol.stopDragTeach()).getString("command"))
        assertEquals("A123S1", JSONObject(RobotProtocol.saveTrajectoryFile("A123S1")).getString("name"))
        assertEquals("get_trajectory_file_list", JSONObject(RobotProtocol.trajectoryFileList()).getString("command"))
        assertEquals("A123S1", JSONObject(RobotProtocol.runTrajectoryFile("A123S1")).getString("name"))
    }

    @Test
    fun buildsWaypointAndPlaybackCommands() {
        val move = JSONObject(RobotProtocol.moveJTo(listOf(1.0, -2.0, 3.25, 0.0, 0.0, 0.0), 7, 0))
        assertEquals(listOf(1000, -2000, 3250, 0, 0, 0), List(6) { move.getJSONArray("joint").getInt(it) })
        assertEquals("set_arm_pause", JSONObject(RobotProtocol.pauseMotion()).getString("command"))
        assertEquals("set_arm_continue", JSONObject(RobotProtocol.continueMotion()).getString("command"))
        assertEquals("pause_drag_trajectory", JSONObject(RobotProtocol.pauseDragTrajectory()).getString("command"))
        assertEquals("continue_drag_trajectory", JSONObject(RobotProtocol.continueDragTrajectory()).getString("command"))
        assertEquals("stop_drag_trajectory", JSONObject(RobotProtocol.stopDragTrajectory()).getString("command"))
    }

    @Test
    fun parsesTrajectoryListAndCompletion() {
        val list = RobotResponseParser.parse(
            """{"command":"get_trajectory_file_list","list":[{"name":"A1S1","point_num":501,"create_time":123}],"total_size":1}"""
        ) as RobotUpdate.TrajectoryList
        assertEquals(1, list.value.size)
        assertEquals("A1S1", list.value.first().name)
        assertEquals(501, list.value.first().pointCount)

        val completed = RobotResponseParser.parse(
            """{"command":"trajectory_file_state","run_state":true}"""
        ) as RobotUpdate.MotionCompletion
        assertTrue(completed.success)
        assertEquals(MotionCompletionKind.TRAJECTORY_FILE, completed.kind)
    }
}
