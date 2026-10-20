package com.realman.rm65webview

enum class DragRecordingPhase { IDLE, STARTING, RECORDING, STOPPING, SAVING, VERIFYING, FAILED }

sealed interface DragRecordingEvent {
    data class Send(val command: String, val name: String? = null) : DragRecordingEvent
    data object Started : DragRecordingEvent
    data class Saved(val file: TrajectoryFileInfo, val finishAction: Boolean) : DragRecordingEvent
    data class Failed(val message: String) : DragRecordingEvent
}

/** Advance only on the matching controller acknowledgement and a confirmed, non-empty file. */
class DragRecordingFlow {
    var phase = DragRecordingPhase.IDLE
        private set
    var fileName: String? = null
        private set
    var error = ""
        private set
    private var finishAction = false
    private var deadline = 0L
    private var nextListQuery = 0L

    val segmentActive: Boolean
        get() = phase in setOf(DragRecordingPhase.STARTING, DragRecordingPhase.RECORDING, DragRecordingPhase.STOPPING)
    val saving: Boolean
        get() = phase in setOf(DragRecordingPhase.STOPPING, DragRecordingPhase.SAVING, DragRecordingPhase.VERIFYING)
    val blocksNodes: Boolean
        get() = phase != DragRecordingPhase.IDLE

    fun start(name: String, now: Long): DragRecordingEvent.Send {
        check(phase == DragRecordingPhase.IDLE) { "当前拖动段尚未处理完成" }
        require(name.isNotBlank())
        fileName = name
        finishAction = false
        error = ""
        phase = DragRecordingPhase.STARTING
        deadline = now + COMMAND_TIMEOUT_MS
        return DragRecordingEvent.Send("start_drag_teach")
    }

    fun finish(saveAction: Boolean, now: Long): DragRecordingEvent.Send? {
        check(phase == DragRecordingPhase.RECORDING || saving) { "当前拖动段尚未开始或已失败" }
        finishAction = finishAction || saveAction
        if (phase != DragRecordingPhase.RECORDING) return null
        phase = DragRecordingPhase.STOPPING
        deadline = now + COMMAND_TIMEOUT_MS
        return DragRecordingEvent.Send("stop_drag_teach")
    }

    fun acknowledge(command: String, success: Boolean?, now: Long): DragRecordingEvent? {
        val expected = when (phase) {
            DragRecordingPhase.STARTING -> "start_drag_teach"
            DragRecordingPhase.STOPPING -> "stop_drag_teach"
            DragRecordingPhase.SAVING -> "save_trajectory_file"
            else -> return null
        }
        if (command != expected) return null
        if (success != true) return fail(
            if (success == false) "控制器拒绝拖动录制命令：$command。当前段未确认保存，请取消录制后检查控制器接口支持。"
            else "控制器的 $command 回复缺少有效的成功标志。当前段未确认保存，请取消录制后检查控制器版本。"
        )
        return when (phase) {
            DragRecordingPhase.STARTING -> {
                phase = DragRecordingPhase.RECORDING
                DragRecordingEvent.Started
            }
            DragRecordingPhase.STOPPING -> {
                phase = DragRecordingPhase.SAVING
                deadline = now + SAVE_TIMEOUT_MS
                DragRecordingEvent.Send("save_trajectory_file", fileName)
            }
            DragRecordingPhase.SAVING -> {
                phase = DragRecordingPhase.VERIFYING
                deadline = now + VERIFY_TIMEOUT_MS
                nextListQuery = now + LIST_RETRY_MS
                DragRecordingEvent.Send("get_trajectory_file_list")
            }
            else -> null
        }
    }

    fun receiveList(files: List<TrajectoryFileInfo>): DragRecordingEvent? {
        if (phase != DragRecordingPhase.VERIFYING) return null
        val file = files.firstOrNull { it.name == fileName } ?: return null
        if (file.pointCount <= 0) return fail("控制器轨迹 ${file.name} 没有有效轨迹点，不能保存为动作。请取消后重新录制。")
        val event = DragRecordingEvent.Saved(file, finishAction)
        reset()
        return event
    }

    fun listFailed(message: String): DragRecordingEvent? =
        if (phase == DragRecordingPhase.VERIFYING) fail(message) else null

    fun tick(now: Long): DragRecordingEvent? {
        if (phase !in setOf(DragRecordingPhase.STARTING, DragRecordingPhase.STOPPING,
                DragRecordingPhase.SAVING, DragRecordingPhase.VERIFYING)) return null
        if (now >= deadline) {
            val detail = when (phase) {
                DragRecordingPhase.STARTING -> "start_drag_teach 未回复，无法确认拖动录制已开始"
                DragRecordingPhase.STOPPING -> "stop_drag_teach 未回复，无法确认拖动录制已结束"
                DragRecordingPhase.SAVING -> "save_trajectory_file 未回复，请核对当前控制器是否支持轨迹文件保存接口"
                else -> "保存回复成功，但轨迹列表中仍找不到有效文件 $fileName"
            }
            return fail("$detail。当前段未确认保存；请取消录制后刷新列表，已保存的控制器文件会保留。")
        }
        if (phase == DragRecordingPhase.VERIFYING && now >= nextListQuery) {
            nextListQuery = now + LIST_RETRY_MS
            return DragRecordingEvent.Send("get_trajectory_file_list")
        }
        return null
    }

    fun reset() {
        phase = DragRecordingPhase.IDLE
        fileName = null
        error = ""
        finishAction = false
        deadline = 0L
        nextListQuery = 0L
    }

    private fun fail(message: String): DragRecordingEvent.Failed {
        phase = DragRecordingPhase.FAILED
        error = message
        return DragRecordingEvent.Failed(message)
    }

    companion object {
        const val COMMAND_TIMEOUT_MS = 5_000L
        const val SAVE_TIMEOUT_MS = 10_000L
        const val VERIFY_TIMEOUT_MS = 10_000L
        const val LIST_RETRY_MS = 500L
    }
}
