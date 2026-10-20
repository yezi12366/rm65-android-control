package com.realman.rm65webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

class MainActivity : Activity(), RobotTcpClient.Listener {
    companion object {
        private const val LOCAL_PAGE = "file:///android_asset/index.html"
        private const val DEFAULT_IP = "192.168.33.80"
        private const val DEFAULT_PORT = 8080
        private const val POLL_TICK_MS = 25L
        private const val IDLE_ARM_POLL_MS = 200L
        private const val ACTIVE_ARM_POLL_MS = 50L
        private const val SAFETY_POLL_MS = 500L
        private const val GRIPPER_POLL_MS = 2_000L
        private const val FRAME_POLL_MS = 5_000L
        private const val REQUEST_TIMEOUT_MS = 500L
        private const val TRAJECTORY_LIST_TIMEOUT_MS = 5_000L
        private const val ARM_FRESH_MS = 1_000L
        private const val SAFETY_FRESH_MS = 1_500L
        private const val DEFAULT_JOG_WATCHDOG_MS = 5_000L
        private const val MIN_JOG_WATCHDOG_MS = 1_000L
        private const val MAX_JOG_WATCHDOG_MS = 30_000L
        private const val DEFAULT_JOINT_LIMIT_MARGIN_DEG = 10.0
        private const val MAX_JOINT_LIMIT_MARGIN_DEG = 90.0
        private const val SHORTCUT_ID = "rm65_console"
        private const val SHORTCUT_ASK_DELAY_MS = 1_200L
        private const val ALIGN_SPEED = 10
        private const val ACTIVE_GRIPPER_POLL_MS = 150L
        private const val PLANNING_POLL_MS = 500L
    }

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = RobotTcpClient(this)
    private val actionSession = ActionSession()
    private val manualTouch = ManualTouchGuard()
    private val holdLease = ManualHoldLease()
    private val holdWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "rm65-hold-watchdog").apply { isDaemon = true }
    }
    @Volatile private var independentStopActive = false
    private var lastIndependentStopAt = 0L
    private var manualHoldId = 0
    private val gripperFlow = GripperActionFlow()
    private val gripperConnection = GripperConnectionFlow()
    private val playbackGate = PlaybackStepGate()
    private val playbackControl = PlaybackControlFlow()
    private var gripperForPlayback = false
    private var gripperCommandAccepted = false
    private var gripperPlaybackCompleted = false
    private var gripperRecordingId: String? = null
    private var recordingInterrupted = false
    private var recordingError = ""
    private var lastPlaybackFailure: JSONObject? = null
    private val diagnosticEvents = ArrayDeque<JSONObject>()
    private var lastGripperDiagnosticSignature = ""
    private lateinit var actionStore: ActionStore

    private var pageReady = false
    private var destroyed = false
    private var connected = false
    private var jogActive = false
    private var robotIp = DEFAULT_IP
    private var robotPort = DEFAULT_PORT
    private var armSnapshot: ArmSnapshot? = null
    private var safetySnapshot: SafetySnapshot? = null
    private val gripperSnapshot: GripperSnapshot? get() = gripperConnection.snapshot
    private var jointMinLimits: List<Double>? = null
    private var jointMaxLimits: List<Double>? = null
    private val configuredJointMinLimits = MutableList<Double?>(6) { null }
    private val configuredJointMaxLimits = MutableList<Double?>(6) { null }
    private val jointLimitMargins = MutableList(6) { DEFAULT_JOINT_LIMIT_MARGIN_DEG }
    private var currentToolFrame = ""
    private var currentWorkFrame = ""
    private var currentTeachFrame: Int? = null
    private var teachFrameUpdatedAt = 0L
    private var pendingTeachFrame: Int? = null
    private var teachFrameSwitchAt = 0L
    private var awaitingTeachFrameAck = false
    private val endpointStep = EndpointStepGate()
    private val manualStop = ManualStopGate()
    private val alarmClear = AlarmClearFlow()
    private val coupledBend = CoupledBendFlow()
    private val savedResetFlow = SavedResetFlow()
    private var savedResetPose: SavedResetPose? = null
    private var armUpdatedAt = 0L
    private var safetyUpdatedAt = 0L
    private var armRequestSentAt = 0L
    private var safetyRequestSentAt = 0L
    private var gripperRequestSentAt = 0L
    private var gripperRequestPending = false
    private var planningRequestSentAt = 0L
    private var planningRequestPending = false
    private var lastPlanningType = ""
    private var planningUpdatedAt = 0L
    private var limitRequestSentAt = 0L
    private var frameRequestSentAt = 0L
    private var armRequestPending = false
    private var safetyRequestPending = false
    private var armSequence = 0L
    private var lastError = ""
    private var jogWatchdogEnabled = true
    private var jogWatchdogMs = DEFAULT_JOG_WATCHDOG_MS
    private var lastJogHeartbeatAt = 0L
    private var activeJog: ActiveJog? = null
    private var pendingWaypointCaptureSequence: Long? = null
    private var captureAfterManualStop = false
    private val dragRecording = DragRecordingFlow()
    private val dragSegmentActive: Boolean get() = dragRecording.segmentActive
    private var dragSegmentAttempt = 0
    private var dragStartJoints = emptyList<Double>()
    private var dragEndJoints = emptyList<Double>()
    private var dragMinimum = emptyList<Double>()
    private var dragMaximum = emptyList<Double>()
    private var controllerTrajectories = emptyList<TrajectoryFileInfo>()
    private var trajectoryListPending = false
    private var trajectoryListReceived = false
    private var trajectoryListSentAt = 0L
    private var trajectoryListError = ""
    private var controllerSoftwareInfo: JSONObject? = null
    private var playbackCursor: PlaybackCursor? = null
    private var playbackState = PlaybackState.IDLE
    private var awaitedCompletion: AwaitedCompletion? = null
    private var waitDeadline = 0L
    private var waitRemainingMs = 0L
    private var waitRunnable: Runnable? = null

    private data class ActiveJog(
        val mode: JogMode,
        val axis: String,
        val direction: Int
    )

    private enum class PlaybackState { IDLE, ALIGNING, RUNNING, PAUSED }
    private enum class AwaitedCompletion { ALIGN_MOVE, WAYPOINT_MOVE, DRAG_TRAJECTORY }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!connected) return
            val now = SystemClock.elapsedRealtime()
            advanceAlarmClear(now)
            advanceManualStop(now)
            handleGripperResult(gripperFlow.tick(now))
            playbackGate.timeout(now)?.let { stopPlayback(it, notify = true, error = true) }
            playbackControl.timeout(now)?.let { stopPlayback(it, notify = true, error = true) }
            advanceConfirmedPlaybackStep(now)
            if (trajectoryListPending && now - trajectoryListSentAt >= TRAJECTORY_LIST_TIMEOUT_MS) {
                handleTrajectoryListError("轨迹列表查询超时：get_trajectory_file_list 未回复，请核对控制器代际与固件是否支持轨迹文件接口。")
            }
            handleDragRecordingEvent(dragRecording.tick(now))
            if (jogActive && !endpointStep.active && jogWatchdogEnabled && now - lastJogHeartbeatAt >= jogWatchdogMs) {
                stopJog("遥控信号超过 ${jogWatchdogMs / 1_000} 秒未更新，已请求停止。", notify = true)
            }
            if (endpointStep.timedOut(now)) {
                stopJog("步进到位反馈超时，已请求停止；请检查控制器提示。", notify = true)
            }
            if (pendingTeachFrame != null && now - teachFrameSwitchAt >= 5_000L) {
                showMessage("坐标模式切换确认超时，已关闭连接；检查状态后重新连接。", true)
                client.stopAndDisconnect()
            }
            val armInterval = if (alarmClear.active || jogActive || manualStop.pending || dragSegmentActive || playbackState != PlaybackState.IDLE) {
                ACTIVE_ARM_POLL_MS
            } else IDLE_ARM_POLL_MS
            if ((!armRequestPending || now - armRequestSentAt >= REQUEST_TIMEOUT_MS) &&
                now - armRequestSentAt >= armInterval
            ) {
                client.send(RobotProtocol.currentState())
                armRequestPending = true
                armRequestSentAt = now
            }
            if ((!safetyRequestPending || now - safetyRequestSentAt >= REQUEST_TIMEOUT_MS) &&
                now - safetyRequestSentAt >= SAFETY_POLL_MS
            ) {
                client.send(RobotProtocol.allState())
                safetyRequestPending = true
                safetyRequestSentAt = now
            }
            gripperConnection.poll(now, mayRestorePower = !alarmClear.active && !manualStop.pending && !jogActive && !endpointStep.active &&
                !gripperFlow.busy && playbackState == PlaybackState.IDLE && actionSession.draft == null)
                .forEach(client::send)
            val gripperInterval = if (gripperFlow.busy) ACTIVE_GRIPPER_POLL_MS else
                if (gripperConnection.health(now).ready) 1_000L else 500L
            if ((!gripperRequestPending || now - gripperRequestSentAt >= REQUEST_TIMEOUT_MS) &&
                now - gripperRequestSentAt >= gripperInterval) {
                requestGripperState()
            }
            if ((alarmClear.active || playbackGate.pending || manualStop.pending) && (!planningRequestPending || now - planningRequestSentAt >= REQUEST_TIMEOUT_MS) &&
                now - planningRequestSentAt >= if (manualStop.pending) 200L else PLANNING_POLL_MS) {
                planningRequestPending = true
                planningRequestSentAt = now
                client.send(RobotProtocol.currentPlanning())
            }
            if (now - limitRequestSentAt >= GRIPPER_POLL_MS &&
                (jointMinLimits == null || jointMaxLimits == null)
            ) {
                if (jointMinLimits == null) client.send(RobotProtocol.jointMinLimits())
                if (jointMaxLimits == null) client.send(RobotProtocol.jointMaxLimits())
                limitRequestSentAt = now
            }
            if (now - frameRequestSentAt >= FRAME_POLL_MS) {
                client.send(RobotProtocol.currentToolFrame())
                client.send(RobotProtocol.currentWorkFrame())
                if (pendingTeachFrame == null) client.send(RobotProtocol.teachFrame())
                frameRequestSentAt = now
            }
            stopIfActiveJogUnsafe()
            stopIfPlaybackUnsafe()
            mainHandler.postDelayed(this, POLL_TICK_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        holdWatchdog.scheduleAtFixedRate({
            val now = SystemClock.elapsedRealtime()
            holdLease.takeExpired(now)?.let { expired ->
                independentStopActive = true
                onMain {
                    if (manualHoldId == expired.id && jogActive) {
                        trace("hold_expired", JSONObject().put("hold_id", expired.id).put("reason", expired.reason))
                        stopJog(expired.reason, notify = true)
                    }
                    beginStopConfirmation(quick = true)
                }
            }
            // These are stop commands only. No motion can be generated by this worker.
            if (independentStopActive && client.isConnected() && now - lastIndependentStopAt >= 200L) {
                lastIndependentStopAt = now
                client.send(RobotProtocol.stopTeach())
                client.send(RobotProtocol.quickStop())
            }
        }, 50, 50, TimeUnit.MILLISECONDS)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        setContentView(R.layout.activity_main)

        val preferences = getSharedPreferences("rm65", MODE_PRIVATE)
        savedResetPose = SavedResetPose.decode(preferences.getString("saved_reset_pose_v1", null))
        lastPlaybackFailure = runCatching {
            preferences.getString("last_playback_failure", null)?.let { JSONObject(it) }
        }.getOrNull()
        actionStore = ActionStore(preferences)
        robotIp = preferences.getString("ip", DEFAULT_IP) ?: DEFAULT_IP
        if (!preferences.getBoolean("endpoint_address_migrated", false)) {
            if (robotIp == "192.168.1.18") robotIp = DEFAULT_IP
            preferences.edit().putString("ip", robotIp).putBoolean("endpoint_address_migrated", true).apply()
        }
        robotPort = preferences.getInt("port", DEFAULT_PORT)
        jogWatchdogEnabled = preferences.getBoolean("jog_watchdog_enabled", true)
        jogWatchdogMs = preferences.getLong("jog_watchdog_ms", DEFAULT_JOG_WATCHDOG_MS)
            .coerceIn(MIN_JOG_WATCHDOG_MS, MAX_JOG_WATCHDOG_MS)
        for (index in 0..5) {
            val joint = index + 1
            configuredJointMinLimits[index] = preferences.all["joint_${joint}_control_min"]
                ?.toString()?.toDoubleOrNull()
            configuredJointMaxLimits[index] = preferences.all["joint_${joint}_control_max"]
                ?.toString()?.toDoubleOrNull()
            jointLimitMargins[index] = preferences.all["joint_${joint}_stop_margin"]
                ?.toString()?.toDoubleOrNull() ?: DEFAULT_JOINT_LIMIT_MARGIN_DEG
        }

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowContentAccess = false
            allowFileAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setSupportZoom(false)
        }
        webView.setBackgroundColor(0xFF071019.toInt())
        webView.isLongClickable = false
        webView.addJavascriptInterface(RobotBridge(), "RobotBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !request.url.toString().startsWith("file:///android_asset/")
            }
        }
        webView.loadUrl(LOCAL_PAGE)

        val shortcutPrefs = getSharedPreferences("rm65", MODE_PRIVATE)
        if (!shortcutPrefs.getBoolean("shortcut_requested", false)) {
            shortcutPrefs.edit().putBoolean("shortcut_requested", true).apply()
            mainHandler.postDelayed({ requestDesktopShortcut(manual = false) }, SHORTCUT_ASK_DELAY_MS)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val released = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                manualTouch.pointerDown(event.getPointerId(event.actionIndex))
                false
            }
            MotionEvent.ACTION_POINTER_UP -> manualTouch.pointerUp(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP -> {
                val ownerReleased = manualTouch.pointerUp(event.getPointerId(event.actionIndex))
                manualTouch.cancelAll() || ownerReleased
            }
            MotionEvent.ACTION_CANCEL -> manualTouch.cancelAll()
            else -> false
        }
        if (released && jogActive && !endpointStep.active) {
            trace("native_touch_stop", JSONObject().put("action", event.actionMasked))
            stopJog("", notify = false)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && manualTouch.cancelAll() && jogActive && !endpointStep.active) stopJog("", notify = false)
    }

    override fun onPause() {
        if (alarmClear.active) alarmClear.cancel("页面进入后台，清除结果未确认；请刷新报警检查。")
        manualTouch.cancelAll()
        stopJog("页面进入后台，已请求停止点动", notify = false)
        if (playbackState != PlaybackState.IDLE) stopPlayback("页面进入后台，已请求停止动作。", notify = false)
        super.onPause()
    }

    override fun onStop() {
        if (dragRecording.blocksNodes) {
            dragRecording.reset()
        }
        if (!manualStop.pending) client.stopAndDisconnect("页面进入后台，连接已关闭。")
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        holdLease.end()
        holdWatchdog.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        client.shutdown()
        webView.removeJavascriptInterface("RobotBridge")
        webView.destroy()
        super.onDestroy()
    }

    override fun onConnected() = onMain {
        if (!client.isConnected()) return@onMain
        connected = true
        lastError = ""
        armRequestPending = false
        safetyRequestPending = false
        armRequestSentAt = 0L
        safetyRequestSentAt = 0L
        gripperRequestSentAt = 0L
        limitRequestSentAt = 0L
        frameRequestSentAt = 0L
        gripperRequestPending = false
        planningRequestPending = false
        beginStopConfirmation(quick = true)
        gripperConnection.connect(SystemClock.elapsedRealtime())
        lastGripperDiagnosticSignature = ""
        startPolling()
        limitRequestSentAt = SystemClock.elapsedRealtime()
        client.send(RobotProtocol.jointMinLimits())
        client.send(RobotProtocol.jointMaxLimits())
        client.send(RobotProtocol.teachFrame())
        client.send(RobotProtocol.softwareInfo())
        requestControllerTrajectories()
        showMessage("连接成功，正在读取关节状态和软件限位；完成前禁止运动。", false)
        pushActionLibrary()
        pushStatus()
    }

    override fun onDisconnected(reason: String) = onMain {
        independentStopActive = false
        holdLease.end()
        captureAfterManualStop = false
        if (alarmClear.active) alarmClear.cancel("连接中断，清除结果未确认；重连后请重新检查报警。")
        if (playbackState != PlaybackState.IDLE) rememberPlaybackFailure(reason)
        if (actionSession.draft != null) {
            recordingInterrupted = true
            recordingError = "连接已中断，草稿已保留。重新连接并确认状态后点击“继续录制”；未确认的节点不会保存。"
        }
        gripperFlow.reset()
        gripperConnection.reset()
        gripperForPlayback = false
        gripperPlaybackCompleted = false
        gripperRecordingId = null
        connected = false
        jogActive = false
        activeJog = null
        finishManualHold()
        manualTouch.cancelAll()
        dragRecording.reset()
        controllerTrajectories = emptyList()
        trajectoryListPending = false
        trajectoryListReceived = false
        trajectoryListError = ""
        controllerSoftwareInfo = null
        endpointStep.reset()
        manualStop.reset()
        coupledBend.reset()
        savedResetFlow.reset()
        currentTeachFrame = null
        teachFrameUpdatedAt = 0L
        pendingTeachFrame = null
        awaitingTeachFrameAck = false
        pendingWaypointCaptureSequence = null
        resetPlayback()
        lastJogHeartbeatAt = 0L
        mainHandler.removeCallbacks(pollRunnable)
        pushStatus()
        pushRecordingStatus()
        pushPlaybackStatus()
        pushControllerTrajectories()
        showMessage(reason, lastPlaybackFailure != null)
    }

    override fun onLine(line: String, sessionId: Long) {
        val update = try {
            RobotResponseParser.parse(line)
        } catch (error: Exception) {
            onMain {
                if (sessionId != client.sessionId()) return@onMain
                trace("parse_error", JSONObject().put("error", error.message).put("line", line.take(512)))
                val command = runCatching { JSONObject(line).optString("command") }.getOrNull()
                if (connected && command == "get_gripper_state") {
                    gripperRequestPending = false
                    gripperConnection.invalidFeedback("夹爪回复无效：${error.message.orEmpty().take(120)}；正在重试")
                    pushStatus()
                }
                // Do not abort an active arm move for one malformed optional reply. Freshness guards still apply.
            }
            return
        }
        onMain {
            if (sessionId != client.sessionId() || !connected) return@onMain
            val now = SystemClock.elapsedRealtime()
            when (update) {
                is RobotUpdate.Arm -> {
                    armRequestPending = false
                    armSnapshot = update.value
                    armUpdatedAt = now
                    armSequence++
                    alarmClear.observeArm(update.value, armSequence, armRequestSentAt, now)
                    manualStop.observeArm(update.value.jointsDeg, armSequence, now)
                    advanceManualStop(now)
                    updateDragEnvelope(update.value.jointsDeg)
                    stopIfActiveJogUnsafe()
                    stopIfPlaybackUnsafe()
                    if (savedResetFlow.reached(update.value.jointsDeg, armSequence)) {
                        savedResetFlow.reset()
                        jogActive = false
                        lastJogHeartbeatAt = 0L
                        finishManualHold()
                        showMessage("已返回保存的复位姿态；夹爪保持不变。", false)
                    }
                    if (coupledBend.reached(update.value.jointsDeg, armSequence)) {
                        coupledBend.reset()
                        jogActive = false
                        lastJogHeartbeatAt = 0L
                        finishManualHold()
                        showMessage("本段联动已到位；继续弯曲请松开后重新按住。", false)
                    }
                    if (endpointStep.reached(update.value.pose, armSequence)) finishEndpointStep()
                    capturePendingRemoteWaypoint()
                    playbackGate.observeArm(update.value.jointsDeg, now)
                    advanceConfirmedPlaybackStep(now)
                }
                is RobotUpdate.Safety -> {
                    safetyRequestPending = false
                    safetySnapshot = update.value
                    safetyUpdatedAt = now
                    alarmClear.observeSafety(update.value, safetyRequestSentAt, now)
                    stopIfActiveJogUnsafe()
                    stopIfPlaybackUnsafe()
                }
                is RobotUpdate.Gripper -> {
                    gripperRequestPending = false
                    gripperConnection.observe(update.value, now)
                    val signature = "${update.value.enabled}/${update.value.online}/${update.value.error}"
                    if (signature != lastGripperDiagnosticSignature) {
                        lastGripperDiagnosticSignature = signature
                        trace("gripper_connection", gripperDiagnostics(now))
                    }
                    handleGripperResult(gripperFlow.observe(update.value, now))
                }
                is RobotUpdate.ToolVoltage -> {
                    gripperConnection.observeVoltage(update.type, now)
                    trace("tool_voltage", JSONObject().put("voltage_type", update.type))
                }
                is RobotUpdate.Planning -> {
                    planningRequestPending = false
                    lastPlanningType = update.type
                    planningUpdatedAt = now
                    alarmClear.observePlanning(update.type, planningRequestSentAt, now)
                    manualStop.observePlanning(update.type, planningRequestSentAt, now)
                    advanceManualStop(now)
                    playbackGate.observePlanning(update.type, now)
                    advanceConfirmedPlaybackStep(now)
                }
                is RobotUpdate.JointLimit -> {
                    if (update.value.degrees.size != 6) {
                        lastError = "控制器返回的关节限位数量不是 6，禁止运动。"
                        showMessage(lastError, true)
                    } else if (update.value.kind == JointLimitKind.MINIMUM) {
                        jointMinLimits = update.value.degrees
                    } else {
                        jointMaxLimits = update.value.degrees
                    }
                    if (jointMinLimits != null && jointMaxLimits != null) {
                        val limitError = validateJointLimits() ?: validateJointSafetyConfig()
                        if (limitError == null) {
                            lastError = ""
                            showMessage("关节实际限位已读取，六轴软件范围与提前停止保护已启用。", false)
                        } else {
                            lastError = limitError
                            showMessage(limitError, true)
                        }
                    }
                }
                is RobotUpdate.FrameName -> {
                    if (update.kind == FrameKind.TOOL) currentToolFrame = update.name
                    else currentWorkFrame = update.name
                }
                is RobotUpdate.TeachFrame -> {
                    if (update.type in 0..1 && !awaitingTeachFrameAck &&
                        (pendingTeachFrame == null || pendingTeachFrame == update.type)) {
                        currentTeachFrame = update.type
                        teachFrameUpdatedAt = now
                        pendingTeachFrame = null
                    }
                }
                is RobotUpdate.TrajectoryList -> {
                    trajectoryListPending = false
                    trajectoryListReceived = true
                    trajectoryListError = ""
                    controllerTrajectories = update.value
                    handleDragRecordingEvent(dragRecording.receiveList(update.value))
                    pushControllerTrajectories()
                }
                is RobotUpdate.SoftwareInfo -> {
                    controllerSoftwareInfo = update.value
                    pushControllerTrajectories()
                }
                is RobotUpdate.MotionCompletion -> handleMotionCompletion(update)
                is RobotUpdate.Acknowledgement -> {
                    trace("ack", JSONObject().put("command", update.command).put("success", update.success))
                    val clearPhase = alarmClear.phase
                    alarmClear.acknowledge(update.command, update.success, now)?.let(::handleAlarmClearEvent)
                    if (clearPhase == AlarmClearFlow.Phase.WAIT_ACK && alarmClear.phase == AlarmClearFlow.Phase.VERIFYING) {
                        // Force new read requests; pre-clear snapshots cannot confirm success.
                        armRequestPending = false; armRequestSentAt = 0L
                        safetyRequestPending = false; safetyRequestSentAt = 0L
                    }
                    if (manualStop.pending && update.command in setOf("set_stop_teach", "set_arm_slow_stop", "set_arm_stop")) {
                        val hadReceipts = manualStop.receiptsAccepted
                        val problem = manualStop.acknowledge(update.command, update.success, now)
                        if (problem != null) failManualStop(problem)
                        else if (!hadReceipts && manualStop.receiptsAccepted) { planningRequestPending = false; planningRequestSentAt = 0L }
                    }
                    if (savedResetFlow.active && update.command == "movej") {
                        savedResetFlow.acknowledge(update.success)
                        if (update.success != true) stopJog("复位指令被拒绝或确认无效，已请求停止。", notify = true)
                    }
                    if (coupledBend.active && update.command == "movej") {
                        coupledBend.acknowledge(update.success)
                        if (update.success != true) stopJog("联动指令被拒绝或确认无效，已请求停止。", notify = true)
                    }
                    if (update.command == "set_tool_voltage") gripperConnection.powerAcknowledgement(update.success, now)
                    val gripperReply = gripperFlow.acknowledge(update.command, update.success, now)
                    if (gripperFlow.accepted) gripperCommandAccepted = true
                    handleGripperResult(gripperReply)
                    val playbackProblem = playbackGate.acknowledge(update.command, update.success, now)
                    if (playbackProblem != null) stopPlayback(playbackProblem, notify = true, error = true)
                    val controlReplyExpected = playbackControl.pending == update.command
                    playbackControl.acknowledge(update.command, update.success)?.let { stopPlayback(it, notify = true, error = true) }
                    if (controlReplyExpected && update.success == true) {
                        showMessage(if (playbackControl.nativePaused) "控制器已确认暂停。" else "控制器已确认继续。", false)
                        pushPlaybackStatus()
                    }
                    advanceConfirmedPlaybackStep(now)
                    if (update.command == "get_trajectory_file_list") {
                        handleTrajectoryListError("控制器未返回有效轨迹列表：get_trajectory_file_list 回复失败或缺少 list 字段，请核对控制器代际与固件支持。")
                        return@onMain
                    }
                    val dragEvent = dragRecording.acknowledge(update.command, update.success, now)
                    handleDragRecordingEvent(dragEvent)
                    if (update.command == "set_teach_frame" && pendingTeachFrame != null) {
                        awaitingTeachFrameAck = false
                        if (update.success != true) pendingTeachFrame = null
                        client.send(RobotProtocol.teachFrame())
                    }
                    if (update.command == "set_pos_step" && endpointStep.active) {
                        endpointStep.acknowledge(update.success == true)
                        if (update.success != true) stopJog("", notify = false)
                    }
                    if (update.success != true && activeJog != null && update.command in setOf("set_pos_teach", "set_ort_teach", "set_joint_teach")) {
                        stopJog("控制器未有效确认点动指令，已请求停止。", notify = true)
                    }
                    if (update.success == false) {
                        if (activeJog != null && update.command in setOf("set_pos_teach", "set_ort_teach", "set_joint_teach")) {
                            stopJog("", notify = false)
                        }
                        if (dragEvent !is DragRecordingEvent.Failed) {
                            lastError = "机械臂拒绝命令：${update.command}"
                            showMessage(lastError, true)
                        }
                    }
                }
                is RobotUpdate.Unknown -> Unit
            }
            advanceAlarmClear(now)
            pushStatus()
        }
    }

    override fun onError(message: String) = onMain {
        if (alarmClear.active) alarmClear.cancel("通信异常，清除结果未确认：$message")
        if (jogActive) stopJog("", notify = false)
        if (playbackState != PlaybackState.IDLE) stopPlayback(message, notify = true, error = true)
        if (gripperFlow.busy) {
            gripperFlow.reset()
            gripperRecordingId = null
            gripperForPlayback = false
            if (actionSession.draft != null) recordingError = message
        }
        lastError = message
        showMessage(message, true)
        pushRecordingStatus()
        pushStatus()
    }

    private inner class RobotBridge {
        @JavascriptInterface
        fun pageReady() = onMain {
            pageReady = true
            pushStatus()
            pushActionLibrary()
            pushRecordingStatus()
            pushPlaybackStatus()
            pushControllerTrajectories()
        }

        @JavascriptInterface
        fun saveConnectionSettings(host: String, port: Int) = onMain {
            val cleanHost = host.trim()
            val error = when {
                !validHost(cleanHost) || port !in 1..65535 -> "IP 地址或端口无效。"
                (connected || client.isConnected()) && (cleanHost != robotIp || port != robotPort) ->
                    "请先断开当前连接，再修改地址。"
                else -> null
            }
            if (error == null) {
                robotIp = cleanHost
                robotPort = port
                getSharedPreferences("rm65", MODE_PRIVATE).edit()
                    .putString("ip", robotIp).putInt("port", robotPort).apply()
            }
            sendEvent(JSONObject().put("type", "connection_settings_saved").put("data", JSONObject()
                .put("success", error == null).put("error", error ?: "")))
            pushStatus()
        }

        @JavascriptInterface
        fun connect(host: String, port: Int) = onMain {
            val cleanHost = host.trim()
            if (connected || client.isConnected()) {
                showMessage("已经连接；如需修改地址，请先断开。", true)
                return@onMain
            }
            if (!validHost(cleanHost) || port !in 1..65535) {
                showMessage("IP 地址或端口无效。", true)
                return@onMain
            }
            robotIp = cleanHost
            robotPort = port
            getSharedPreferences("rm65", MODE_PRIVATE).edit()
                .putString("ip", robotIp)
                .putInt("port", robotPort)
                .apply()
            clearSnapshots()
            lastError = ""
            showMessage("正在连接 $robotIp:$robotPort ……", false)
            pushStatus()
            client.connect(robotIp, robotPort)
        }

        @JavascriptInterface
        fun disconnect() = onMain {
            stopJog("", notify = false)
            if (playbackState != PlaybackState.IDLE) stopPlayback("", notify = false)
            client.stopAndDisconnect()
        }

        @JavascriptInterface
        fun refreshAlarms() = onMain {
            if (!connected) { showMessage("请先连接机械臂。", true); return@onMain }
            armRequestPending = false; armRequestSentAt = 0L
            safetyRequestPending = false; safetyRequestSentAt = 0L
            showMessage("正在重新读取系统和关节报警。", false)
        }

        @JavascriptInterface
        fun clearAlarm(target: Int) = onMain {
            val problem = alarmClearProblem() ?: if (target !in 0..6) "报警来源无效。" else null
            if (problem != null) { showMessage(problem, true); return@onMain }
            alarmClear.begin(target, SystemClock.elapsedRealtime())
            armRequestPending = false; armRequestSentAt = 0L
            safetyRequestPending = false; safetyRequestSentAt = 0L
            planningRequestPending = false; planningRequestSentAt = 0L
            pushStatus()
        }

        @JavascriptInterface
        fun startCoupledBend(direction: Int, speed: Int, holdId: Int) = onMain {
            val problem = manualControlProblem() ?: motionProblem() ?: when {
                jogActive -> "请先停止当前运动。"
                actionSession.draft != null -> "联动试用暂不支持动作录制，请结束录制后使用。"
                else -> null
            }
            if (problem != null) {
                showMessage(problem, true)
                notifyManualHoldReleased(holdId)
                return@onMain
            }
            val prepared = runCatching {
                val lower = effectiveJointMinimum().mapIndexed { i, v -> v + jointLimitMargins[i] }
                val upper = effectiveJointMaximum().mapIndexed { i, v -> v - jointLimitMargins[i] }
                CoupledBendFlow.plan(armSnapshot!!.jointsDeg, lower, upper, direction, speed)
            }
            if (prepared.isFailure) {
                showMessage(prepared.exceptionOrNull()?.message ?: "联动参数无效。", true)
                notifyManualHoldReleased(holdId)
                return@onMain
            }
            if (!manualTouch.beginHold()) {
                showMessage("请松开后重新按住联动按钮。", true)
                notifyManualHoldReleased(holdId)
                return@onMain
            }
            manualHoldId = holdId
            holdLease.begin(holdId, SystemClock.elapsedRealtime())
            val plan = prepared.getOrThrow()
            coupledBend.begin(plan, SystemClock.elapsedRealtime())
            jogActive = true
            activeJog = null
            lastJogHeartbeatAt = SystemClock.elapsedRealtime()
            client.send(RobotProtocol.moveJTo(plan.target, plan.speed))
            val sign = if (direction > 0) "+" else "−"
            showMessage("J2/J3/J5 联动各 $sign${"%.3f".format(Locale.US, plan.travelDeg)}°，速度 ${plan.speed}%；松手停止，确认停稳后可继续。", false)
            trace("coupled_bend", JSONObject().put("target", JSONArray(plan.target)).put("speed", plan.speed))
            pushStatus()
        }

        @JavascriptInterface
        fun startJog(mode: String, axis: String, direction: Int, speed: Int, holdId: Int) = onMain {
            manualControlProblem()?.let {
                showMessage(it, true)
                return@onMain
            }
            val problem = motionProblem()
            if (problem != null) {
                showMessage(problem, true)
                return@onMain
            }
            if (direction != -1 && direction != 1) {
                showMessage("点动方向参数无效。", true)
                return@onMain
            }
            val jogMode = when (mode) {
                "joint" -> JogMode.JOINT
                "position" -> JogMode.POSITION
                "orientation" -> JogMode.ORIENTATION
                else -> null
            }
            if (jogMode == null) {
                showMessage("点动模式无效。", true)
                return@onMain
            }
            if (jogMode != JogMode.JOINT) endpointControlProblem()?.let {
                showMessage(it, true)
                return@onMain
            }
            val limitProblem = jogLimitProblem(jogMode, axis, direction)
            if (limitProblem != null) {
                showMessage(limitProblem, true)
                return@onMain
            }
            val command = try {
                RobotProtocol.jog(jogMode, axis, direction > 0, speed)
            } catch (error: IllegalArgumentException) {
                showMessage(error.message ?: "点动参数无效。", true)
                return@onMain
            }
            if (jogActive) stopJog("", notify = false)
            if (!manualTouch.beginHold()) {
                showMessage("未检测到持续按住，请松开后重新按住方向按钮。", true)
                notifyManualHoldReleased(holdId)
                return@onMain
            }
            manualHoldId = holdId
            holdLease.begin(holdId, SystemClock.elapsedRealtime())
            client.send(command)
            jogActive = true
            activeJog = ActiveJog(jogMode, axis, direction)
            lastJogHeartbeatAt = SystemClock.elapsedRealtime()
            pushStatus()
            pushRecordingStatus()
        }

        @JavascriptInterface
        fun setTeachFrame(frame: Int) = onMain {
            val problem = motionProblem()
            if (problem != null || frame !in 0..1 || jogActive || gripperFlow.busy || pendingTeachFrame != null ||
                playbackState != PlaybackState.IDLE || actionSession.draft != null) {
                showMessage(problem ?: "请在机械臂静止且没有录制或回放时切换坐标模式。", true)
                return@onMain
            }
            pendingTeachFrame = frame
            awaitingTeachFrameAck = true
            currentTeachFrame = null
            teachFrameUpdatedAt = 0L
            teachFrameSwitchAt = SystemClock.elapsedRealtime()
            client.send(RobotProtocol.setTeachFrame(frame))
            pushStatus()
        }

        @JavascriptInterface
        fun stepPosition(axis: String, direction: Int, distanceMm: Double, speed: Int) = onMain {
            val problem = manualControlProblem() ?: motionProblem() ?: endpointControlProblem() ?:
                jogLimitProblem(JogMode.POSITION, axis, direction)
            if (problem != null || jogActive || direction !in setOf(-1, 1)) {
                showMessage(problem ?: "请等待当前运动结束后再步进。", true)
                return@onMain
            }
            val command = try {
                val json = RobotProtocol.positionStep(axis, distanceMm * direction, speed)
                endpointStep.begin(armSnapshot!!.pose, distanceMm, armSequence, SystemClock.elapsedRealtime())
                json
            } catch (error: IllegalArgumentException) {
                showMessage(error.message ?: "步进参数无效。", true)
                return@onMain
            }
            jogActive = true
            activeJog = ActiveJog(JogMode.POSITION, axis, direction)
                client.send(command)
            pushStatus()
            pushRecordingStatus()
        }

        @JavascriptInterface
        fun saveResetPose() = onMain {
            val problem = manualControlProblem() ?: motionProblem() ?: when {
                jogActive -> "请停止运动后保存复位姿态。"
                actionSession.draft != null -> "请结束录制后保存复位姿态。"
                else -> null
            }
            if (problem != null) { showMessage(problem, true); return@onMain }
            val joints = armSnapshot!!.jointsDeg
            jointTargetProblem(joints)?.let { showMessage(it, true); return@onMain }
            savedResetPose = SavedResetPose(joints.toList(), System.currentTimeMillis())
            getSharedPreferences("rm65", MODE_PRIVATE).edit().putString("saved_reset_pose_v1", savedResetPose!!.toJson()).apply()
            showMessage("已将当前机械臂姿态保存为复位姿态；夹爪状态未保存。", false)
            pushStatus()
        }

        @JavascriptInterface
        fun startSavedReset(speed: Int, holdId: Int) = onMain {
            val problem = manualControlProblem() ?: motionProblem() ?: when {
                jogActive -> "请先停止当前运动。"
                actionSession.draft != null -> "请结束录制后再复位。"
                savedResetPose == null -> "尚未保存复位姿态，请先移动到需要的位置并点击保存复位。"
                else -> null
            }
            if (problem != null) {
                showMessage(problem, true)
                notifyManualHoldReleased(holdId)
                return@onMain
            }
            if (savedResetPose!!.matches(armSnapshot!!.jointsDeg)) {
                showMessage("当前已在保存的复位姿态，无需运动。", false)
                notifyManualHoldReleased(holdId)
                return@onMain
            }
            if (!manualTouch.beginHold()) {
                showMessage("请松开后重新按住复位按钮。", true)
                notifyManualHoldReleased(holdId)
                return@onMain
            }
            manualHoldId = holdId
            val started = runCatching {
                savedResetFlow.begin(armSnapshot!!.jointsDeg, savedResetPose!!,
                    effectiveJointMinimum().mapIndexed { i, v -> v + jointLimitMargins[i] },
                    effectiveJointMaximum().mapIndexed { i, v -> v - jointLimitMargins[i] },
                    speed, SystemClock.elapsedRealtime())
            }
            if (started.isFailure) {
                savedResetFlow.reset()
                finishManualHold()
                showMessage(started.exceptionOrNull()?.message ?: "复位姿态不可执行。", true)
                return@onMain
            }
            jogActive = true
            activeJog = null
            lastJogHeartbeatAt = SystemClock.elapsedRealtime()
            holdLease.begin(holdId, SystemClock.elapsedRealtime())
            client.send(RobotProtocol.moveJTo(savedResetFlow.target, started.getOrThrow()))
            showMessage("正在返回已保存的复位姿态，速度 ${started.getOrThrow()}%；松手停止，确认停稳后可继续，夹爪保持不变。", false)
            pushStatus()
        }

        @JavascriptInterface
        fun stopJog() = onMain { stopJog("", notify = false) }

        @JavascriptInterface
        fun jogHeartbeat(holdId: Int) = onMain {
            val now = SystemClock.elapsedRealtime()
            if (jogActive && manualHoldId == holdId && holdLease.renew(holdId, now)) lastJogHeartbeatAt = now
        }

        @JavascriptInterface
        fun setJogWatchdog(enabled: Boolean, seconds: Int) = onMain {
            if (seconds !in (MIN_JOG_WATCHDOG_MS / 1_000).toInt()..(MAX_JOG_WATCHDOG_MS / 1_000).toInt()) {
                showMessage("失联停止时间只允许 1～30 秒。", true)
                return@onMain
            }
            jogWatchdogEnabled = enabled
            jogWatchdogMs = seconds * 1_000L
            if (jogActive && !coupledBend.active && !savedResetFlow.active) lastJogHeartbeatAt = SystemClock.elapsedRealtime()
            getSharedPreferences("rm65", MODE_PRIVATE).edit()
                .putBoolean("jog_watchdog_enabled", jogWatchdogEnabled)
                .putLong("jog_watchdog_ms", jogWatchdogMs)
                .apply()
            showMessage(
                if (enabled) "失联自动停止已开启：$seconds 秒。" else "兼容失联设置已关闭；固定的 500ms 按住保护始终开启。",
                false
            )
            pushStatus()
        }

        @JavascriptInterface
        fun setJointSafetyConfig(configJson: String) = onMain {
            val parsed = try {
                val config = JSONObject(configJson)
                Triple(
                    config.getJSONArray("minimum").toDoubleList(),
                    config.getJSONArray("maximum").toDoubleList(),
                    config.getJSONArray("margin").toDoubleList()
                )
            } catch (error: Exception) {
                showMessage("关节自由度配置格式无效。", true)
                return@onMain
            }
            val problem = validateJointSafetyConfig(parsed.first, parsed.second, parsed.third)
            if (problem != null) {
                showMessage(problem, true)
                return@onMain
            }
            val editor = getSharedPreferences("rm65", MODE_PRIVATE).edit()
            for (index in 0..5) {
                configuredJointMinLimits[index] = parsed.first[index]
                configuredJointMaxLimits[index] = parsed.second[index]
                jointLimitMargins[index] = parsed.third[index]
                val joint = index + 1
                editor.putString("joint_${joint}_control_min", parsed.first[index].toString())
                editor.putString("joint_${joint}_control_max", parsed.second[index].toString())
                editor.putString("joint_${joint}_stop_margin", parsed.third[index].toString())
            }
            editor.apply()
            lastError = ""
            showMessage("六轴软件运动范围和提前停止角度已保存。", false)
            pushStatus()
        }

        @JavascriptInterface
        fun stopMotion() = onMain {
            if (alarmClear.active) alarmClear.cancel("已取消报警清除并请求停止；请刷新检查实际报警。")
            stopJog("", notify = false)
            if (playbackState != PlaybackState.IDLE) stopPlayback("", notify = false)
            if (dragRecording.blocksNodes) {
                dragRecording.reset()
                client.send(RobotProtocol.stopDragTeach())
            }
            if (connected) {
                client.send(RobotProtocol.stopDragTrajectory())
                beginStopConfirmation(quick = true)
            }
            showMessage("已发送示教停止和受控快速停止，正在确认停稳；连接保持。", false)
            pushStatus()
            pushRecordingStatus()
        }

        @JavascriptInterface
        fun gripperOpen() = onMain { sendGripper(RobotProtocol.gripperOpen()) }

        @JavascriptInterface
        fun gripperClose() = onMain { sendGripper(RobotProtocol.gripperClose()) }

        @JavascriptInterface
        fun refreshGripper() = onMain {
            if (!connected || alarmClear.active || gripperFlow.busy || jogActive || endpointStep.active || pendingTeachFrame != null ||
                playbackState != PlaybackState.IDLE || actionSession.draft != null) {
                showMessage("请先连接并等待当前动作或录制结束，再重新检测夹爪。", true)
                return@onMain
            }
            gripperConnection.refresh(SystemClock.elapsedRealtime())
            gripperRequestSentAt = 0L
            requestGripperState()
            showMessage("正在重新读取末端供电和夹爪状态。", false)
            pushStatus()
        }

        @JavascriptInterface
        fun initializeGripper() = onMain {
            if (actionSession.draft != null || playbackState != PlaybackState.IDLE || jogActive ||
                endpointStep.active || pendingTeachFrame != null || gripperFlow.busy) {
                showMessage("请结束当前动作或录制后再启用夹爪。", true)
                return@onMain
            }
            manualControlProblem()?.let { showMessage(it, true); return@onMain }
            motionProblem()?.let { showMessage(it, true); return@onMain }
            startGripperAction(GripperNode(GripperOperation.OPEN), forPlayback = false, initialize = true)
        }

        @JavascriptInterface
        fun copyGripperDiagnostics() = onMain {
            val data = gripperDiagnostics(SystemClock.elapsedRealtime())
                .put("controller_software", controllerSoftwareInfo ?: JSONObject.NULL)
                .put("recent_events", JSONArray(diagnosticEvents.toList()))
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("夹爪诊断", data.toString(2)))
            showMessage("夹爪状态与通信诊断已复制。", false)
        }

        @JavascriptInterface
        fun startRemoteRecording(displayName: String, speed: Int) = onMain {
            startRecording(displayName, speed, RecordingType.REMOTE)
        }

        @JavascriptInterface
        fun startDragRecording(displayName: String, speed: Int) = onMain {
            startRecording(displayName, speed, RecordingType.DRAG)
        }

        @JavascriptInterface
        fun startNextDragSegment() = onMain { startDragSegment() }

        @JavascriptInterface
        fun finishDragSegment() = onMain { finishDragSegmentInternal() }

        @JavascriptInterface
        fun insertActionWait(milliseconds: Int) = onMain {
            if (jogActive || pendingWaypointCaptureSequence != null) {
                showMessage("请等待运动完成并读取实际路点，再插入等待。", true)
                return@onMain
            }
            if (gripperFlow.busy || recordingInterrupted || recordingError.isNotEmpty()) {
                showMessage("请先确认夹爪完成并恢复录制，再插入等待。", true)
                return@onMain
            }
            if (dragRecording.blocksNodes) {
                showMessage("请先结束并确认当前拖动段保存成功，再插入等待。", true)
                return@onMain
            }
            try {
                actionSession.addWait(milliseconds.toLong())
                showMessage("已加入等待 ${milliseconds / 1_000.0} 秒。", false)
                pushRecordingStatus()
            } catch (error: Exception) {
                showMessage(error.message ?: "等待节点无效。", true)
            }
        }

        @JavascriptInterface
        fun finishActionRecording() = onMain { finishRecording() }

        @JavascriptInterface
        fun cancelActionRecording() = onMain { cancelRecording() }

        @JavascriptInterface
        fun resumeRecording() = onMain {
            if (jogActive || gripperFlow.busy || pendingTeachFrame != null) {
                showMessage("请先等待手动操作完成，再恢复录制。", true)
                return@onMain
            }
            motionProblem()?.let { showMessage(it, true); return@onMain }
            if (actionSession.draft == null) return@onMain
            if (actionSession.draft?.recordingType == RecordingType.DRAG && !trajectoryListReceived) {
                showMessage("请先成功读取控制器轨迹列表。", true)
                return@onMain
            }
            recordingInterrupted = false
            recordingError = ""
            if (actionSession.draft?.recordingType == RecordingType.REMOTE) captureRemoteWaypoint(armSnapshot)
            showMessage("已恢复录制；当前未完成的拖动段需重新点击“录制下一拖动段”。", false)
            pushRecordingStatus()
            pushStatus()
        }

        @JavascriptInterface
        fun copyDiagnostics() = onMain {
            val data = lastPlaybackFailure ?: JSONObject().put("message", "暂无动作中断记录")
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("机械臂动作诊断", data.toString(2)))
            showMessage("动作中断诊断已复制。", false)
        }

        @JavascriptInterface
        fun refreshControllerTrajectories() = onMain {
            if (!connected) showMessage("请先连接机械臂。", true)
            else requestControllerTrajectories()
        }

        @JavascriptInterface
        fun importControllerTrajectory(controllerName: String, displayName: String, speed: Int) = onMain {
            importControllerTrajectoryInternal(controllerName, displayName, speed)
        }

        @JavascriptInterface
        fun runAction(actionId: String, speed: Int) = onMain { startPlayback(actionId, speed) }

        @JavascriptInterface
        fun pauseAction() = onMain { pausePlayback() }

        @JavascriptInterface
        fun resumeAction() = onMain { resumePlayback() }

        @JavascriptInterface
        fun stopAction() = onMain { stopPlayback("已请求停止动作，正在确认停稳。", notify = true) }

        @JavascriptInterface
        fun renameAction(actionId: String, displayName: String) = onMain {
            if (displayName.trim().length > 80) {
                showMessage("动作名称不能超过80个字符。", true)
            } else if (actionStore.rename(actionId, displayName, System.currentTimeMillis())) {
                pushActionLibrary()
                showMessage("动作已重命名。", false)
            } else showMessage("动作不存在或名称为空。", true)
        }

        @JavascriptInterface
        fun deleteAction(actionId: String) = onMain { deleteActionInternal(actionId) }

        @JavascriptInterface
        fun addDesktopShortcut() = onMain { requestDesktopShortcut(manual = true) }
    }

    private fun requestDesktopShortcut(manual: Boolean) {
        val manager = getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            if (manual) showMessage("当前桌面不支持固定快捷方式，无法添加图标。", true)
            return
        }
        if (manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }) {
            if (manual) showMessage("桌面图标已存在。", false)
            return
        }
        val intent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        val shortcutLabel = applicationInfo.loadLabel(packageManager).toString()
        val info = ShortcutInfo.Builder(this, SHORTCUT_ID)
            .setShortLabel(shortcutLabel)
            .setLongLabel(shortcutLabel)
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        val accepted = manager.requestPinShortcut(info, null)
        if (manual) {
            showMessage(if (accepted) "已发起添加请求，请在桌面弹窗中确认。" else "添加桌面图标失败。", !accepted)
        }
    }

    private fun startRecording(displayName: String, speed: Int, type: RecordingType) {
        if (gripperFlow.busy || jogActive) {
            showMessage("请先等待当前手动动作完成，再开始录制。", true)
            return
        }
        val cleanName = displayName.trim()
        if (cleanName.isBlank()) {
            showMessage("请输入动作名称。", true)
            return
        }
        if (cleanName.length > 80) {
            showMessage("动作名称不能超过80个字符。", true)
            return
        }
        if (speed !in 1..RobotProtocol.MAX_DEMO_SPEED) {
            showMessage("动作速度必须为1～${RobotProtocol.MAX_DEMO_SPEED}%。", true)
            return
        }
        if (jogActive || pendingTeachFrame != null || actionSession.draft != null || playbackState != PlaybackState.IDLE) {
            showMessage("已有录制或动作执行正在进行。", true)
            return
        }
        motionProblem()?.let {
            showMessage(it, true)
            return
        }
        if (type == RecordingType.DRAG && (!trajectoryListReceived || trajectoryListPending || trajectoryListError.isNotEmpty())) {
            showMessage(trajectoryListError.ifBlank { "尚未确认控制器支持轨迹文件接口，请先刷新并成功读取轨迹列表。" }, true)
            return
        }
        val now = System.currentTimeMillis()
        val id = "A$now"
        actionSession.begin(id, cleanName, type, speed, now)
        recordingInterrupted = false
        recordingError = ""
        dragSegmentAttempt = 0
        if (type == RecordingType.REMOTE) {
            captureRemoteWaypoint(armSnapshot)
            showMessage("遥控录制已开始；每次松开移动按钮后自动保存实际路点。", false)
        } else {
            startDragSegment()
        }
        pushRecordingStatus()
    }

    private fun startDragSegment() {
        if (gripperFlow.busy || recordingInterrupted || recordingError.isNotEmpty()) {
            showMessage("请先完成夹爪动作并恢复录制。", true)
            return
        }
        val draft = actionSession.draft
        if (draft == null || draft.recordingType != RecordingType.DRAG) {
            showMessage("请先新建拖动录制。", true)
            return
        }
        if (dragRecording.blocksNodes) {
            showMessage("当前拖动段仍在录制或保存。", true)
            return
        }
        motionProblem()?.let {
            showMessage(it, true)
            return
        }
        val joints = armSnapshot?.jointsDeg.orEmpty()
        if (joints.size != 6) {
            showMessage("当前六轴位置不可用，无法开始拖动录制。", true)
            return
        }
        dragStartJoints = joints.toList()
        dragEndJoints = joints.toList()
        dragMinimum = joints.toList()
        dragMaximum = joints.toList()
        dragSegmentAttempt++
        handleDragRecordingEvent(dragRecording.start("${draft.id}S$dragSegmentAttempt", SystemClock.elapsedRealtime()))
        showMessage("正在请求控制器开始拖动录制，请等待确认……", false)
        pushRecordingStatus()
    }

    private fun finishDragSegmentInternal() {
        val draft = actionSession.draft
        if (draft == null || draft.recordingType != RecordingType.DRAG || dragRecording.phase != DragRecordingPhase.RECORDING) {
            showMessage("当前没有正在录制的拖动轨迹段。", true)
            return
        }
        handleDragRecordingEvent(dragRecording.finish(false, SystemClock.elapsedRealtime()))
        showMessage("正在结束并保存控制器拖动轨迹……", false)
        pushRecordingStatus()
    }

    private fun finishRecording() {
        if (jogActive) {
            showMessage("请先结束当前运动，等待实际路点读取完成后再保存。", true)
            return
        }
        if (gripperFlow.busy || recordingInterrupted || recordingError.isNotEmpty()) {
            showMessage(recordingError.ifBlank { "夹爪动作尚未确认完成，请等待后再保存。" }, true)
            return
        }
        if (dragRecording.phase == DragRecordingPhase.RECORDING || dragRecording.saving) {
            handleDragRecordingEvent(dragRecording.finish(true, SystemClock.elapsedRealtime()))
            showMessage("正在结束拖动段，确认控制器文件保存成功后将自动保存动作……", false)
            pushRecordingStatus()
            return
        }
        if (dragRecording.blocksNodes) {
            showMessage(dragRecording.error.ifBlank { "正在等待拖动录制开始的确认，请稍后再保存。" }, true)
            return
        }
        if (pendingWaypointCaptureSequence != null) {
            showMessage("正在读取最后一个实际路点，请稍后再保存。", true)
            return
        }
        val action = try {
            actionSession.finish(System.currentTimeMillis())
        } catch (error: Exception) {
            showMessage(error.message ?: "动作内容无效。", true)
            return
        }
        actionStore.save(action)
        recordingError = ""
        pushRecordingStatus()
        pushActionLibrary()
        showMessage("动作“${action.displayName}”已保存。", false)
    }

    private fun cancelRecording() {
        if (jogActive) stopJog("", notify = false)
        gripperRecordingId = null
        recordingInterrupted = false
        recordingError = ""
        if (dragRecording.blocksNodes && connected) client.send(RobotProtocol.stopDragTeach())
        actionSession.cancel()?.nodes?.forEach { node ->
            when (node) {
                is WaypointNode -> if (connected && node.controllerPointName.isNotBlank()) {
                    client.send(RobotProtocol.deleteGlobalWaypoint(node.controllerPointName))
                }
                else -> Unit
            }
        }
        dragRecording.reset()
        pendingWaypointCaptureSequence = null
        showMessage("动作录制已取消。", false)
        pushRecordingStatus()
    }

    private fun capturePendingRemoteWaypoint() {
        val afterSequence = pendingWaypointCaptureSequence ?: return
        if (armSequence <= afterSequence) return
        pendingWaypointCaptureSequence = null
        captureRemoteWaypoint(armSnapshot)
        pushRecordingStatus()
    }

    private fun captureRemoteWaypoint(snapshot: ArmSnapshot?) {
        val draft = actionSession.draft ?: return
        if (draft.recordingType != RecordingType.REMOTE || snapshot == null) return
        val index = draft.nodes.count { it is WaypointNode } + 1
        val internalName = "${draft.id}P$index"
        val added = actionSession.captureWaypoint(
            snapshot.jointsDeg,
            internalName,
            draft.defaultSpeed
        )
        if (!added) return
        if (connected) {
            client.send(RobotProtocol.addGlobalWaypoint(
                internalName,
                snapshot.jointsDeg,
                snapshot.pose,
                currentWorkFrame,
                currentToolFrame,
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            ))
        }
    }

    private fun updateDragEnvelope(joints: List<Double>) {
        if (!dragSegmentActive || joints.size != 6) return
        dragEndJoints = joints.toList()
        dragMinimum = List(6) { minOf(dragMinimum[it], joints[it]) }
        dragMaximum = List(6) { maxOf(dragMaximum[it], joints[it]) }
    }

    private fun requestControllerTrajectories() {
        if (!connected || trajectoryListPending) return
        trajectoryListPending = true
        trajectoryListSentAt = SystemClock.elapsedRealtime()
        trajectoryListError = ""
        client.send(RobotProtocol.trajectoryFileList())
        pushControllerTrajectories()
    }

    private fun handleTrajectoryListError(message: String) {
        trajectoryListPending = false
        trajectoryListError = message
        val event = dragRecording.listFailed(message)
        if (event != null) handleDragRecordingEvent(event)
        else showMessage(message, true)
        pushControllerTrajectories()
    }

    private fun handleDragRecordingEvent(event: DragRecordingEvent?) {
        when (event) {
            null -> return
            is DragRecordingEvent.Send -> when (event.command) {
                "start_drag_teach" -> client.send(RobotProtocol.startDragTeach())
                "stop_drag_teach" -> client.send(RobotProtocol.stopDragTeach())
                "save_trajectory_file" -> client.send(RobotProtocol.saveTrajectoryFile(requireNotNull(event.name)))
                "get_trajectory_file_list" -> requestControllerTrajectories()
            }
            DragRecordingEvent.Started -> showMessage("控制器已确认开始拖动录制；结束后可直接点击“结束并保存”。", false)
            is DragRecordingEvent.Saved -> {
                try {
                    actionSession.addDragTrajectory(DragTrajectoryNode(
                        event.file.name, event.file.pointCount, dragStartJoints, dragEndJoints,
                        dragMinimum, dragMaximum, verified = true
                    ))
                    if (event.finishAction) finishRecording()
                    else showMessage("拖动轨迹段已确认保存，共 ${event.file.pointCount} 点；可添加夹爪、等待或继续录制。", false)
                } catch (error: Exception) {
                    showMessage(error.message ?: "无法加入拖动轨迹段。", true)
                }
            }
            is DragRecordingEvent.Failed -> {
                if (connected) client.send(RobotProtocol.stopDragTeach())
                lastError = event.message
                showMessage(event.message, true)
            }
        }
        pushRecordingStatus()
    }

    private fun importControllerTrajectoryInternal(controllerName: String, displayName: String, speed: Int) {
        val info = controllerTrajectories.firstOrNull { it.name == controllerName }
        val snapshot = armSnapshot
        if (info == null || snapshot == null || snapshot.jointsDeg.size != 6) {
            showMessage("控制器轨迹或当前位置不可用；请刷新列表并将机械臂置于轨迹起点。", true)
            return
        }
        if (displayName.isBlank() || displayName.trim().length > 80 ||
            speed !in 1..RobotProtocol.MAX_DEMO_SPEED
        ) {
            showMessage("动作名称或速度无效。", true)
            return
        }
        val now = System.currentTimeMillis()
        val action = ActionRecord(
            id = "A$now",
            displayName = displayName.trim(),
            recordingType = RecordingType.DRAG,
            defaultSpeed = speed,
            nodes = listOf(DragTrajectoryNode(
                controllerName,
                info.pointCount,
                snapshot.jointsDeg,
                emptyList(),
                emptyList(),
                emptyList(),
                verified = false
            )),
            createdAt = now,
            updatedAt = now
        )
        actionStore.save(action)
        pushActionLibrary()
        showMessage("已绑定控制器轨迹；当前位置已作为自动回起点目标，请确认它确实是轨迹起点。", false)
    }

    private fun startPlayback(actionId: String, requestedSpeed: Int) {
        if (gripperFlow.busy || jogActive || pendingTeachFrame != null) {
            showMessage("请先等待当前手动动作完成，再执行动作。", true)
            return
        }
        if (playbackState != PlaybackState.IDLE || actionSession.draft != null) {
            showMessage("录制或另一个动作正在进行。", true)
            return
        }
        val action = actionStore.get(actionId)
        if (action == null) {
            showMessage("动作不存在。", true)
            return
        }
        val speed = requestedSpeed.coerceIn(1, RobotProtocol.MAX_DEMO_SPEED)
        val problem = actionProblem(action)
        if (problem != null) {
            showMessage(problem, true)
            return
        }
        val start = action.nodes.firstNotNullOfOrNull { node ->
            when (node) {
                is WaypointNode -> node.jointsDeg
                is DragTrajectoryNode -> node.startJointsDeg.takeIf { it.size == 6 }
                else -> null
            }
        }
        if (start == null) {
            showMessage("动作没有可用的起点，禁止执行。", true)
            return
        }
        playbackCursor = PlaybackCursor(action.copy(defaultSpeed = speed))
        playbackState = PlaybackState.ALIGNING
        awaitedCompletion = AwaitedCompletion.ALIGN_MOVE
        playbackGate.begin("movej", MotionCompletionKind.MOVE, start, SystemClock.elapsedRealtime())
        trace("align_start", JSONObject().put("target", JSONArray(start)).put("speed", min(ALIGN_SPEED, speed)))
        client.send(RobotProtocol.moveJTo(start, min(ALIGN_SPEED, speed)))
        showMessage("正在低速移动到动作“${action.displayName}”的起点。", false)
        pushPlaybackStatus()
    }

    private fun executePlaybackNode() {
        val cursor = playbackCursor ?: return
        if (playbackState != PlaybackState.RUNNING) return
        val problem = motionProblem()
        if (problem != null) {
            stopPlayback(problem, notify = true, error = true)
            return
        }
        cursor.current?.let { trace("node_start", it.toJson()) }
        when (val node = cursor.current) {
            null -> finishPlayback()
            is WaypointNode -> {
                val nodeProblem = jointTargetProblem(node.jointsDeg)
                if (nodeProblem != null) stopPlayback(nodeProblem, notify = true, error = true)
                else {
                    awaitedCompletion = AwaitedCompletion.WAYPOINT_MOVE
                    playbackGate.begin("movej", MotionCompletionKind.MOVE, node.jointsDeg, SystemClock.elapsedRealtime())
                    client.send(RobotProtocol.moveJTo(node.jointsDeg, cursor.action.defaultSpeed))
                    pushPlaybackStatus()
                }
            }
            is DragTrajectoryNode -> {
                awaitedCompletion = AwaitedCompletion.DRAG_TRAJECTORY
                playbackGate.begin("set_run_trajectory_file", MotionCompletionKind.TRAJECTORY_FILE, emptyList(), SystemClock.elapsedRealtime())
                client.send(RobotProtocol.runTrajectoryFile(node.controllerName))
                pushPlaybackStatus()
            }
            is GripperNode -> {
                startGripperAction(node, forPlayback = true)
            }
            is WaitNode -> schedulePlaybackDelay(node.durationMs)
        }
    }

    private fun schedulePlaybackDelay(durationMs: Long) {
        val cursor = playbackCursor ?: return
        val index = cursor.position()
        waitRemainingMs = durationMs
        waitDeadline = SystemClock.elapsedRealtime() + durationMs
        val runnable = Runnable {
            if (playbackCursor !== cursor || cursor.position() != index || playbackState != PlaybackState.RUNNING) return@Runnable
            waitRunnable = null
            waitRemainingMs = 0L
            playbackCursor?.advance()
            executePlaybackNode()
        }
        waitRunnable = runnable
        mainHandler.postDelayed(runnable, durationMs)
        pushPlaybackStatus()
    }

    private fun handleMotionCompletion(update: RobotUpdate.MotionCompletion) {
        trace("completion", JSONObject().put("kind", update.kind.name).put("device", update.device).put("success", update.success))
        if (savedResetFlow.active && update.kind == MotionCompletionKind.MOVE) {
            if (update.device == 0) {
                if (!update.success) stopJog("复位未到位，已请求停止；请检查控制器故障。", notify = true)
                else savedResetFlow.complete(update.device, true, armSequence)
            }
            return
        }
        if (coupledBend.active && update.kind == MotionCompletionKind.MOVE) {
            if (update.device == 0) {
                if (!update.success) stopJog("联动未到位，已请求停止；请检查控制器故障。", notify = true)
                else coupledBend.complete(update.device, true, armSequence)
            }
            return
        }
        if (endpointStep.active && update.kind == MotionCompletionKind.MOVE) {
            if (update.device == 0) {
                if (!update.success) stopJog("步进未到位，已请求停止；请检查可达范围和控制器故障。", notify = true)
                else endpointStep.complete(update.device, true, armSequence)
            }
            return
        }
        handleGripperResult(gripperFlow.completion(update))
        playbackGate.completion(update)?.let { stopPlayback(it, notify = true, error = true) }
        advanceConfirmedPlaybackStep(SystemClock.elapsedRealtime())
        pushPlaybackStatus()
    }

    private fun advanceConfirmedPlaybackStep(now: Long) {
        if (playbackState == PlaybackState.IDLE || playbackState == PlaybackState.PAUSED) return
        if (playbackControl.pending != null) return
        if (gripperForPlayback && gripperPlaybackCompleted) {
            gripperForPlayback = false
            gripperPlaybackCompleted = false
            playbackCursor?.advance()
            executePlaybackNode()
        } else if (playbackGate.ready(now)) {
            val step = awaitedCompletion
            playbackGate.reset()
            awaitedCompletion = null
            if (step == AwaitedCompletion.ALIGN_MOVE) {
                playbackState = PlaybackState.RUNNING
                showMessage("已确认到达动作起点，开始执行动作链。", false)
            } else playbackCursor?.advance()
            executePlaybackNode()
        } else return
        pushPlaybackStatus()
    }

    private fun pausePlayback() {
        if (playbackState != PlaybackState.RUNNING) return
        if (playbackControl.pending != null) { showMessage("请先等待当前暂停/继续指令确认。", false); return }
        if (playbackGate.pending && !playbackGate.accepted) {
            stopPlayback("当前运动指令尚未确认接收，无法可靠暂停；连接保持，已请求停止并核对实际状态。", notify = true, error = true)
            return
        }
        playbackGate.pause(SystemClock.elapsedRealtime())
        waitRunnable?.let {
            mainHandler.removeCallbacks(it)
            waitRunnable = null
            waitRemainingMs = (waitDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } ?: if (playbackGate.pending && playbackGate.accepted) {
            val command = playbackControl.begin(true, awaitedCompletion == AwaitedCompletion.DRAG_TRAJECTORY, SystemClock.elapsedRealtime())
            client.send(if (command == "pause_drag_trajectory") RobotProtocol.pauseDragTrajectory() else RobotProtocol.pauseMotion())
        } else Unit
        playbackState = PlaybackState.PAUSED
        showMessage(if (playbackControl.pending != null) "正在请求暂停，请等待控制器确认。" else "已暂停后续节点；当前夹爪动作会完成。", false)
        pushPlaybackStatus()
    }

    private fun resumePlayback() {
        if (playbackState != PlaybackState.PAUSED) return
        if (playbackControl.pending != null) { showMessage("请先等待暂停确认。", false); return }
        motionProblem()?.let { stopPlayback(it, notify = true, error = true); return }
        playbackState = PlaybackState.RUNNING
        playbackGate.resume(SystemClock.elapsedRealtime())
        if (playbackCursor?.current is WaitNode) schedulePlaybackDelay(waitRemainingMs)
        else if (gripperForPlayback) advanceConfirmedPlaybackStep(SystemClock.elapsedRealtime())
        else if (playbackControl.nativePaused) {
            val command = playbackControl.begin(false, awaitedCompletion == AwaitedCompletion.DRAG_TRAJECTORY, SystemClock.elapsedRealtime())
            client.send(if (command == "continue_drag_trajectory") RobotProtocol.continueDragTrajectory() else RobotProtocol.continueMotion())
        } else advanceConfirmedPlaybackStep(SystemClock.elapsedRealtime())
        showMessage(if (playbackControl.pending != null) "正在请求继续，请等待控制器确认。" else "动作继续执行。", false)
        pushPlaybackStatus()
    }

    private fun stopPlayback(message: String, notify: Boolean, error: Boolean = false) {
        if (error && playbackState != PlaybackState.IDLE) {
            lastError = message
            rememberPlaybackFailure(message)
        }
        if (connected && client.isConnected()) {
            client.send(RobotProtocol.stopDragTrajectory())
            beginStopConfirmation(quick = error)
        }
        resetPlayback()
        if (notify && message.isNotBlank()) showMessage(message, error)
        pushPlaybackStatus()
    }

    private fun finishPlayback() {
        val name = playbackCursor?.action?.displayName.orEmpty()
        resetPlayback()
        lastPlaybackFailure = null
        getSharedPreferences("rm65", MODE_PRIVATE).edit().remove("last_playback_failure").apply()
        showMessage("动作“$name”执行完成。", false)
        pushPlaybackStatus()
    }

    private fun resetPlayback() {
        playbackGate.reset()
        playbackControl.reset()
        if (gripperForPlayback) gripperFlow.reset()
        gripperForPlayback = false
        gripperPlaybackCompleted = false
        waitRunnable?.let { mainHandler.removeCallbacks(it) }
        waitRunnable = null
        waitDeadline = 0L
        waitRemainingMs = 0L
        playbackCursor = null
        playbackState = PlaybackState.IDLE
        awaitedCompletion = null
    }

    private fun deleteActionInternal(actionId: String) {
        if (playbackCursor?.action?.id == actionId) {
            showMessage("动作正在执行，不能删除。", true)
            return
        }
        val action = actionStore.get(actionId)
        if (action == null) {
            showMessage("动作不存在。", true)
            return
        }
        if (connected) action.nodes.forEach { node ->
            when (node) {
                is WaypointNode -> if (node.controllerPointName.isNotBlank()) {
                    client.send(RobotProtocol.deleteGlobalWaypoint(node.controllerPointName))
                }
                else -> Unit
            }
        }
        actionStore.delete(actionId)
        pushActionLibrary()
        showMessage("动作“${action.displayName}”已从动作库删除。", false)
    }

    private fun sendGripper(command: String) {
        if (endpointStep.active || pendingTeachFrame != null) {
            showMessage("请等待当前步进完成后再操作夹爪。", true)
            return
        }
        if (playbackState != PlaybackState.IDLE) {
            showMessage("动作执行期间不能手动控制夹爪。", true)
            return
        }
        if (dragRecording.blocksNodes) {
            showMessage("请先结束当前拖动轨迹段，再操作夹爪。", true)
            return
        }
        if (recordingInterrupted || jogActive || pendingWaypointCaptureSequence != null) {
            showMessage("请先恢复录制并松开点动，等待实际路点读取完成，再操作夹爪。", true)
            return
        }
        val problem = motionProblem()
        if (problem != null) {
            showMessage(problem, true)
            return
        }
        val json = JSONObject(command)
        val node = when (json.getString("command")) {
            "set_gripper_release" -> GripperNode(GripperOperation.OPEN)
            "set_gripper_pick_on" -> GripperNode(GripperOperation.CLOSE)
            "set_gripper_position" -> GripperNode(GripperOperation.POSITION, json.getInt("position"))
            else -> return
        }
        startGripperAction(node, forPlayback = false)
    }

    private fun startGripperAction(node: GripperNode, forPlayback: Boolean, initialize: Boolean = false) {
        if (gripperFlow.busy) {
            showMessage(if (gripperFlow.pending == node) "夹爪正在执行同一动作，重复点击未发送也未记录。" else "请等待当前夹爪动作确认完成。", false)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val health = gripperConnection.health(now)
        val allowed = if (initialize) !forPlayback && node.operation == GripperOperation.OPEN &&
            gripperConnection.canInitialize(now) else health.ready
        if (!connected || !allowed) {
            val message = "夹爪暂不能执行：${health.label}，${health.detail}"
            if (forPlayback) stopPlayback(message, notify = true, error = true) else showMessage(message, true)
            return
        }
        gripperFlow.begin(node, now, initialize)
        gripperForPlayback = forPlayback
        gripperCommandAccepted = false
        gripperPlaybackCompleted = false
        gripperRecordingId = if (forPlayback || initialize) null else actionSession.draft?.id
        if (!forPlayback) recordingError = ""
        val command = when (node.operation) {
            GripperOperation.OPEN -> RobotProtocol.gripperOpen()
            GripperOperation.CLOSE -> RobotProtocol.gripperClose()
            GripperOperation.POSITION -> RobotProtocol.gripperPosition(requireNotNull(node.position))
        }
        trace(if (initialize) "gripper_initialize_open" else "gripper_send", node.toJson())
        client.send(command)
        requestGripperState()
        pushRecordingStatus()
        pushPlaybackStatus()
        pushStatus()
    }

    private fun requestGripperState() {
        if (!connected) return
        val now = SystemClock.elapsedRealtime()
        if (gripperRequestPending && now - gripperRequestSentAt < REQUEST_TIMEOUT_MS) return
        gripperRequestPending = true
        gripperRequestSentAt = now
        client.send(RobotProtocol.gripperState())
    }

    private fun handleGripperResult(result: GripperActionResult?) {
        when (result) {
            null -> return
            is GripperActionResult.Completed -> {
                trace("gripper_completed", result.node.toJson())
                if (gripperForPlayback) {
                    gripperPlaybackCompleted = true
                    advanceConfirmedPlaybackStep(SystemClock.elapsedRealtime())
                } else {
                    if (actionSession.draft?.id == gripperRecordingId && gripperRecordingId != null && !recordingInterrupted) {
                        actionSession.addGripper(result.node.operation, result.node.position)
                        recordingError = ""
                    }
                    gripperRecordingId = null
                    showMessage("夹爪动作已确认完成。", false)
                }
            }
            is GripperActionResult.Failed -> {
                trace("gripper_failed", JSONObject().put("reason", result.reason))
                val wasPlayback = gripperForPlayback
                val recording = gripperRecordingId != null && actionSession.draft?.id == gripperRecordingId
                gripperRecordingId = null
                if (recording) recordingError = result.reason
                if (gripperForPlayback) stopPlayback(result.reason, notify = true, error = true)
                else { lastError = result.reason; showMessage(result.reason, true) }
                gripperForPlayback = false
                // After failure, a late receipt or still-running jaw action must not contaminate a new intention.
                if (!wasPlayback && client.isConnected() && !manualStop.pending) client.stopAndDisconnect()
            }
        }
        pushRecordingStatus()
        pushPlaybackStatus()
        pushStatus()
    }

    private fun manualControlProblem(): String? = when {
        alarmClear.active -> "正在清除并核对报警，请稍候。"
        manualStop.pending -> "正在确认机械臂停稳，请稍候。"
        savedResetFlow.active -> "正在复位，请先松手停止。"
        coupledBend.active -> "联动正在执行，请先松手停止。"
        pendingTeachFrame != null -> "正在切换坐标模式，请等待控制器确认。"
        endpointStep.active -> "当前步进尚未完成，请等待到位反馈。"
        gripperFlow.busy -> "请先等待夹爪动作确认完成。"
        recordingInterrupted || recordingError.isNotEmpty() -> recordingError.ifBlank { "录制已中断，请先恢复录制。" }
        pendingWaypointCaptureSequence != null -> "正在读取实际路点，请稍后再移动。"
        playbackState != PlaybackState.IDLE -> "动作正在执行，不能手动点动。"
        actionSession.draft?.recordingType == RecordingType.DRAG -> "拖动录制期间不能使用普通点动；请完成或取消录制。"
        else -> null
    }

    private fun endpointControlProblem(): String? = when {
        pendingTeachFrame != null -> "正在切换坐标模式，请等待控制器确认。"
        currentTeachFrame == null || SystemClock.elapsedRealtime() - teachFrameUpdatedAt > 12_000L ->
            "尚未确认当前示教坐标模式，禁止末端运动。"
        else -> null
    }

    private fun motionProblem(includeStopPending: Boolean = true): String? {
        if (!connected || !client.isConnected()) return "机械臂尚未连接。"
        if (alarmClear.active) return "正在清除并核对报警，禁止运动。"
        if (includeStopPending && manualStop.pending) return if (manualStop.unconfirmed) "尚未确认停住，运动已锁定；连接保持，持续重试停止。" else "正在确认机械臂停稳，请稍候。"
        val now = SystemClock.elapsedRealtime()
        val arm = armSnapshot ?: return "尚未收到机械臂位置状态，禁止运动。"
        if (now - armUpdatedAt > ARM_FRESH_MS) return "机械臂位置状态已超时，禁止运动。"
        if (arm.systemErrors.isEmpty()) return "未收到有效系统错误状态，禁止运动；请刷新报警。"
        arm.systemErrors.firstOrNull { it != 0 }?.let {
            return "系统报警：${RobotAlarms.item(it).getString("label")}（${RobotAlarms.item(it).getString("hex")}）；请查看报警。"
        }
        val safety = safetySnapshot ?: return "尚未收到关节安全状态，禁止运动。"
        if (now - safetyUpdatedAt > SAFETY_FRESH_MS) return "关节安全状态已超时，禁止运动。"
        if (!safety.ready) return "关节未全部使能或存在故障，禁止运动。"
        validateJointLimits()?.let { return it }
        validateJointSafetyConfig()?.let { return it }
        return null
    }

    private fun actionProblem(action: ActionRecord): String? {
        motionProblem()?.let { return it }
        if (action.nodes.none { it is WaypointNode || it is DragTrajectoryNode }) {
            return "动作没有机械臂运动节点。"
        }
        if (action.nodes.any { it is DragTrajectoryNode }) {
            if (!trajectoryListReceived || trajectoryListPending || trajectoryListError.isNotEmpty()) {
                return trajectoryListError.ifBlank { "请先成功读取控制器轨迹列表，再执行拖动动作。" }
            }
            ActionSafety.controllerTrajectoryProblem(action.nodes, controllerTrajectories)?.let { return it }
        }
        action.nodes.forEach { node ->
            when (node) {
                is WaypointNode -> jointTargetProblem(node.jointsDeg)?.let { return it }
                is DragTrajectoryNode -> {
                    jointTargetProblem(node.startJointsDeg)?.let { return it }
                    if (node.verified) {
                        jointTargetProblem(node.minimumJointsDeg)?.let { return it }
                        jointTargetProblem(node.maximumJointsDeg)?.let { return it }
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun jointTargetProblem(joints: List<Double>): String? {
        if (joints.size != 6) return "动作关节数据不完整。"
        val minimum = effectiveJointMinimum()
        val maximum = effectiveJointMaximum()
        if (minimum.size != 6 || maximum.size != 6) return "软件限位尚未准备完成。"
        joints.indices.forEach { index ->
            val lower = minimum[index] + jointLimitMargins[index]
            val upper = maximum[index] - jointLimitMargins[index]
            if (joints[index] !in lower..upper) {
                return "动作的 J${index + 1}=${"%.3f".format(Locale.US, joints[index])}° 超出当前软件安全区 [$lower°, $upper°]。"
            }
        }
        return null
    }

    private fun validateJointLimits(): String? {
        val minimum = jointMinLimits ?: return "尚未读取关节实际最小限位，禁止运动。"
        val maximum = jointMaxLimits ?: return "尚未读取关节实际最大限位，禁止运动。"
        if (minimum.size != 6 || maximum.size != 6) return "关节实际限位数据不完整，禁止运动。"
        minimum.indices.forEach { index ->
            if (!minimum[index].isFinite() || !maximum[index].isFinite() ||
                maximum[index] <= minimum[index]
            ) {
                return "J${index + 1} 控制器实际限位无效，禁止运动。"
            }
        }
        return null
    }

    private fun effectiveJointMinimum(): List<Double> {
        val actual = jointMinLimits
        val resolved = List(6) { index -> configuredJointMinLimits[index] ?: actual?.getOrNull(index) }
        return if (resolved.all { it != null }) resolved.filterNotNull() else emptyList()
    }

    private fun effectiveJointMaximum(): List<Double> {
        val actual = jointMaxLimits
        val resolved = List(6) { index -> configuredJointMaxLimits[index] ?: actual?.getOrNull(index) }
        return if (resolved.all { it != null }) resolved.filterNotNull() else emptyList()
    }

    private fun validateJointSafetyConfig(
        minimum: List<Double> = effectiveJointMinimum(),
        maximum: List<Double> = effectiveJointMaximum(),
        margins: List<Double> = jointLimitMargins
    ): String? {
        if (minimum.size != 6 || maximum.size != 6 || margins.size != 6) {
            return "六轴软件运动范围尚未准备完成，禁止运动。"
        }
        val actualMinimum = jointMinLimits
        val actualMaximum = jointMaxLimits
        for (index in 0..5) {
            val min = minimum[index]
            val max = maximum[index]
            val margin = margins[index]
            if (!min.isFinite() || !max.isFinite() || !margin.isFinite()) {
                return "J${index + 1} 软件范围包含无效数值。"
            }
            if (margin < 0.0 || margin > MAX_JOINT_LIMIT_MARGIN_DEG) {
                return "J${index + 1} 提前停止角度必须在 0～${MAX_JOINT_LIMIT_MARGIN_DEG.toInt()}°。"
            }
            if (max - min <= margin * 2) {
                return "J${index + 1} 软件范围过小，必须大于两倍提前停止角度。"
            }
            if (actualMinimum != null && min < actualMinimum[index]) {
                return "J${index + 1} 软件最小值不能小于控制器实际最小限位。"
            }
            if (actualMaximum != null && max > actualMaximum[index]) {
                return "J${index + 1} 软件最大值不能大于控制器实际最大限位。"
            }
        }
        return null
    }

    private fun jogLimitProblem(mode: JogMode, axis: String, direction: Int): String? {
        validateJointLimits()?.let { return it }
        val joints = armSnapshot?.jointsDeg ?: return "尚未收到机械臂位置状态，禁止运动。"
        val minimum = effectiveJointMinimum()
        val maximum = effectiveJointMaximum()
        val margins = jointLimitMargins
        if (joints.size < 6) return "关节角度数据不完整，禁止运动。"

        if (mode == JogMode.JOINT) {
            val index = axis.toIntOrNull()?.minus(1) ?: return "关节编号无效。"
            if (index !in 0..5) return "关节编号无效。"
            val angle = joints[index]
            if (direction > 0 && angle >= maximum[index] - margins[index]) {
                return "J${index + 1} 已到正向提前停止线，只允许负向退回。"
            }
            if (direction < 0 && angle <= minimum[index] + margins[index]) {
                return "J${index + 1} 已到负向提前停止线，只允许正向退回。"
            }
            return null
        }

        val nearLimit = joints.indices.take(6).firstOrNull { index ->
            joints[index] <= minimum[index] + margins[index] ||
                joints[index] >= maximum[index] - margins[index]
        }
        return nearLimit?.let {
            "J${it + 1} 已进入限位保护区；请切换到关节模式，向远离限位方向退回后再使用笛卡尔点动。"
        }
    }

    private fun stopIfActiveJogUnsafe() {
        if (!jogActive) return
        val jog = activeJog
        val problem = motionProblem() ?: (if (savedResetFlow.active) savedResetFlow.problem(
            armSnapshot?.jointsDeg.orEmpty(),
            effectiveJointMinimum().mapIndexed { i, v -> v + jointLimitMargins[i] },
            effectiveJointMaximum().mapIndexed { i, v -> v - jointLimitMargins[i] },
            SystemClock.elapsedRealtime(), lastJogHeartbeatAt) else null) ?: (if (coupledBend.active) coupledBend.problem(
            armSnapshot?.jointsDeg.orEmpty(),
            effectiveJointMinimum().mapIndexed { i, v -> v + jointLimitMargins[i] },
            effectiveJointMaximum().mapIndexed { i, v -> v - jointLimitMargins[i] },
            SystemClock.elapsedRealtime(), lastJogHeartbeatAt) else null) ?: (if (jog != null && jog.mode != JogMode.JOINT) endpointControlProblem() else null) ?: when {
            jog != null -> jogLimitProblem(jog.mode, jog.axis, jog.direction)
            else -> null
        }
        if (problem != null) stopJog("$problem 已请求停止。", notify = true)
    }

    private fun stopIfPlaybackUnsafe() {
        if (playbackState == PlaybackState.IDLE || (playbackState == PlaybackState.PAUSED && playbackControl.pending == null)) return
        val problem = motionProblem() ?: armSnapshot?.jointsDeg?.let { joints ->
            val minimum = effectiveJointMinimum()
            val maximum = effectiveJointMaximum()
            if (minimum.size != 6 || maximum.size != 6 || joints.size != 6) {
                "动作执行期间限位数据不完整。"
            } else joints.indices.firstOrNull { index ->
                joints[index] <= minimum[index] + jointLimitMargins[index] ||
                    joints[index] >= maximum[index] - jointLimitMargins[index]
            }?.let { "动作执行接近 J${it + 1} 软件限位。" }
        }
        if (problem != null) stopPlayback("$problem 已请求停止动作。", notify = true, error = true)
    }

    private fun stopJog(message: String, notify: Boolean) {
        val hadActiveMotion = jogActive
        val wasStep = endpointStep.active
        val wasCoupled = coupledBend.active
        val wasReset = savedResetFlow.active
        savedResetFlow.reset()
        coupledBend.reset()
        finishManualHold()
        endpointStep.reset()
        jogActive = false
        activeJog = null
        lastJogHeartbeatAt = 0L
        if (wasStep && actionSession.draft != null) {
            recordingInterrupted = true
            recordingError = "步进已中断，未确认路点未记录；确认停稳并检查位置后再继续录制。"
        }
        if (hadActiveMotion && !wasStep && !wasCoupled && !wasReset && actionSession.draft?.recordingType == RecordingType.REMOTE) {
            captureAfterManualStop = true
            pendingWaypointCaptureSequence = null
        }
        if (connected && hadActiveMotion) beginStopConfirmation()
        pushStatus()
        pushRecordingStatus()
        if (notify && message.isNotBlank()) showMessage(message + " 正在确认停稳。", true)
        else if (hadActiveMotion) showMessage("已请求停止，正在确认停稳；连接保持。", false)
    }

    private fun beginStopConfirmation(quick: Boolean = false) {
        if (!connected || !client.isConnected()) return
        if (!manualStop.pending) trace("manual_stop_requested", JSONObject().put("quick", quick).put("arm_sequence", armSequence))
        if (quick) independentStopActive = true
        pendingTeachFrame = null
        awaitingTeachFrameAck = false
        manualStop.begin(SystemClock.elapsedRealtime(), quick)
        planningRequestPending = false
        planningRequestSentAt = 0L
        manualStop.commandsDue(SystemClock.elapsedRealtime()).forEach(client::send)
    }

    private fun failManualStop(reason: String) {
        val first = !manualStop.unconfirmed
        manualStop.markUnconfirmed()
        independentStopActive = true
        if (first) {
            lastError = "尚未确认机械臂停住：$reason；连接保持，已锁定运动并持续重试停止。如仍在动，请使用实体急停。"
            trace("manual_stop_unconfirmed", JSONObject().put("reason", reason))
            showMessage(lastError, true)
            pushStatus()
        }
    }

    private fun advanceManualStop(now: Long) {
        if (!manualStop.pending) return
        // Stale state or a controller fault must not close the only channel available for stop retries.
        manualStop.timeout(now)?.let(::failManualStop)
        if (manualStop.ready(now)) {
            independentStopActive = false
            manualStop.reset()
            if (lastError.startsWith("尚未确认机械臂停住")) lastError = ""
            trace("manual_stop_confirmed", JSONObject())
            if (captureAfterManualStop && actionSession.draft?.recordingType == RecordingType.REMOTE) {
                pendingWaypointCaptureSequence = armSequence
                client.send(RobotProtocol.currentState())
            }
            captureAfterManualStop = false
            showMessage("已确认停稳，连接保持；请检查机械臂状态后继续操作。", false)
            pushStatus()
        } else manualStop.commandsDue(now).forEach(client::send)
    }

    private fun notifyManualHoldReleased(holdId: Int) {
        if (holdId > 0) sendEvent(JSONObject().put("type", "manual_hold_released")
            .put("data", JSONObject().put("hold_id", holdId)))
    }

    private fun finishManualHold() {
        holdLease.end()
        manualTouch.endHold()
        val ended = manualHoldId
        manualHoldId = 0
        notifyManualHoldReleased(ended)
    }

    private fun finishEndpointStep() {
        endpointStep.reset()
        jogActive = false
        activeJog = null
        lastJogHeartbeatAt = 0L
        if (actionSession.draft?.recordingType == RecordingType.REMOTE) {
            pendingWaypointCaptureSequence = armSequence
            client.send(RobotProtocol.currentState())
        }
        showMessage("步进已到位。", false)
    }

    private fun startPolling() {
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.post(pollRunnable)
    }

    private fun clearSnapshots() {
        independentStopActive = false
        holdLease.end()
        alarmClear.reset()
        captureAfterManualStop = false
        manualStop.reset()
        savedResetFlow.reset()
        coupledBend.reset()
        gripperFlow.reset()
        gripperRequestPending = false
        planningRequestPending = false
        lastPlanningType = ""
        planningUpdatedAt = 0L
        dragRecording.reset()
        controllerTrajectories = emptyList()
        trajectoryListPending = false
        trajectoryListReceived = false
        trajectoryListError = ""
        controllerSoftwareInfo = null
        armSnapshot = null
        safetySnapshot = null
        gripperConnection.reset()
        jointMinLimits = null
        jointMaxLimits = null
        currentToolFrame = ""
        currentWorkFrame = ""
        currentTeachFrame = null
        teachFrameUpdatedAt = 0L
        pendingTeachFrame = null
        awaitingTeachFrameAck = false
        endpointStep.reset()
        armUpdatedAt = 0L
        safetyUpdatedAt = 0L
        armRequestPending = false
        safetyRequestPending = false
        armSequence = 0L
        jogActive = false
        activeJog = null
        lastJogHeartbeatAt = 0L
    }

    private fun pushActionLibrary() {
        if (!pageReady) return
        val data = JSONArray().apply { actionStore.list().forEach { put(it.toJson()) } }
        sendEvent(JSONObject().put("type", "action_library").put("data", data))
    }

    private fun pushControllerTrajectories() {
        if (!pageReady) return
        val data = JSONArray().apply {
            controllerTrajectories.forEach { item ->
                put(JSONObject()
                    .put("name", item.name)
                    .put("point_count", item.pointCount)
                    .put("created_at", item.createdAt))
            }
        }
        sendEvent(JSONObject().put("type", "controller_trajectories").put("data", data)
            .put("loading", trajectoryListPending)
            .put("received", trajectoryListReceived)
            .put("error", trajectoryListError)
            .put("software_info", controllerSoftwareInfo ?: JSONObject.NULL))
    }

    private fun pushRecordingStatus() {
        if (!pageReady) return
        val draft = actionSession.draft
        val data = if (draft == null) JSONObject.NULL else JSONObject()
            .put("id", draft.id)
            .put("display_name", draft.displayName)
            .put("recording_type", draft.recordingType.name.lowercase())
            .put("node_count", draft.nodes.size)
            .put("nodes", JSONArray().apply { draft.nodes.forEach { put(it.toJson()) } })
            .put("drag_segment_active", dragSegmentActive)
            .put("starting_drag_segment", dragRecording.phase == DragRecordingPhase.STARTING)
            .put("saving_drag_segment", dragRecording.saving)
            .put("drag_recording_error", dragRecording.error)
            .put("gripper_pending", gripperFlow.busy)
            .put("interrupted", recordingInterrupted)
            .put("recording_error", recordingError)
            .put("waiting_for_waypoint", pendingWaypointCaptureSequence != null)
        sendEvent(JSONObject().put("type", "recording").put("data", data))
    }

    private fun pushPlaybackStatus() {
        if (!pageReady) return
        val cursor = playbackCursor
        val data = JSONObject()
            .put("state", playbackState.name.lowercase())
            .put("action_id", cursor?.action?.id ?: "")
            .put("display_name", cursor?.action?.displayName ?: "")
            .put("node_index", cursor?.position() ?: 0)
            .put("node_count", cursor?.action?.nodes?.size ?: 0)
            .put("current_node", cursor?.current?.toJson() ?: JSONObject.NULL)
            .put("waiting_for", if (gripperForPlayback) "等待夹爪确认完成" else if (playbackGate.pending) "等待机械臂确认到位" else "")
            .put("last_failure", lastPlaybackFailure ?: JSONObject.NULL)
            .put("control_pending", playbackControl.pending ?: "")
        sendEvent(JSONObject().put("type", "playback").put("data", data))
    }

    private fun alarmClearProblem(): String? {
        if (!connected || !client.isConnected()) return "机械臂尚未连接。"
        if (alarmClear.active) return "正在清除并核对报警，请等待本次操作完成。"
        if (jogActive || manualStop.pending || endpointStep.active || gripperFlow.busy ||
            pendingTeachFrame != null || playbackState != PlaybackState.IDLE || actionSession.draft != null) {
            return "请先停止运动，并结束动作执行或录制，再清除报警。"
        }
        val now = SystemClock.elapsedRealtime()
        val a = armSnapshot
        val s = safetySnapshot
        if (a == null || s == null || now - armUpdatedAt !in 0..ARM_FRESH_MS ||
            now - safetyUpdatedAt !in 0..SAFETY_FRESH_MS || a.systemErrors.isEmpty() ||
            s.systemErrors.isEmpty() || s.jointErrors.size < 6) {
            return "报警状态缺失或已过期，请先刷新并取得完整状态。"
        }
        return null
    }

    private fun advanceAlarmClear(now: Long) {
        alarmClear.advance(now)?.let(::handleAlarmClearEvent)
    }

    private fun handleAlarmClearEvent(event: AlarmClearFlow.Event) {
        when (event) {
            is AlarmClearFlow.Event.Send -> {
                trace("alarm_clear_requested", JSONObject().put("target", event.target))
                client.send(if (event.target == 0) RobotProtocol.clearSystemError() else RobotProtocol.clearJointError(event.target))
            }
            is AlarmClearFlow.Event.Finished -> {
                trace("alarm_clear_result", JSONObject().put("success", event.success).put("message", event.message))
                showMessage(event.message, !event.success)
            }
        }
        pushStatus()
    }

    private fun alarmStatus(now: Long): JSONObject {
        val a = armSnapshot
        val s = safetySnapshot
        val known = connected && a != null && s != null &&
            now - armUpdatedAt in 0..ARM_FRESH_MS && now - safetyUpdatedAt in 0..SAFETY_FRESH_MS &&
            a.systemErrors.isNotEmpty() && s.systemErrors.isNotEmpty() && s.jointErrors.size >= 6
        val items = JSONArray()
        run {
            (a?.systemErrors.orEmpty() + s?.systemErrors.orEmpty()).filter { it != 0 }.distinct()
                .forEach { items.put(RobotAlarms.item(it)) }
            s?.jointErrors?.take(6)?.forEachIndexed { index, code ->
                if (code != 0) items.put(RobotAlarms.item(code, index + 1))
            }
        }
        return JSONObject().put("known", known).put("items", items)
            .put("busy", alarmClear.active).put("phase", alarmClear.phase.name.lowercase())
            .put("message", alarmClear.message).put("can_clear", alarmClearProblem() == null)
            .put("clear_block_reason", alarmClearProblem() ?: "")
            .put("arm_age_ms", if (a == null) -1 else now - armUpdatedAt)
            .put("safety_age_ms", if (s == null) -1 else now - safetyUpdatedAt)
            .put("system_codes", JSONArray(a?.systemErrors.orEmpty()))
            .put("all_state_system_codes", JSONArray(s?.systemErrors.orEmpty()))
            .put("joint_codes", JSONArray(s?.jointErrors.orEmpty()))
    }

    private fun pushStatus() {
        if (!pageReady) return
        val arm = armSnapshot
        val safety = safetySnapshot
        val gripper = gripperSnapshot
        val now = SystemClock.elapsedRealtime()
        val armAgeMs = if (armUpdatedAt == 0L) -1L else now - armUpdatedAt
        val motionBlockReason = motionProblem()
        val root = JSONObject()
            .put("connected", connected)
            .put("jog_active", jogActive)
            .put("coupled_bend_active", coupledBend.active)
            .put("manual_stop_pending", manualStop.pending)
            .put("manual_stop_unconfirmed", manualStop.unconfirmed)
            .put("hold_signal_timeout_ms", ManualHoldLease.SIGNAL_TIMEOUT_MS)
            .put("hold_max_ms", ManualHoldLease.MAX_HOLD_MS)
            .put("gripper_busy", gripperFlow.busy)
            .put("gripper_initializing", gripperFlow.isInitializing)
            .put("gripper_health", gripperDiagnostics(now))
            .put("recording_interrupted", recordingInterrupted)
            .put("step_active", endpointStep.active)
            .put("teach_frame_ready", endpointControlProblem() == null)
            .put("teach_frame_pending", pendingTeachFrame ?: JSONObject.NULL)
            .put("teach_frame", currentTeachFrame ?: JSONObject.NULL)
            .put("reset_return_active", savedResetFlow.active)
            .put("reset_pose_saved", savedResetPose != null)
            .put("reset_pose_label", savedResetPose?.let { "复位姿态：" + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it.savedAt)) } ?: "复位姿态尚未保存")
            .put("action_recording", actionSession.draft != null)
            .put("drag_recording", dragSegmentActive)
            .put("action_playback_state", playbackState.name.lowercase())
            .put("motion_ready", motionBlockReason == null)
            .put("motion_block_reason", motionBlockReason ?: "")
            .put("last_error", lastError)
            .put("alarms", alarmStatus(now))
            .put("arm_state_age_ms", armAgeMs)
            .put("arm_state_sequence", armSequence)
            .put("frames", JSONObject()
                .put("tool", currentToolFrame)
                .put("work", currentWorkFrame))
            .put("limits", JSONObject()
                .put("default_speed", 5)
                .put("max_speed", RobotProtocol.MAX_DEMO_SPEED)
                .put("jog_watchdog_enabled", jogWatchdogEnabled)
                .put("jog_watchdog_seconds", jogWatchdogMs / 1_000)
                .put("joint_limit_margin_deg", JSONArray(jointLimitMargins))
                .put("joint_min", JSONArray(jointMinLimits.orEmpty()))
                .put("joint_max", JSONArray(jointMaxLimits.orEmpty()))
                .put("control_joint_min", JSONArray(effectiveJointMinimum()))
                .put("control_joint_max", JSONArray(effectiveJointMaximum())))
            .put("robot", JSONObject().put("ip", robotIp).put("port", robotPort))
            .put("arm", if (arm == null) JSONObject.NULL else JSONObject()
                .put("joint", JSONArray(arm.jointsDeg))
                .put("pose", JSONArray(arm.pose)))
            .put("joints", JSONObject()
                .put("joint_en_flag", JSONArray(safety?.enabled.orEmpty()))
                .put("joint_err_code", JSONArray(safety?.jointErrors.orEmpty()))
                .put("system_error", JSONArray(arm?.systemErrors.orEmpty())))
            .put("gripper", if (gripper == null) JSONObject.NULL else JSONObject()
                .put("enable", gripper.enabled)
                .put("status", gripper.online)
                .put("error", gripper.error)
                .put("mode", gripper.mode)
                .put("temperature", gripper.temperature ?: JSONObject.NULL)
                .put("actpos", gripper.position)
                .put("current_force", gripper.forceGram))
        sendEvent(JSONObject().put("type", "status").put("data", root))
    }

    private fun showMessage(message: String, error: Boolean) {
        if (message.isBlank()) return
        sendEvent(JSONObject().put("type", "message").put("message", message).put("error", error))
    }

    private fun gripperDiagnostics(now: Long): JSONObject {
        val health = gripperConnection.health(now)
        val state = gripperSnapshot
        return JSONObject().put("code", health.code).put("label", health.label).put("detail", health.detail)
            .put("ready", connected && health.ready)
            .put("can_initialize", connected && gripperConnection.canInitialize(now))
            .put("age_ms", gripperConnection.age(now))
            .put("tool_voltage_type", gripperConnection.voltageType ?: JSONObject.NULL)
            .put("power_confirmed", gripperConnection.powerReady(now))
            .put("enabled", state?.enabled ?: JSONObject.NULL).put("online", state?.online ?: JSONObject.NULL)
            .put("error", state?.error ?: JSONObject.NULL).put("mode", state?.mode ?: JSONObject.NULL)
            .put("position", state?.position ?: JSONObject.NULL)
            .put("tcp_connected", client.isConnected()).put("tcp_session", client.sessionId())
    }

    private fun trace(event: String, data: JSONObject) {
        diagnosticEvents.addLast(JSONObject().put("time_ms", SystemClock.elapsedRealtime())
            .put("event", event).put("data", data))
        while (diagnosticEvents.size > 40) diagnosticEvents.removeFirst()
    }

    private fun rememberPlaybackFailure(reason: String) {
        val now = SystemClock.elapsedRealtime()
        lastPlaybackFailure = JSONObject()
            .put("recorded_at_epoch_ms", System.currentTimeMillis())
            .put("reason", reason)
            .put("action", playbackCursor?.action?.displayName ?: "")
            .put("node_index", playbackCursor?.position() ?: -1)
            .put("node", playbackCursor?.current?.toJson() ?: JSONObject.NULL)
            .put("waiting_command", playbackGate.command ?: if (gripperForPlayback) "gripper" else "")
            .put("playback_state", playbackState.name.lowercase())
            .put("control_pending", playbackControl.pending ?: "")
            .put("command_accepted", if (gripperForPlayback) gripperCommandAccepted else playbackGate.accepted)
            .put("target_joints", JSONArray(playbackGate.targetJoints))
            .put("planning_type", lastPlanningType)
            .put("planning_age_ms", if (planningUpdatedAt == 0L) -1 else now - planningUpdatedAt)
            .put("controller_software", controllerSoftwareInfo ?: JSONObject.NULL)
            .put("connected", connected).put("tcp_connected", client.isConnected())
            .put("tcp_session", client.sessionId())
            .put("arm_age_ms", if (armUpdatedAt == 0L) -1 else now - armUpdatedAt)
            .put("safety_age_ms", if (safetyUpdatedAt == 0L) -1 else now - safetyUpdatedAt)
            .put("joints", JSONArray(armSnapshot?.jointsDeg.orEmpty()))
            .put("system_errors", JSONArray(armSnapshot?.systemErrors.orEmpty()))
            .put("joint_errors", JSONArray(safetySnapshot?.jointErrors.orEmpty()))
            .put("gripper_mode", gripperSnapshot?.mode ?: -1)
            .put("gripper_error", gripperSnapshot?.error ?: -1)
            .put("recent_events", JSONArray(diagnosticEvents.toList()))
        getSharedPreferences("rm65", MODE_PRIVATE).edit().putString("last_playback_failure", lastPlaybackFailure.toString()).apply()
    }

    private fun sendEvent(event: JSONObject) {
        if (!pageReady || destroyed) return
        val encoded = JSONObject.quote(event.toString())
        webView.evaluateJavascript("window.RM65App.onNativeEvent($encoded)", null)
    }

    private fun validHost(value: String): Boolean {
        if (value.isBlank() || value.length > 253 || value.any { it.isWhitespace() }) return false
        return value.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }
    }

    private fun JSONArray.toDoubleList() = List(length()) { getDouble(it) }

    private fun onMain(action: () -> Unit) {
        if (destroyed) return
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post {
            if (!destroyed) action()
        }
    }
}
