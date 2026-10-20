package com.realman.rm65webview

import org.json.JSONArray
import org.json.JSONObject

data class ArmSnapshot(
    val jointsDeg: List<Double>,
    val pose: List<Double>,
    val systemErrors: List<Int>
)

data class SafetySnapshot(
    val enabled: List<Int>,
    val jointErrors: List<Int>,
    val systemErrors: List<Int>
) {
    val ready: Boolean
        get() = enabled.size >= 6 &&
            jointErrors.size >= 6 &&
            systemErrors.isNotEmpty() &&
            enabled.take(6).all { it == 1 } &&
            jointErrors.take(6).all { it == 0 } &&
            systemErrors.all { it == 0 }
}

data class GripperSnapshot(
    val enabled: Int,
    val online: Int,
    val error: Int,
    val position: Int,
    val forceGram: Int,
    val mode: Int = 0,
    val temperature: Int? = null
)

data class TrajectoryFileInfo(
    val name: String,
    val pointCount: Int,
    val createdAt: Long
)

enum class MotionCompletionKind { MOVE, GRIPPER, TRAJECTORY_FILE, PROGRAM }

enum class JointLimitKind { MINIMUM, MAXIMUM }
enum class FrameKind { TOOL, WORK }

data class JointLimitUpdate(
    val kind: JointLimitKind,
    val degrees: List<Double>
)

sealed interface RobotUpdate {
    data class Arm(val value: ArmSnapshot) : RobotUpdate
    data class Safety(val value: SafetySnapshot) : RobotUpdate
    data class Gripper(val value: GripperSnapshot) : RobotUpdate
    data class ToolVoltage(val type: Int) : RobotUpdate
    data class JointLimit(val value: JointLimitUpdate) : RobotUpdate
    data class FrameName(val kind: FrameKind, val name: String) : RobotUpdate
    data class TeachFrame(val type: Int) : RobotUpdate
    data class TrajectoryList(val value: List<TrajectoryFileInfo>) : RobotUpdate
    data class SoftwareInfo(val value: JSONObject) : RobotUpdate
    data class MotionCompletion(val kind: MotionCompletionKind, val success: Boolean, val device: Int = 0) : RobotUpdate
    data class Planning(val type: String) : RobotUpdate
    data class Acknowledgement(val command: String, val success: Boolean?) : RobotUpdate
    data class Unknown(val json: JSONObject) : RobotUpdate
}

object RobotResponseParser {
    fun parse(line: String): RobotUpdate {
        val root = JSONObject(line)
        return when (root.optString("state")) {
            "current_arm_state" -> RobotUpdate.Arm(parseArm(root.getJSONObject("arm_state")))
            "arm_all_state" -> RobotUpdate.Safety(parseSafety(root.getJSONObject("all_state")))
            "current_trajectory_state" -> parseCompletion(root)
            "arm_current_trajectory" -> RobotUpdate.Planning(root.getString("type"))
            "program_run_finish" -> RobotUpdate.MotionCompletion(MotionCompletionKind.PROGRAM, true)
            "joint_min_pos" -> RobotUpdate.JointLimit(
                JointLimitUpdate(
                    JointLimitKind.MINIMUM,
                    root.getJSONArray("min_pos").toDoubleList().take(6).map { it / 1_000.0 }
                )
            )
            "joint_max_pos" -> RobotUpdate.JointLimit(
                JointLimitUpdate(
                    JointLimitKind.MAXIMUM,
                    root.getJSONArray("max_pos").toDoubleList().take(6).map { it / 1_000.0 }
                )
            )
            "current_tool_frame" -> RobotUpdate.FrameName(FrameKind.TOOL, root.optString("tool_name"))
            "current_work_frame" -> RobotUpdate.FrameName(FrameKind.WORK, root.optString("frame_name"))
            "teach_frame" -> RobotUpdate.TeachFrame(root.getInt("frame_type"))
            "tool_voltage_state" -> RobotUpdate.ToolVoltage(parseToolVoltage(root))
            else -> when (root.optString("command")) {
                "get_current_arm_state" -> RobotUpdate.Arm(parseArm(root.getJSONObject("arm_state")))
                "get_arm_all_state" -> RobotUpdate.Safety(parseSafety(root.getJSONObject("all_state")))
                "get_current_tool_frame" -> RobotUpdate.FrameName(FrameKind.TOOL, root.optString("tool_name"))
                "get_current_work_frame" -> RobotUpdate.FrameName(FrameKind.WORK, root.optString("frame_name"))
                "get_teach_frame" -> RobotUpdate.TeachFrame(root.getInt("frame_type"))
                "get_joint_min_pos" -> RobotUpdate.JointLimit(
                    JointLimitUpdate(
                        JointLimitKind.MINIMUM,
                        root.getJSONArray("min_pos").toDoubleList().take(6).map { it / 1_000.0 }
                    )
                )
                "get_joint_max_pos" -> RobotUpdate.JointLimit(
                    JointLimitUpdate(
                        JointLimitKind.MAXIMUM,
                        root.getJSONArray("max_pos").toDoubleList().take(6).map { it / 1_000.0 }
                    )
                )
                "get_gripper_state" -> RobotUpdate.Gripper(
                    GripperSnapshot(
                        root.getInt("enable"),
                        root.getInt("status"),
                        root.getInt("error"),
                        root.getInt("actpos"),
                        root.optInt("current_force"),
                        root.optInt("mode", 0),
                        if (root.has("temperature")) root.getInt("temperature") else null
                    )
                )
                "get_tool_voltage", "tool_voltage_state" -> RobotUpdate.ToolVoltage(parseToolVoltage(root))
                "get_trajectory_file_list" -> if (root.opt("receive_state") == false) {
                    RobotUpdate.Acknowledgement("get_trajectory_file_list", false)
                } else if (root.has("list")) {
                    RobotUpdate.TrajectoryList(parseTrajectoryList(root))
                } else RobotUpdate.Acknowledgement("get_trajectory_file_list", firstBoolean(root))
                "arm_software_info", "get_arm_software_info" -> RobotUpdate.SoftwareInfo(root)
                "trajectory_file_state" -> RobotUpdate.MotionCompletion(
                    MotionCompletionKind.TRAJECTORY_FILE,
                    root.optBoolean("run_state", false)
                )
                "current_trajectory_state" -> parseCompletion(root)
                "get_arm_current_trajectory", "arm_current_trajectory" -> RobotUpdate.Planning(root.getString("type"))
                "" -> RobotUpdate.Unknown(root)
                else -> RobotUpdate.Acknowledgement(
                    root.optString("command"),
                    acknowledgementSuccess(root)
                )
            }
        }
    }

    private fun parseCompletion(root: JSONObject): RobotUpdate.MotionCompletion {
        val device = root.optInt("device", -1)
        return RobotUpdate.MotionCompletion(
            if (device == 1) MotionCompletionKind.GRIPPER else MotionCompletionKind.MOVE,
            root.getBoolean("trajectory_state"), device
        )
    }

    private fun parseArm(state: JSONObject): ArmSnapshot {
        val jointRaw = state.getJSONArray("joint").toIntList().take(6)
        val poseRaw = state.getJSONArray("pose").toIntList()
        val pose = poseRaw.mapIndexed { index, value ->
            if (index < 3) value / 1_000_000.0 else value / 1_000.0
        }
        return ArmSnapshot(
            jointsDeg = jointRaw.map { it / 1_000.0 },
            pose = pose,
            systemErrors = state.optErrors("err")
        )
    }

    private fun parseSafety(state: JSONObject) = SafetySnapshot(
        enabled = state.optJSONArray("en_flag")?.toIntList().orEmpty(),
        jointErrors = state.optJSONArray("err_flag")?.toIntList().orEmpty(),
        systemErrors = state.optErrors("err")
    )

    private fun parseTrajectoryList(root: JSONObject): List<TrajectoryFileInfo> {
        val array = root.getJSONArray("list")
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            TrajectoryFileInfo(
                name = item.optString("name", item.optString("trajectory_name")),
                pointCount = item.optInt("point_num", 0),
                createdAt = item.optLong("create_time", 0L)
            )
        }.filter { it.name.isNotBlank() }
    }

    private fun parseToolVoltage(root: JSONObject): Int {
        val type = root.getInt("voltage_type")
        require(type in setOf(0, 2, 3)) { "未知末端供电类型：$type" }
        return type
    }

    private fun acknowledgementSuccess(json: JSONObject): Boolean? {
        val key = when (json.optString("command")) {
            "start_drag_teach", "stop_drag_teach" -> "drag_teach"
            "save_trajectory_file" -> "save_state"
            "movej", "set_pos_step" -> "receive_state"
            "set_arm_slow_stop" -> "arm_slow_stop"
            "set_stop_teach" -> "stop_teach"
            "set_arm_stop" -> "arm_stop"
            "set_joint_teach" -> "joint_teach"
            "set_pos_teach" -> "pos_teach"
            "set_ort_teach" -> "ort_teach"
            "clear_system_err" -> "clear_state"
            "set_joint_clear_err" -> "joint_clear_err"
            "set_teach_frame" -> "set_state"
            "set_run_trajectory_file" -> return if (json.opt("run_state") is Boolean) {
                json.getBoolean("run_state")
            } else json.opt("start_state") as? Boolean
            "set_gripper", "set_gripper_release", "set_gripper_pick_on", "set_gripper_position" -> "state"
            else -> return firstBoolean(json)
        }
        return json.opt(key) as? Boolean
    }

    private fun firstBoolean(json: JSONObject): Boolean? {
        val names = json.keys()
        while (names.hasNext()) {
            val key = names.next()
            if (json.opt(key) is Boolean) return json.getBoolean(key)
        }
        return null
    }

    private fun JSONObject.optErrors(name: String): List<Int> {
        val value = opt(name) ?: return emptyList()
        return when (value) {
            is JSONArray -> value.toIntList()
            is Number -> listOf(value.toInt())
            is String -> listOfNotNull(value.toIntOrNull())
            else -> emptyList()
        }
    }

    private fun JSONArray.toIntList() = List(length()) { getInt(it) }
    private fun JSONArray.toDoubleList() = List(length()) { getDouble(it) }
}
