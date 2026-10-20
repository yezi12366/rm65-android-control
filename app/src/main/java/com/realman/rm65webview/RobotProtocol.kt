package com.realman.rm65webview

import org.json.JSONArray
import org.json.JSONObject

enum class JogMode { JOINT, POSITION, ORIENTATION }

object RobotProtocol {
    const val MAX_DEMO_SPEED = 30

    fun currentState() = command("get_current_arm_state")
    fun allState() = command("get_arm_all_state")
    fun clearSystemError() = command("clear_system_err")
    fun clearJointError(joint: Int): String {
        require(joint in 1..6) { "关节编号只允许 1～6" }
        return JSONObject().put("command", "set_joint_clear_err").put("joint_clear_err", joint).toString()
    }
    fun softwareInfo() = command("get_arm_software_info")
    fun currentPlanning() = command("get_arm_current_trajectory")
    fun jointMinLimits() = command("get_joint_min_pos")
    fun jointMaxLimits() = command("get_joint_max_pos")
    fun currentToolFrame() = command("get_current_tool_frame")
    fun currentWorkFrame() = command("get_current_work_frame")
    fun teachFrame() = command("get_teach_frame")

    fun setTeachFrame(frame: Int): String {
        require(frame in 0..1) { "示教坐标模式无效" }
        return JSONObject().put("command", "set_teach_frame").put("frame_type", frame).toString()
    }

    fun positionStep(axis: String, distanceMm: Double, speed: Int): String {
        require(axis in setOf("x", "y", "z")) { "步进方向无效" }
        require(distanceMm.isFinite() && kotlin.math.abs(distanceMm) in setOf(1.0, 5.0, 10.0)) {
            "位置步进只允许 ±1、±5 或 ±10 mm"
        }
        require(speed in 1..MAX_DEMO_SPEED) { "速度只允许 1～$MAX_DEMO_SPEED%" }
        return JSONObject()
            .put("command", "set_pos_step")
            .put("step_type", "${axis}_step")
            .put("step", (distanceMm * 1_000).toInt())
            .put("v", speed)
            .toString()
    }
    fun gripperState() = command("get_gripper_state")
    fun toolVoltageState() = command("get_tool_voltage")
    fun stopTeach() = command("set_stop_teach")
    fun slowStop() = command("set_arm_slow_stop")
    fun quickStop() = command("set_arm_stop")
    fun pauseMotion() = command("set_arm_pause")
    fun continueMotion() = command("set_arm_continue")
    fun pauseDragTrajectory() = command("pause_drag_trajectory")
    fun continueDragTrajectory() = command("continue_drag_trajectory")
    fun startDragTeach() = JSONObject()
        .put("command", "start_drag_teach")
        .put("trajectory_record", 1)
        .toString()
    fun stopDragTeach() = command("stop_drag_teach")
    fun stopDragTrajectory() = command("stop_drag_trajectory")
    fun dragTrajectoryOrigin() = command("drag_trajectory_origin")
    fun trajectoryFileList() = command("get_trajectory_file_list")

    fun saveTrajectoryFile(name: String): String = JSONObject()
        .put("command", "save_trajectory_file")
        .put("name", controllerName(name))
        .toString()

    fun runTrajectoryFile(name: String): String = JSONObject()
        .put("command", "set_run_trajectory_file")
        .put("name", controllerName(name))
        .toString()

    fun moveJToZero(speed: Int): String {
        require(speed in 1..MAX_DEMO_SPEED) { "Demo速度只允许1~$MAX_DEMO_SPEED%" }
        return JSONObject()
            .put("command", "movej")
            .put("joint", JSONArray(listOf(0, 0, 0, 0, 0, 0)))
            .put("v", speed)
            .put("r", 0)
            .put("trajectory_connect", 0)
            .toString()
    }

    fun moveJTo(jointsDeg: List<Double>, speed: Int, blendRadius: Int = 0): String {
        require(jointsDeg.size == 6 && jointsDeg.all { it.isFinite() }) { "目标必须包含六个有效关节角" }
        require(speed in 1..MAX_DEMO_SPEED) { "Demo速度只允许1~$MAX_DEMO_SPEED%" }
        require(blendRadius in 0..100) { "交融半径只允许0~100" }
        return JSONObject()
            .put("command", "movej")
            .put("joint", JSONArray(jointsDeg.map { (it * 1_000.0).toInt() }))
            .put("v", speed)
            .put("r", blendRadius)
            .put("trajectory_connect", 0)
            .toString()
    }

    fun addGlobalWaypoint(
        name: String,
        jointsDeg: List<Double>,
        pose: List<Double>,
        workFrame: String,
        toolFrame: String,
        timestamp: String
    ): String {
        require(jointsDeg.size == 6 && pose.size == 6) { "全局路点数据必须为六轴和六维位姿" }
        return JSONObject()
            .put("command", "add_global_waypoint")
            .put("point_name", controllerName(name))
            .put("joint", JSONArray(jointsDeg.map { (it * 1_000.0).toInt() }))
            .put("pose", JSONArray(pose.mapIndexed { index, value ->
                if (index < 3) (value * 1_000_000.0).toInt() else (value * 1_000.0).toInt()
            }))
            .put("work_frame", workFrame.ifBlank { "World" })
            .put("tool_frame", toolFrame.ifBlank { "Arm_Tip" })
            .put("time", timestamp)
            .toString()
    }

    fun deleteGlobalWaypoint(name: String): String = JSONObject()
        .put("command", "delete_global_waypoint")
        .put("point_name", controllerName(name))
        .toString()

    fun jog(mode: JogMode, axis: String, positive: Boolean, speed: Int): String {
        require(speed in 1..MAX_DEMO_SPEED) { "Demo速度只允许1~$MAX_DEMO_SPEED%" }
        val json = JSONObject()
        json.put("direction", if (positive) "pos" else "neg")
        json.put("v", speed)
        when (mode) {
            JogMode.JOINT -> {
                val joint = axis.toInt()
                require(joint in 1..6)
                json.put("command", "set_joint_teach")
                json.put("teach_joint", joint)
            }
            JogMode.POSITION -> {
                require(axis in setOf("x", "y", "z"))
                json.put("command", "set_pos_teach")
                json.put("teach_type", axis)
            }
            JogMode.ORIENTATION -> {
                require(axis in setOf("rx", "ry", "rz"))
                json.put("command", "set_ort_teach")
                json.put("teach_type", axis)
            }
        }
        return json.toString()
    }

    fun toolVoltageOn(): String = JSONObject()
        .put("command", "set_tool_voltage")
        .put("voltage_type", 3)
        .toString()

    fun gripperOpen(speed: Int = 200): String = JSONObject()
        .put("command", "set_gripper_release")
        .put("speed", speed.coerceIn(1, 1000))
        .put("block", false)
        .toString()

    fun gripperClose(speed: Int = 500, force: Int = 200): String = JSONObject()
        .put("command", "set_gripper_pick_on")
        .put("speed", speed.coerceIn(1, 1000))
        .put("force", force.coerceIn(50, 1000))
        .put("block", false)
        .toString()

    fun gripperPosition(position: Int): String = JSONObject()
        .put("command", "set_gripper_position")
        .put("position", position.coerceIn(1, 1000))
        .put("block", false)
        .toString()

    private fun controllerName(value: String): String {
        val clean = value.trim()
        require(clean.isNotEmpty() && clean.length <= 30) { "控制器内部名称长度必须为1~30" }
        require(clean.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { "控制器内部名称只能包含字母、数字、下划线和短横线" }
        return clean
    }

    private fun command(name: String) = JSONObject().put("command", name).toString()
}
