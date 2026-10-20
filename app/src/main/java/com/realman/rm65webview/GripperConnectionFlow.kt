package com.realman.rm65webview

data class GripperHealth(val code: String, val label: String, val detail: String, val ready: Boolean = false)

/** Readback, recovery and freshness for an attached gripper. Never issues a jaw movement. */
class GripperConnectionFlow {
    var snapshot: GripperSnapshot? = null
        private set
    var feedbackAt: Long? = null
        private set
    var voltageType: Int? = null
        private set
    private var voltageAt: Long? = null
    private var active = false
    private var voltagePendingAt: Long? = null
    private var nextVoltageQueryAt = 0L
    private var voltageTimeouts = 0
    private var powerOnIssued = false
    private var powerIssue = ""
    private var feedbackIssue = ""

    fun connect(now: Long) { reset(); active = true; nextVoltageQueryAt = now }

    fun reset() {
        active = false
        snapshot = null
        feedbackAt = null
        voltageType = null
        voltageAt = null
        voltagePendingAt = null
        voltageTimeouts = 0
        powerOnIssued = false
        powerIssue = ""
        feedbackIssue = ""
        nextVoltageQueryAt = 0
    }

    fun refresh(now: Long) {
        if (!active) return
        // Keep the last feedback (including faults), but recheck the supply without cycling it off.
        powerOnIssued = false
        powerIssue = ""
        voltageTimeouts = 0
        voltageType = null
        voltageAt = null
        nextVoltageQueryAt = now
    }

    fun poll(now: Long, mayRestorePower: Boolean): List<String> {
        if (!active) return emptyList()
        if (voltageType != null && voltageType != 3 && !powerOnIssued && mayRestorePower) {
            powerOnIssued = true
            voltageType = null
            voltageAt = null
            voltagePendingAt = null
            nextVoltageQueryAt = now + 500
            powerIssue = "正在确认末端 24V 供电"
            return listOf(RobotProtocol.toolVoltageOn())
        }
        val sent = voltagePendingAt
        if (sent != null && now - sent >= VOLTAGE_REPLY_TIMEOUT_MS) {
            voltagePendingAt = null
            voltageTimeouts++
            if (voltageTimeouts >= 3) {
                powerIssue = "末端供电查询未回复；正在重试，请检查控制器接口支持"
                nextVoltageQueryAt = now + 5_000
            }
        }
        if (voltagePendingAt == null && now >= nextVoltageQueryAt) {
            voltagePendingAt = now
            return listOf(RobotProtocol.toolVoltageState())
        }
        return emptyList()
    }

    fun observeVoltage(type: Int, now: Long) {
        if (!active) return
        require(type in setOf(0, 2, 3))
        voltageType = type
        voltageAt = now
        voltagePendingAt = null
        voltageTimeouts = 0
        nextVoltageQueryAt = now + if (type == 3) 5_000 else 1_000
        powerIssue = if (type == 3) "" else if (powerOnIssued) "末端供电未达到 24V；请重新检测" else "等待恢复末端 24V 供电"
    }

    fun powerAcknowledgement(success: Boolean?, now: Long) {
        if (!active || !powerOnIssued) return
        if (success != true && voltageType != 3) powerIssue = "控制器未确认末端供电设置；正在读取实际电压"
        if (voltagePendingAt == null) nextVoltageQueryAt = now + 300
    }

    fun observe(state: GripperSnapshot, now: Long) {
        if (!active) return
        snapshot = state
        feedbackAt = now
        feedbackIssue = ""
    }

    fun invalidFeedback(reason: String) { if (active) feedbackIssue = reason }
    fun age(now: Long): Long = feedbackAt?.let { (now - it).coerceAtLeast(0) } ?: -1
    fun powerReady(now: Long): Boolean = active && voltageType == 3 &&
        voltageAt?.let { now - it <= POWER_FRESH_MS } == true

    fun health(now: Long): GripperHealth {
        if (!active) return GripperHealth("disconnected", "未连接", "连接机械臂后自动检测夹爪")
        val state = snapshot
        if (state != null && state.error != 0) {
            val flags = listOf(1 to "堵转", 2 to "过温", 4 to "过流", 8 to "驱动器", 16 to "内部通信")
                .filter { state.error and it.first != 0 }.joinToString("、") { it.second }
            return GripperHealth("fault", "夹爪故障", "错误码 ${state.error}：${flags.ifBlank { "请查看示教器" }}")
        }
        if (!powerReady(now)) return GripperHealth("power", "确认供电中", powerIssue.ifBlank {
            if (voltageType == 3) "末端供电反馈超时，正在重新读取" else "正在读取末端电源；不自动开合夹爪"
        })
        if (feedbackIssue.isNotEmpty()) return GripperHealth("invalid_reply", "回复异常", feedbackIssue)
        if (state == null) return GripperHealth("waiting", "等待夹爪回复", "夹爪已连接但尚未启用时，可点击“启用并张开”")
        if (age(now) > FEEDBACK_FRESH_MS) return GripperHealth("stale", "夹爪状态超时", "保留最后读数，正在重试；不代表夹爪已拔出")
        if (state.online != 1) return GripperHealth("offline", "控制器报告离线", "若夹爪一直连接，可点击“启用并张开”开启状态更新")
        if (state.enabled != 1) return GripperHealth("disabled", "夹爪未使能", "点击“启用并张开”完成首次控制")
        if (state.mode !in 1..6) return GripperHealth("uninitialized", "等待夹爪启用", "尚未返回有效工作状态，可点击“启用并张开”")
        return GripperHealth("ready", "在线", "末端 24V 已确认 · 夹爪状态正常", true)
    }

    fun canInitialize(now: Long): Boolean = powerReady(now) && !health(now).ready &&
        snapshot?.error.let { it == null || it == 0 } && snapshot?.mode !in setOf(4, 5)

    companion object {
        const val FEEDBACK_FRESH_MS = 3_000L
        const val POWER_FRESH_MS = 12_000L
        const val VOLTAGE_REPLY_TIMEOUT_MS = 1_000L
    }
}
