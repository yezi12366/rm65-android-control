package com.realman.rm65webview

sealed interface GripperActionResult {
    data class Completed(val node: GripperNode) : GripperActionResult
    data class Failed(val reason: String) : GripperActionResult
}

/** One intention at a time. A command receipt alone does not mean the jaws have finished moving. */
class GripperActionFlow {
    var pending: GripperNode? = null
        private set
    private var startedAt = 0L
    private var acceptedAt: Long? = null
    private var lastFeedbackAt = 0L
    private var terminalSince: Long? = null
    private var terminalFrames = 0
    private var initializing = false

    val busy: Boolean get() = pending != null
    val accepted: Boolean get() = acceptedAt != null
    val isInitializing: Boolean get() = busy && initializing

    fun begin(node: GripperNode, now: Long, initialize: Boolean = false): Boolean {
        if (busy) return false
        require(!initialize || node.operation == GripperOperation.OPEN) { "夹爪初始化只能显式张开" }
        pending = node
        startedAt = now
        acceptedAt = null
        lastFeedbackAt = now
        terminalSince = null
        terminalFrames = 0
        initializing = initialize
        return true
    }

    fun acknowledge(command: String, success: Boolean?, now: Long): GripperActionResult? {
        val node = pending ?: return null
        val expected = when (node.operation) {
            GripperOperation.OPEN -> "set_gripper_release"
            GripperOperation.CLOSE -> "set_gripper_pick_on"
            GripperOperation.POSITION -> "set_gripper_position"
        }
        if (command != expected && command != "set_gripper") return null
        if (success != true) return fail("夹爪拒绝指令或未返回有效接收标志：$command。未记录该节点，动作链不会继续。")
        if (acceptedAt == null) {
            acceptedAt = now
            lastFeedbackAt = now
            terminalSince = null
            terminalFrames = 0
        }
        return null
    }

    fun observe(state: GripperSnapshot, now: Long): GripperActionResult? {
        val node = pending ?: return null
        lastFeedbackAt = now
        if (state.error != 0) {
            return fail("夹爪状态异常：使能=${state.enabled}，在线=${state.online}，错误码=${state.error}。未记录该节点，动作链不会继续。")
        }
        if (state.enabled != 1 || state.online != 1 || state.mode !in 1..6) {
            if (initializing && now - startedAt < INITIALIZE_TIMEOUT_MS) return null
            return fail("夹爪未返回有效在线状态：使能=${state.enabled}，在线=${state.online}，模式=${state.mode}。动作链不会继续。")
        }
        if (acceptedAt == null) return null
        initializing = false
        val terminal = when (node.operation) {
            GripperOperation.OPEN -> state.mode == 1
            GripperOperation.CLOSE -> state.mode == 2 || state.mode == 6
            GripperOperation.POSITION -> state.mode in setOf(1, 2, 3, 6) &&
                kotlin.math.abs(state.position - requireNotNull(node.position)) <= POSITION_TOLERANCE
        }
        if (!terminal) {
            terminalSince = null
            terminalFrames = 0
            return null
        }
        terminalFrames++
        val since = terminalSince ?: now.also { terminalSince = it }
        if (terminalFrames < 2 || now - since < SETTLE_MS) return null
        reset()
        return GripperActionResult.Completed(node)
    }

    fun completion(update: RobotUpdate.MotionCompletion): GripperActionResult? {
        if (!busy || update.device != 1 || update.success) return null
        return fail("控制器报告夹爪动作失败。未记录该节点，动作链不会继续。")
    }

    fun tick(now: Long): GripperActionResult? {
        if (!busy) return null
        if (acceptedAt == null && now - startedAt >= ACK_TIMEOUT_MS) {
            return fail("夹爪指令接收确认超时；无法确定执行结果，未记录该节点，请检查状态后重新连接。")
        }
        if (initializing && now - startedAt >= INITIALIZE_TIMEOUT_MS) {
            return fail("夹爪启用后仍未返回有效在线状态；请检查示教器的末端通信模式和夹爪固件。")
        }
        if (!initializing && acceptedAt != null && now - lastFeedbackAt >= FEEDBACK_TIMEOUT_MS) {
            return fail("夹爪状态超过 ${FEEDBACK_TIMEOUT_MS / 1000} 秒未更新，未确认完成，动作链已停止。")
        }
        if (now - startedAt >= OPERATION_TIMEOUT_MS) {
            return fail("夹爪在 ${OPERATION_TIMEOUT_MS / 1000} 秒内未到达目标状态，动作链已停止。")
        }
        return null
    }

    fun reset() {
        pending = null
        acceptedAt = null
        terminalSince = null
        terminalFrames = 0
        initializing = false
    }

    private fun fail(reason: String): GripperActionResult.Failed {
        reset()
        return GripperActionResult.Failed(reason)
    }

    companion object {
        const val ACK_TIMEOUT_MS = 5_000L
        const val FEEDBACK_TIMEOUT_MS = 2_000L
        const val OPERATION_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 150L
        const val POSITION_TOLERANCE = 5
        const val INITIALIZE_TIMEOUT_MS = 5_000L
    }
}
