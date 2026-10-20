package com.realman.rm65webview

/** Pause/resume receipts are independent of movement completion and continue to time out while paused. */
class PlaybackControlFlow {
    var pending: String? = null
        private set
    var nativePaused = false
        private set
    private var sentAt = 0L

    fun begin(pausing: Boolean, drag: Boolean, now: Long): String {
        check(pending == null) { "正在等待暂停或继续的确认" }
        val command = if (drag) {
            if (pausing) "pause_drag_trajectory" else "continue_drag_trajectory"
        } else if (pausing) "set_arm_pause" else "set_arm_continue"
        pending = command
        sentAt = now
        return command
    }

    fun acknowledge(command: String, success: Boolean?): String? {
        if (command != pending) return null
        pending = null
        if (success != true) return "控制器未确认 $command；已停止本次动作。"
        nativePaused = command == "set_arm_pause" || command == "pause_drag_trajectory"
        return null
    }

    fun timeout(now: Long): String? = pending?.takeIf { now - sentAt >= TIMEOUT_MS }
        ?.let { "暂停/继续指令 $it 的确认超时，已停止本次动作。" }

    fun reset() { pending = null; nativePaused = false }

    companion object { const val TIMEOUT_MS = 5_000L }
}
