package com.realman.rm65webview

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Trial 1:1:1 joint-space bend. J1/J4/J6 stay at their measured starting angles. */
data class CoupledBendPlan(
    val origin: List<Double>,
    val target: List<Double>,
    val lower: List<Double>,
    val upper: List<Double>,
    val speed: Int,
    val direction: Int,
    val travelDeg: Double
)

class CoupledBendFlow {
    companion object {
        val AXES: Set<Int> = setOf(1, 2, 4) // zero-based J2/J3/J5
        const val MAX_TRAVEL_DEG = 10.0
        const val MAX_SPEED = RobotProtocol.MAX_DEMO_SPEED
        const val HEARTBEAT_MS = 1_500L
        const val TIMEOUT_MS = 30_000L

        fun plan(joints: List<Double>, lower: List<Double>, upper: List<Double>, direction: Int, speed: Int): CoupledBendPlan {
            require(direction == -1 || direction == 1) { "联动方向无效" }
            require(speed in 1..RobotProtocol.MAX_DEMO_SPEED) { "联动速度无效" }
            require(listOf(joints, lower, upper).all { values -> values.size == 6 && values.all { it.isFinite() } }) { "关节或限位数据无效" }
            require(joints.indices.all { lower[it] < upper[it] && joints[it] in lower[it]..upper[it] }) { "当前位置超出软件安全区，请先单轴调整" }
            val room = AXES.minOf { if (direction > 0) upper[it] - joints[it] else joints[it] - lower[it] }
            // Leave an extra 0.05 degrees at the nearest boundary and round inward.
            val travel = floor(min(MAX_TRAVEL_DEG, room - .05) * 1_000.0) / 1_000.0
            require(travel >= .5) { "J2/J3/J5 接近软件限位，可用联动幅度不足 0.5°，请单轴调整" }
            val target = joints.mapIndexed { index, value -> if (index in AXES) value + direction * travel else value }
            return CoupledBendPlan(joints.toList(), target, lower.toList(), upper.toList(), min(speed, MAX_SPEED), direction, travel)
        }
    }

    var active: Boolean = false
        private set
    var plan: CoupledBendPlan? = null
        private set
    private var accepted = false
    private var completed = false
    private var startedAt = 0L
    private var completionSequence = 0L
    private var lastSequence = 0L
    private var stableFrames = 0

    fun begin(value: CoupledBendPlan, now: Long) {
        check(!active) { "上一段联动尚未结束" }
        // Revalidate even an internally supplied plan: no arbitrary multi-axis targets.
        require(value == plan(value.origin, value.lower, value.upper, value.direction, value.speed)) { "联动方案无效" }
        plan = value
        active = true
        accepted = false
        completed = false
        stableFrames = 0
        lastSequence = 0L
        startedAt = now
    }

    fun acknowledge(success: Boolean?) {
        if (active) accepted = success == true
    }

    fun complete(device: Int, success: Boolean, sequence: Long) {
        if (!active || !accepted || device != 0 || !success) return
        completed = true
        completionSequence = sequence
    }

    fun reached(joints: List<Double>, sequence: Long): Boolean {
        val target = plan?.target ?: return false
        if (!active || !accepted || !completed || sequence <= completionSequence || sequence <= lastSequence) return false
        lastSequence = sequence
        val atTarget = joints.size == 6 && joints.indices.all { joints[it].isFinite() && abs(joints[it] - target[it]) <= .05 }
        stableFrames = if (atTarget) stableFrames + 1 else 0
        return stableFrames >= 2
    }

    fun problem(joints: List<Double>, lower: List<Double>, upper: List<Double>, now: Long, heartbeatAt: Long): String? {
        val value = plan ?: return if (active) "联动目标丢失" else null
        if (!active) return null
        if (now - heartbeatAt >= HEARTBEAT_MS) return "联动按住信号超时"
        if (!accepted && now - startedAt >= 5_000L) return "联动指令确认超时"
        if (now - startedAt >= TIMEOUT_MS) return "联动到位反馈超时"
        if (lower != value.lower || upper != value.upper) return "联动期间软件安全区已变化"
        if (joints.size != 6 || joints.any { !it.isFinite() }) return "联动关节反馈无效"
        if (joints.indices.any { joints[it] !in lower[it]..upper[it] }) return "联动已到软件安全区边界"
        if (joints.indices.any { joints[it] !in min(value.origin[it], value.target[it]) - .25..max(value.origin[it], value.target[it]) + .25 }) {
            return "关节偏离联动范围"
        }
        // For a single joint-space move the three driven axes should follow the same progress.
        val progress = AXES.map { (joints[it] - value.origin[it]) * value.direction }
        if (progress.maxOrNull()!! - progress.minOrNull()!! > .5) return "J2/J3/J5 联动进度不一致"
        return null
    }

    fun reset() {
        active = false
        plan = null
        accepted = false
        completed = false
        stableFrames = 0
    }
}
