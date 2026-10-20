package com.realman.rm65webview

import kotlin.math.abs

/** Clearing is explicit and never enables a joint or resumes a motion. Target 0 means system errors. */
class AlarmClearFlow {
    enum class Phase { IDLE, CHECKING, WAIT_ACK, VERIFYING }
    sealed interface Event {
        data class Send(val target: Int) : Event
        data class Finished(val success: Boolean, val message: String) : Event
    }
    var phase = Phase.IDLE
        private set
    var target = 0
        private set
    val active get() = phase != Phase.IDLE
    var message = ""
        private set
    private var since = 0L
    private var anchor = emptyList<Double>()
    private var stableSince = 0L
    private var frames = 0
    private var arm: ArmSnapshot? = null
    private var safety: SafetySnapshot? = null
    private var armAt = -1L
    private var safetyAt = -1L
    private var lastSequence = -1L
    private var planningType = ""
    private var planningAt = -1L
    private var verifiedArmFrames = 0
    private var verifiedSafetyFrames = 0

    fun begin(target: Int, now: Long) {
        require(target in 0..6 && !active)
        reset()
        this.target = target
        phase = Phase.CHECKING
        since = now
        message = "正在确认机械臂静止，随后清除${targetName()}报警…"
    }

    fun observeArm(value: ArmSnapshot, sequence: Long, requestedAt: Long, now: Long) {
        if (!active || requestedAt <= since || now < requestedAt || sequence <= lastSequence) return
        lastSequence = sequence
        arm = value; armAt = now
        val joints = value.jointsDeg
        if (joints.size != 6 || joints.any { !it.isFinite() }) {
            anchor = emptyList(); frames = 0
        } else if (anchor.size != 6 || joints.indices.any { abs(joints[it] - anchor[it]) > .03 }) {
            anchor = joints.toList(); stableSince = now; frames = 1
        } else frames++
        if (phase == Phase.VERIFYING) {
            verifiedArmFrames = if (value.systemErrors.isEmpty() ||
                (target == 0 && value.systemErrors.any { it != 0 })) 0 else verifiedArmFrames + 1
        }
    }

    fun observeSafety(value: SafetySnapshot, requestedAt: Long, now: Long) {
        if (!active || requestedAt <= since || now < requestedAt || now <= safetyAt) return
        safety = value; safetyAt = now
        if (phase == Phase.VERIFYING) {
            // Still count valid fault reports, so a persistent fault is reported promptly instead of as a timeout.
            verifiedSafetyFrames++
        }
    }

    fun observePlanning(type: String, requestedAt: Long, now: Long) {
        if (!active || requestedAt <= since || now < requestedAt) return
        planningType = type; planningAt = now
        if (type != "none") { frames = 0; anchor = emptyList() }
    }

    fun acknowledge(command: String, success: Boolean?, now: Long): Event.Finished? {
        if (phase != Phase.WAIT_ACK || command != expectedCommand()) return null
        if (success != true) return finish(false, "控制器未确认清除${targetName()}报警；请检查报警详情。")
        phase = Phase.VERIFYING; since = now
        arm = null; safety = null; armAt = -1L; safetyAt = -1L
        verifiedArmFrames = 0; verifiedSafetyFrames = 0
        message = "清除请求已接受，正在重新读取报警确认结果…"
        return null
    }

    fun advance(now: Long): Event? {
        if (!active) return null
        if (now - since >= 6_000L) return finish(false, when (phase) {
            Phase.CHECKING -> "无法确认机械臂静止或报警状态不完整，未发送清除指令。"
            Phase.WAIT_ACK -> "清除报警回复超时，结果未知；请刷新状态后再检查。"
            else -> "清除后未取得完整的新状态，无法确认结果；请刷新检查。"
        })
        val a = arm ?: return null
        val s = safety ?: return null
        if (now - armAt !in 0..500L || now - safetyAt !in 0..1_500L ||
            a.systemErrors.isEmpty() || s.systemErrors.isEmpty() || s.jointErrors.size < 6) return null
        val remaining = if (target == 0) (a.systemErrors + s.systemErrors).any { it != 0 }
            else s.jointErrors[target - 1] != 0
        if (phase == Phase.VERIFYING && verifiedSafetyFrames >= 2) {
            if (remaining) return finish(false, "${targetName()}报警仍存在或再次出现；请先排除原因。")
            if (verifiedArmFrames >= 2) return finish(true, "已确认${targetName()}报警清除；不会自动继续运动或给关节上使能。")
        }
        if (phase == Phase.CHECKING && frames >= 3 && now - stableSince >= 300L &&
            planningType == "none" && now - planningAt in 0..1_000L) {
            if (!remaining) return finish(true, "当前${targetName()}已无报警，无需发送清除指令。")
            phase = Phase.WAIT_ACK; since = now
            message = "正在等待控制器确认清除${targetName()}报警…"
            return Event.Send(target)
        }
        return null
    }

    fun cancel(reason: String) { reset(); message = reason }
    fun reset() {
        phase = Phase.IDLE; message = ""; frames = 0; anchor = emptyList()
        arm = null; safety = null; armAt = -1L; safetyAt = -1L; lastSequence = -1L
        planningAt = -1L; planningType = ""; verifiedArmFrames = 0; verifiedSafetyFrames = 0
    }
    private fun targetName() = if (target == 0) "系统" else "J$target 关节"
    private fun expectedCommand() = if (target == 0) "clear_system_err" else "set_joint_clear_err"
    private fun finish(success: Boolean, text: String): Event.Finished {
        phase = Phase.IDLE; message = text
        return Event.Finished(success, text)
    }
}
