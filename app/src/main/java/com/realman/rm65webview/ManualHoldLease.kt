package com.realman.rm65webview

/** Checked by an independent thread. Delayed or old-gesture heartbeats cannot revive an expired hold. */
class ManualHoldLease {
    companion object {
        const val SIGNAL_TIMEOUT_MS = 500L
        const val MAX_HOLD_MS = 5_000L
    }
    data class Expired(val id: Int, val reason: String)
    private var id = 0
    private var startedAt = 0L
    private var heartbeatAt = 0L
    @Synchronized fun begin(id: Int, now: Long) {
        require(id > 0)
        this.id = id; startedAt = now; heartbeatAt = now
    }
    @Synchronized fun renew(id: Int, now: Long): Boolean {
        if (id != this.id || id <= 0 || now - heartbeatAt !in 0 until SIGNAL_TIMEOUT_MS ||
            now - startedAt !in 0 until MAX_HOLD_MS) return false
        heartbeatAt = now
        return true
    }
    @Synchronized fun takeExpired(now: Long): Expired? {
        if (id <= 0) return null
        val reason = when {
            now - startedAt >= MAX_HOLD_MS -> "本次按住已达 5 秒，请松手后重新按住"
            now - heartbeatAt >= SIGNAL_TIMEOUT_MS -> "按住信号超过 500ms 未更新"
            else -> return null
        }
        val expired = Expired(id, reason); id = 0
        return expired
    }
    @Synchronized fun end() { id = 0 }
}
