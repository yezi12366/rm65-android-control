package com.realman.rm65webview

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

enum class RecordingType { REMOTE, DRAG }
enum class GripperOperation { OPEN, CLOSE, POSITION }

sealed interface ActionNode {
    val type: String
    fun toJson(): JSONObject

    companion object {
        fun fromJson(json: JSONObject): ActionNode = when (json.getString("type")) {
            "waypoint" -> WaypointNode(
                jointsDeg = json.getJSONArray("joints_deg").toDoubleList(),
                controllerPointName = json.optString("controller_point_name"),
                speed = json.optInt("speed", 5),
                continuous = json.optBoolean("continuous", true)
            )
            "drag_trajectory" -> DragTrajectoryNode(
                controllerName = json.getString("controller_name"),
                pointCount = json.optInt("point_count", 0),
                startJointsDeg = json.optJSONArray("start_joints_deg")?.toDoubleList().orEmpty(),
                endJointsDeg = json.optJSONArray("end_joints_deg")?.toDoubleList().orEmpty(),
                minimumJointsDeg = json.optJSONArray("minimum_joints_deg")?.toDoubleList().orEmpty(),
                maximumJointsDeg = json.optJSONArray("maximum_joints_deg")?.toDoubleList().orEmpty(),
                verified = json.optBoolean("verified", false)
            )
            "gripper" -> GripperNode(
                operation = GripperOperation.valueOf(json.getString("operation").uppercase()),
                position = if (json.has("position")) json.getInt("position") else null
            )
            "wait" -> WaitNode(json.getLong("duration_ms"))
            else -> throw IllegalArgumentException("未知动作节点：${json.optString("type")}")
        }
    }
}

data class WaypointNode(
    val jointsDeg: List<Double>,
    val controllerPointName: String,
    val speed: Int,
    val continuous: Boolean = true
) : ActionNode {
    init {
        require(jointsDeg.size == 6 && jointsDeg.all { it.isFinite() }) { "路点必须包含六个有效关节角" }
        require(speed in 1..RobotProtocol.MAX_DEMO_SPEED) { "路点速度无效" }
    }

    override val type = "waypoint"
    override fun toJson() = JSONObject()
        .put("type", type)
        .put("joints_deg", JSONArray(jointsDeg))
        .put("controller_point_name", controllerPointName)
        .put("speed", speed)
        .put("continuous", continuous)
}

data class DragTrajectoryNode(
    val controllerName: String,
    val pointCount: Int,
    val startJointsDeg: List<Double>,
    val endJointsDeg: List<Double>,
    val minimumJointsDeg: List<Double>,
    val maximumJointsDeg: List<Double>,
    val verified: Boolean
) : ActionNode {
    init {
        require(controllerName.isNotBlank()) { "控制器轨迹名称不能为空" }
        require(pointCount >= 0) { "轨迹点数不能为负数" }
        listOf(startJointsDeg, endJointsDeg, minimumJointsDeg, maximumJointsDeg).forEach { values ->
            require(values.isEmpty() || values.size == 6) { "拖动轨迹关节数据必须为空或包含六轴" }
            require(values.all { it.isFinite() }) { "拖动轨迹包含无效关节角" }
        }
    }

    override val type = "drag_trajectory"
    override fun toJson() = JSONObject()
        .put("type", type)
        .put("controller_name", controllerName)
        .put("point_count", pointCount)
        .put("start_joints_deg", JSONArray(startJointsDeg))
        .put("end_joints_deg", JSONArray(endJointsDeg))
        .put("minimum_joints_deg", JSONArray(minimumJointsDeg))
        .put("maximum_joints_deg", JSONArray(maximumJointsDeg))
        .put("verified", verified)
}

data class GripperNode(
    val operation: GripperOperation,
    val position: Int? = null
) : ActionNode {
    init {
        if (operation == GripperOperation.POSITION) {
            require(position != null && position in 1..1000) { "夹爪位置必须为1~1000" }
        }
    }

    override val type = "gripper"
    override fun toJson() = JSONObject()
        .put("type", type)
        .put("operation", operation.name.lowercase())
        .apply { if (position != null) put("position", position) }
}

data class WaitNode(val durationMs: Long) : ActionNode {
    init {
        require(durationMs in 100L..60_000L) { "等待时间必须为0.1~60秒" }
    }

    override val type = "wait"
    override fun toJson() = JSONObject()
        .put("type", type)
        .put("duration_ms", durationMs)
}

data class ActionRecord(
    val id: String,
    val displayName: String,
    val recordingType: RecordingType,
    val defaultSpeed: Int,
    val nodes: List<ActionNode>,
    val createdAt: Long,
    val updatedAt: Long
) {
    init {
        require(id.isNotBlank()) { "动作ID不能为空" }
        require(displayName.isNotBlank()) { "动作名称不能为空" }
        require(defaultSpeed in 1..RobotProtocol.MAX_DEMO_SPEED) { "动作速度无效" }
    }

    fun toJson() = JSONObject()
        .put("id", id)
        .put("display_name", displayName)
        .put("recording_type", recordingType.name.lowercase())
        .put("default_speed", defaultSpeed)
        .put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)

    companion object {
        fun fromJson(json: JSONObject): ActionRecord {
            val rawNodes = json.getJSONArray("nodes")
            return ActionRecord(
                id = json.getString("id"),
                displayName = json.getString("display_name"),
                recordingType = RecordingType.valueOf(json.getString("recording_type").uppercase()),
                defaultSpeed = json.optInt("default_speed", 5),
                nodes = List(rawNodes.length()) { ActionNode.fromJson(rawNodes.getJSONObject(it)) },
                createdAt = json.optLong("created_at"),
                updatedAt = json.optLong("updated_at")
            )
        }
    }
}

class ActionDraft(
    val id: String,
    val displayName: String,
    val recordingType: RecordingType,
    val defaultSpeed: Int,
    val createdAt: Long
) {
    val nodes = mutableListOf<ActionNode>()

    fun appendWaypoint(
        jointsDeg: List<Double>,
        controllerPointName: String,
        speed: Int,
        continuous: Boolean = true
    ): Boolean {
        val previous = nodes.lastOrNull { it is WaypointNode } as? WaypointNode
        if (previous != null && previous.jointsDeg.indices.all {
                abs(previous.jointsDeg[it] - jointsDeg[it]) < WAYPOINT_TOLERANCE_DEG
            }
        ) return false
        nodes += WaypointNode(jointsDeg, controllerPointName, speed, continuous)
        return true
    }

    fun toRecord(now: Long): ActionRecord = ActionRecord(
        id = id,
        displayName = displayName,
        recordingType = recordingType,
        defaultSpeed = defaultSpeed,
        nodes = nodes.toList(),
        createdAt = createdAt,
        updatedAt = now
    )

    companion object {
        const val WAYPOINT_TOLERANCE_DEG = 0.05
    }
}

data class JointEnvelope(val minimum: List<Double>, val maximum: List<Double>)

object ActionSafety {
    fun controllerTrajectoryProblem(nodes: List<ActionNode>, files: List<TrajectoryFileInfo>): String? {
        for (node in nodes.filterIsInstance<DragTrajectoryNode>()) {
            val file = files.firstOrNull { it.name == node.controllerName }
                ?: return "动作引用的控制器轨迹 ${node.controllerName} 不存在，请刷新列表或重新录制。"
            if (file.pointCount <= 0) return "控制器轨迹 ${node.controllerName} 没有有效轨迹点，禁止执行。"
        }
        return null
    }

    fun envelope(nodes: List<ActionNode>): JointEnvelope {
        val minimum = MutableList(6) { Double.POSITIVE_INFINITY }
        val maximum = MutableList(6) { Double.NEGATIVE_INFINITY }
        fun include(values: List<Double>) {
            if (values.size != 6) return
            values.indices.forEach { index ->
                minimum[index] = minOf(minimum[index], values[index])
                maximum[index] = maxOf(maximum[index], values[index])
            }
        }
        nodes.forEach { node ->
            when (node) {
                is WaypointNode -> include(node.jointsDeg)
                is DragTrajectoryNode -> {
                    include(node.minimumJointsDeg)
                    include(node.maximumJointsDeg)
                }
                else -> Unit
            }
        }
        return JointEnvelope(
            minimum.map { if (it.isFinite()) it else 0.0 },
            maximum.map { if (it.isFinite()) it else 0.0 }
        )
    }
}

internal fun JSONArray.toDoubleList() = List(length()) { getDouble(it) }
