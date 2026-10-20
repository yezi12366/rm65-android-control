package com.realman.rm65webview

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Only arm joints are saved. Reset never changes the gripper. */
data class SavedResetPose(val joints: List<Double>, val savedAt: Long) {
    init {
        require(joints.size == 6 && joints.all { it.isFinite() })
        require(savedAt > 0L)
    }
    fun matches(actual: List<Double>): Boolean = actual.size == 6 && actual.indices.all { actual[it].isFinite() && abs(actual[it] - joints[it]) <= .05 }
    fun toJson(): String = JSONObject().put("version", 1).put("joint", JSONArray(joints)).put("saved_at", savedAt).toString()
    companion object {
        fun decode(value: String?): SavedResetPose? = runCatching {
            val root = JSONObject(requireNotNull(value))
            require(root.getInt("version") == 1)
            val joint = root.getJSONArray("joint")
            require(joint.length() == 6)
            SavedResetPose(List(6) { joint.getDouble(it) }, root.getLong("saved_at"))
        }.getOrNull()
    }
}

/** One finite, held joint move to a saved pose; stale feedback and release are handled by the native owner. */
class SavedResetFlow {
    companion object { const val MAX_SPEED = RobotProtocol.MAX_DEMO_SPEED }
    var active = false
        private set
    var target = emptyList<Double>()
        private set
    private var origin = emptyList<Double>()
    private var lower = emptyList<Double>()
    private var upper = emptyList<Double>()
    private var startedAt = 0L
    private var accepted = false
    private var completed = false
    private var completionSequence = 0L
    private var lastSequence = 0L
    private var stableFrames = 0

    fun begin(joints: List<Double>, saved: SavedResetPose, low: List<Double>, high: List<Double>, speed: Int, now: Long): Int {
        check(!active) { "正在复位" }
        require(speed in 1..RobotProtocol.MAX_DEMO_SPEED) { "复位速度无效" }
        require(listOf(joints, saved.joints, low, high).all { it.size == 6 && it.all(Double::isFinite) }) { "复位数据无效" }
        require((0..5).all { low[it] < high[it] && joints[it] in low[it]..high[it] && saved.joints[it] in low[it]..high[it] }) {
            "当前姿态或已保存的复位姿态超出软件安全区，请先单轴调整或重新保存"
        }
        origin = joints.toList()
        target = saved.joints.toList()
        lower = low.toList()
        upper = high.toList()
        startedAt = now
        accepted = false
        completed = false
        stableFrames = 0
        lastSequence = 0
        active = true
        return min(speed, MAX_SPEED)
    }
    fun acknowledge(success: Boolean?) { if (active) accepted = success == true }
    fun complete(device: Int, success: Boolean, sequence: Long) {
        if (!active || !accepted || device != 0 || !success) return
        completed = true
        completionSequence = sequence
    }
    fun reached(joints: List<Double>, sequence: Long): Boolean {
        if (!active || !accepted || !completed || sequence <= completionSequence || sequence <= lastSequence) return false
        lastSequence = sequence
        stableFrames = if (joints.size == 6 && joints.indices.all { joints[it].isFinite() && abs(joints[it] - target[it]) <= .05 }) stableFrames + 1 else 0
        return stableFrames >= 2
    }
    fun problem(joints: List<Double>, low: List<Double>, high: List<Double>, now: Long, heartbeatAt: Long): String? {
        if (!active) return null
        if (now - heartbeatAt >= 1_500L) return "复位按住信号超时"
        if (!accepted && now - startedAt >= 5_000L) return "复位指令确认超时"
        if (now - startedAt >= 120_000L) return "复位到位反馈超时"
        if (low != lower || high != upper) return "复位期间软件安全区已变化"
        if (joints.size != 6 || joints.any { !it.isFinite() }) return "复位关节反馈无效"
        if (joints.indices.any { joints[it] !in lower[it]..upper[it] }) return "复位到达软件安全区边界"
        if (joints.indices.any { joints[it] !in min(origin[it],target[it]) - .25..max(origin[it],target[it]) + .25 }) return "关节偏离复位范围"
        return null
    }
    fun reset() {
        active = false
        origin = emptyList()
        target = emptyList()
        accepted = false
        completed = false
        stableFrames = 0
    }
}
