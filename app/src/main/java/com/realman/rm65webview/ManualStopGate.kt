package com.realman.rm65webview

import kotlin.math.abs

/** Every manual release stays locked until both stop receipts, idle planning and fresh stillness agree. */
class ManualStopGate {
    var pending = false
        private set
    var unconfirmed = false
        private set
    var quick = false
        private set
    private var startedAt = 0L
    private var lastSentAt: Long? = null
    private var teachAccepted = false
    private var armAccepted = false
    private var acceptedAt: Long? = null
    val receiptsAccepted get() = acceptedAt != null
    private var planningAt = -1L
    private var planningType = ""
    private var stableSince: Long? = null
    private var anchor = emptyList<Double>()
    private var frames = 0
    private var lastArmAt = -1L
    private var lastSequence = -1L

    fun begin(now: Long, quick: Boolean = false) {
        if (pending) {
            if (quick) escalate()
            lastSentAt = null
            return
        }
        reset(); pending = true; startedAt = now; this.quick = quick
    }
    fun acknowledge(command: String, success: Boolean?, now: Long): String? {
        if (!pending || command !in setOf("set_stop_teach", armStopCommand())) return null
        if (command == "set_stop_teach") teachAccepted = success == true else armAccepted = success == true
        if (success != true) {
            acceptedAt = null; clearStillness()
            return "控制器未确认${if (command == "set_stop_teach") "示教停止" else "轨迹停止"}指令"
        }
        if (teachAccepted && armAccepted && acceptedAt == null) acceptedAt = now
        return null
    }
    /** Preserve the connection and lock; only stop commands are retried, never the original motion. */
    fun markUnconfirmed() {
        if (!pending) return
        unconfirmed = true
        escalate()
    }
    private fun escalate() {
        if (quick) return
        quick = true; armAccepted = false; acceptedAt = null; lastSentAt = null; clearStillness()
    }
    fun commandsDue(now: Long): List<String> {
        if (!pending) return emptyList()
        val interval = if (now - startedAt < 2_000L) 200L else 1_000L
        if (lastSentAt?.let { now - it < interval } == true) return emptyList()
        lastSentAt = now
        return listOf(RobotProtocol.stopTeach(), if (quick) RobotProtocol.quickStop() else RobotProtocol.slowStop())
    }
    fun observeArm(joints: List<Double>, sequence: Long, now: Long) {
        if (!pending || acceptedAt == null || now < acceptedAt!! || sequence <= lastSequence) return
        lastSequence = sequence
        if (joints.size != 6 || joints.any { !it.isFinite() }) { clearStillness(); return }
        if (anchor.size != 6 || joints.indices.any { abs(joints[it] - anchor[it]) > .03 }) {
            anchor = joints.toList(); stableSince = now; frames = 1
        } else frames++
        lastArmAt = now
    }
    fun observePlanning(type: String, requestedAt: Long, now: Long) {
        val accepted = acceptedAt ?: return
        if (!pending || requestedAt < accepted || now < requestedAt) return
        planningType = type; planningAt = now
        if (type != "none") { anchor = emptyList(); stableSince = null; frames = 0 }
    }
    fun ready(now: Long): Boolean {
        val since = stableSince ?: return false
        return pending && teachAccepted && armAccepted && acceptedAt != null && planningType == "none" &&
            now - planningAt in 0..1_000L && now - lastArmAt in 0..250L && frames >= 3 && now - since >= 300L
    }
    fun timeout(now: Long): String? = when {
        !pending || unconfirmed -> null
        acceptedAt == null && now - startedAt >= 1_000L -> "停止指令未获得完整确认"
        now - startedAt >= 2_000L -> "停止后未能确认机械臂停稳"
        else -> null
    }
    fun reset() {
        pending = false; unconfirmed = false; quick = false; lastSentAt = null
        teachAccepted = false; armAccepted = false; acceptedAt = null; lastSequence = -1L
        clearStillness()
    }
    private fun armStopCommand() = if (quick) "set_arm_stop" else "set_arm_slow_stop"
    private fun clearStillness() {
        planningType = ""; planningAt = -1L; stableSince = null
        anchor = emptyList(); frames = 0; lastArmAt = -1L
    }
}
