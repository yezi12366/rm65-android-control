package com.realman.rm65webview

class ActionSession {
    var draft: ActionDraft? = null
        private set

    fun begin(
        id: String,
        displayName: String,
        recordingType: RecordingType,
        defaultSpeed: Int,
        now: Long
    ) {
        check(draft == null) { "已有录制正在进行" }
        draft = ActionDraft(id, displayName.trim(), recordingType, defaultSpeed, now)
    }

    fun captureWaypoint(jointsDeg: List<Double>, controllerPointName: String, speed: Int): Boolean =
        requireDraft(RecordingType.REMOTE).appendWaypoint(jointsDeg, controllerPointName, speed)

    fun addDragTrajectory(node: DragTrajectoryNode) {
        requireDraft(RecordingType.DRAG).nodes += node
    }

    fun addGripper(operation: GripperOperation, position: Int? = null): Boolean {
        val current = requireNotNull(draft) { "当前没有录制" }
        val node = GripperNode(operation, position)
        if (current.nodes.lastOrNull() == node) return false
        current.nodes += node
        return true
    }

    fun addWait(durationMs: Long) {
        requireNotNull(draft) { "当前没有录制" }.nodes += WaitNode(durationMs)
    }

    fun finish(now: Long): ActionRecord {
        val current = requireNotNull(draft) { "当前没有录制" }
        require(current.nodes.any { it is WaypointNode || it is DragTrajectoryNode }) { "动作至少需要一个机械臂运动节点" }
        return current.toRecord(now).also { draft = null }
    }

    fun cancel(): ActionDraft? = draft.also { draft = null }

    private fun requireDraft(type: RecordingType): ActionDraft {
        val current = requireNotNull(draft) { "当前没有录制" }
        require(current.recordingType == type) { "当前录制模式不支持该操作" }
        return current
    }
}

class PlaybackCursor(val action: ActionRecord) {
    private var index = 0
    val current: ActionNode?
        get() = action.nodes.getOrNull(index)
    val finished: Boolean
        get() = index >= action.nodes.size

    fun advance(): ActionNode? {
        index++
        return current
    }

    fun position(): Int = index
}
