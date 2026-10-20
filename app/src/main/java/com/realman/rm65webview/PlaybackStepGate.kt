package com.realman.rm65webview

/** Match device/kind, receipt and fresh target feedback before advancing one arm step. */
class PlaybackStepGate {
    var command: String? = null
        private set
    private var kind = MotionCompletionKind.MOVE
    private var target = emptyList<Double>()
    private var startedAt = 0L
    private var acceptedAt: Long? = null
    private var completionReported = false
    private var planningType = ""
    private var planningAt = -1L
    private var targetSince: Long? = null
    private var targetFrames = 0
    private var armAt = -1L
    private var pausedAt: Long? = null

    val pending: Boolean get() = command != null
    val accepted: Boolean get() = acceptedAt != null
    val targetJoints: List<Double> get() = target

    fun begin(command: String, kind: MotionCompletionKind, target: List<Double>, now: Long) {
        reset()
        this.command = command
        this.kind = kind
        this.target = target.toList()
        startedAt = now
    }

    fun acknowledge(command: String, success: Boolean?, now: Long): String? {
        if (!pending || command != this.command) return null
        if (success != true) return "控制器未接受当前动作指令：$command。"
        if (acceptedAt == null) acceptedAt = now
        return null
    }

    fun completion(update: RobotUpdate.MotionCompletion): String? {
        if (!pending || update.device != 0 || update.kind != kind) return null
        if (!update.success) return "控制器报告当前机械臂动作未到位，已停止动作链。"
        completionReported = true
        return null
    }

    fun observeArm(joints: List<Double>, now: Long) {
        if (!pending || !accepted || kind != MotionCompletionKind.MOVE) return
        armAt = now
        if (target.size != 6 || joints.size != 6 || target.indices.any { kotlin.math.abs(target[it] - joints[it]) > TARGET_TOLERANCE_DEG }) {
            targetSince = null
            targetFrames = 0
        } else {
            if (targetSince == null) targetSince = now
            targetFrames++
        }
    }

    fun observePlanning(type: String, now: Long) {
        if (!pending || !accepted) return
        planningType = type
        planningAt = now
    }

    fun ready(now: Long): Boolean {
        if (!pending || !accepted || pausedAt != null) return false
        if (kind == MotionCompletionKind.TRAJECTORY_FILE) return completionReported
        val since = targetSince ?: return false
        val targetSettled = targetFrames >= 2 && now - since >= SETTLE_MS && now - armAt <= FEEDBACK_FRESH_MS
        val noPlanning = planningType == "none" && planningAt >= requireNotNull(acceptedAt) && now - planningAt <= FEEDBACK_FRESH_MS
        return targetSettled && noPlanning
    }

    fun pause(now: Long) { if (pending && pausedAt == null) pausedAt = now }
    fun resume(now: Long) {
        pausedAt?.let {
            val duration = now - it
            startedAt += duration
        }
        pausedAt = null
    }

    fun timeout(now: Long): String? {
        if (!pending || pausedAt != null) return null
        if (!accepted && now - startedAt >= ACK_TIMEOUT_MS) return "动作指令 $command 的接收确认超时；未自动重发，已停止动作链。"
        if (now - startedAt >= COMPLETION_TIMEOUT_MS) return "动作指令 $command 的到位确认超时，已停止动作链。"
        return null
    }

    fun reset() {
        command = null
        acceptedAt = null
        completionReported = false
        planningType = ""
        planningAt = -1L
        targetSince = null
        targetFrames = 0
        armAt = -1L
        pausedAt = null
    }

    companion object {
        const val ACK_TIMEOUT_MS = 5_000L
        const val COMPLETION_TIMEOUT_MS = 15 * 60_000L
        const val FEEDBACK_FRESH_MS = 1_000L
        const val SETTLE_MS = 150L
        const val TARGET_TOLERANCE_DEG = 0.1
    }
}
