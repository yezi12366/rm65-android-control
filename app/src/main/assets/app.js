const state = {
  connected: false,
  jogActive: false,
  coupledBendActive: false,
  manualStopPending: false,
  alarmClearBusy: false,
  savedResetActive: false,
  resetPoseSaved: false,
  activeButton: null,
  activePointerId: null,
  controlTab: "move",
  stepMm: 0,
  stepActive: false,
  teachFrame: null,
  teachFrameReady: false,
  teachFramePending: false,
  lastStatus: null,
  speed: 5,
  speedInitialized: false,
  maxSpeed: 30,
  joints: [0, 0, 0, 0, 0, 0],
  hasJointPose: false,
  lastShownError: "",
  armFrameSequence: 0,
  armFrameSeenAt: 0,
  jogWatchdogEnabled: true,
  jogWatchdogSeconds: 5,
  jogHeartbeatTimer: null,
  actualJointMinimum: [],
  actualJointMaximum: [],
  motionReady: false,
  gripperBusy: false,
  gripperAvailable: false,
  gripperCanInitialize: false,
  recordingInterrupted: false,
  actions: [],
  controllerTrajectories: [],
  trajectoryCatalog: { loading: false, received: false, error: "", software_info: null },
  recording: null,
  playback: { state: "idle", action_id: "", display_name: "", node_index: 0, node_count: 0 },
};

const $ = (id) => document.getElementById(id);
const actionDomain = window.RM65ActionDomain;
const connectionBadge = $("connectionBadge");
const messageBar = $("messageBar");
const canvas = $("armCanvas");
const ctx = canvas.getContext("2d");
let poseViewer = null;

function nativeCall(method, ...args) {
  const bridge = window.RobotBridge;
  if (!bridge || typeof bridge[method] !== "function") {
    showMessage("未检测到 Android 控制桥；请从安卓 App 打开本页面。", true);
    return false;
  }
  try {
    bridge[method](...args);
    return true;
  } catch (error) {
    showMessage(`调用 Android 控制层失败：${error.message || error}`, true);
    return false;
  }
}

function makeControls(container, definitions) {
  definitions.forEach(({ label, mode, axis, value }) => {
    const card = document.createElement("div");
    card.className = "axis-control";
    card.innerHTML = `<header><strong>${label}</strong><span>${value}</span></header><div class="axis-buttons"></div>`;
    const buttons = card.querySelector(".axis-buttons");
    [-1, 1].forEach((direction) => {
      const button = document.createElement("button");
      button.className = "jog-button";
      button.textContent = direction < 0 ? "−" : "+";
      button.dataset.mode = mode;
      button.dataset.axis = axis;
      button.dataset.direction = direction;
      button.setAttribute("aria-label", `${label} ${direction > 0 ? "正向" : "负向"}${mode === "joint" ? value : mode === "orientation" ? "旋转" : "移动"}，按住操作`);
      bindHoldButton(button);
      buttons.appendChild(button);
    });
    container.appendChild(card);
  });
}

function bindHoldButton(button) {
  button.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    if (!state.connected || button.disabled || state.activeButton || state.jogActive || state.stepActive) return;
    if (button.dataset.mode === "position" && state.stepMm > 0) {
      startStep(button);
      return;
    }
    state.activePointerId = event.pointerId;
    button.setPointerCapture?.(event.pointerId);
    startJog(button);
  });
  ["pointerup", "pointercancel", "lostpointercapture"].forEach((name) => {
    button.addEventListener(name, stopJog);
  });
  button.addEventListener("click", (event) => {
    if (event.detail === 0 && button.dataset.mode === "position" && state.stepMm > 0 && !button.disabled) startStep(button);
  });
}

makeControls($("jointControls"), [1, 2, 3, 4, 5, 6].map((joint) => ({
  label: `J${joint}`, mode: "joint", axis: String(joint), value: [2, 3, 5].includes(joint) ? "弯曲" : "旋转",
})));
makeControls($("orientationControls"), [
  { label: "绕 X 轴", mode: "orientation", axis: "rx", value: "RX" },
  { label: "绕 Y 轴", mode: "orientation", axis: "ry", value: "RY" },
  { label: "绕 Z 轴", mode: "orientation", axis: "rz", value: "RZ" },
]);
document.querySelectorAll('.direction-button').forEach(bindHoldButton);

$("jointReadout").innerHTML = Array.from({ length: 6 }, (_, index) =>
  `<div class="joint-chip"><span>J${index + 1} °</span><strong id="joint${index + 1}">—</strong></div>`
).join("");

$("jointLimitRows").innerHTML = Array.from({ length: 6 }, (_, index) => {
  const joint = index + 1;
  return `<tr>
    <td>J${joint}</td>
    <td><span id="actualMin${joint}" class="actual-limit">—</span></td>
    <td><span id="actualMax${joint}" class="actual-limit">—</span></td>
    <td><input id="controlMin${joint}" type="number" step="0.001" aria-label="J${joint} 软件最小角度"></td>
    <td><input id="controlMax${joint}" type="number" step="0.001" aria-label="J${joint} 软件最大角度"></td>
    <td><input id="limitMargin${joint}" type="number" min="0" max="90" step="0.1" value="10" aria-label="J${joint} 提前停止角度"></td>
  </tr>`;
}).join("");

function showMessage(message, isError = false) {
  messageBar.textContent = message;
  messageBar.classList.toggle("error", isError);
}

function updateStateAgeDisplay() {
  updatePoseDataLabel();
  const output = $("stateAgeText");
  if (!state.armFrameSeenAt) {
    output.textContent = "等待首帧";
    output.classList.remove("stale");
    return;
  }
  const age = Math.max(0, performance.now() - state.armFrameSeenAt);
  output.textContent = `${Math.round(age)} ms · #${state.armFrameSequence}`;
  output.classList.toggle("stale", age >= 300);
}

function startJog(button) {
  const holdId = nextHoldId();
  if (!nativeCall(
    "startJog",
    button.dataset.mode,
    button.dataset.axis,
    Number(button.dataset.direction),
    state.speed,
    holdId,
  )) return;
  beginHeldMotion(button, holdId);
}

function startStep(button) {
  state.stepActive = true;
  setEnabled(state.connected, state.motionReady);
  if (!nativeCall("stepPosition", button.dataset.axis, Number(button.dataset.direction), state.stepMm, state.speed)) {
    state.stepActive = false;
    refreshActionControls();
  }
}

function startSavedReset(button) {
  const holdId = nextHoldId();
  if (!nativeCall("startSavedReset", state.speed, holdId)) return;
  beginHeldMotion(button, holdId, true);
  refreshActionControls();
}

function bindCoupledBend(button) {
  button.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    if (!state.connected || button.disabled || state.activeButton || state.jogActive || state.stepActive) return;
    const holdId = nextHoldId();
    state.activePointerId = event.pointerId;
    button.setPointerCapture?.(event.pointerId);
    if (nativeCall("startCoupledBend", Number(button.dataset.direction), state.speed, holdId)) {
      beginHeldMotion(button, holdId, true);
      refreshActionControls();
    }
  });
  ["pointerup", "pointercancel", "lostpointercapture"].forEach(name => button.addEventListener(name, stopJog));
}

function nextHoldId() {
  state.holdSequence = (state.holdSequence || 0) + 1;
  return state.holdSequence;
}

function beginHeldMotion(button, holdId, mandatoryHeartbeat = false) {
  state.activeHoldId = holdId;
  state.jogActive = true;
  state.activeButton?.classList.remove("active");
  state.activeButton = button;
  button.classList.add("active");
  clearInterval(state.jogHeartbeatTimer);
  nativeCall("jogHeartbeat", holdId);
  state.jogHeartbeatTimer = setInterval(() => nativeCall("jogHeartbeat", holdId), 150);
}

function stopJog(event) {
  if (event?.pointerId != null && state.activePointerId != null && event.pointerId !== state.activePointerId) return;
  const wasActive = Boolean(state.activeButton) || (state.jogActive && !state.stepActive);
  state.activeButton?.classList.remove("active");
  state.activeButton = null;
  state.activeHoldId = 0;
  if (wasActive) state.jogActive = false;
  state.activePointerId = null;
  clearInterval(state.jogHeartbeatTimer);
  state.jogHeartbeatTimer = null;
  if (wasActive) nativeCall("stopJog");
}

function finishNativeHold(data) {
  if (Number.isInteger(data?.hold_id) && data.hold_id > 0 && data.hold_id === state.activeHoldId) stopJog();
}

function stopManualMotion() {
  if (state.stepActive) nativeCall("stopJog");
  else stopJog();
}

function setEnabled(connected, motionReady) {
  const playbackActive = state.playback.state !== "idle";
  const dragRecording = state.recording?.recording_type === "drag";
  const dragSegmentActive = Boolean(state.recording?.drag_segment_active || state.recording?.saving_drag_segment || state.recording?.drag_recording_error);
  const catalogReady = state.trajectoryCatalog.received && !state.trajectoryCatalog.loading && !state.trajectoryCatalog.error;
  const gripperBusy = state.gripperBusy || Boolean(state.recording?.gripper_pending);
  const coupledHeld = state.alarmClearBusy || state.manualStopPending || state.coupledBendActive || state.savedResetActive || state.activeButton?.dataset?.coupled === "true" || state.activeButton?.id === "zeroButton";
  const manualReady = !coupledHeld && motionReady && !playbackActive && !dragRecording && !gripperBusy &&
    !state.stepActive && !state.teachFramePending && !state.recordingInterrupted && !state.recording?.interrupted && !state.recording?.recording_error && !state.recording?.waiting_for_waypoint;
  const gripperReady = !coupledHeld && motionReady && state.gripperAvailable && !playbackActive && !dragSegmentActive &&
    !gripperBusy && !state.jogActive && !state.stepActive && !state.teachFramePending && !state.recordingInterrupted && !state.recording?.interrupted && !state.recording?.recording_error && !state.recording?.waiting_for_waypoint;
  document.querySelectorAll(".jog-button").forEach((button) => {
    button.disabled = !manualReady || (button.dataset.mode !== "joint" && !state.teachFrameReady);
  });
  document.querySelectorAll(".motion-policy,.tab").forEach((button) => button.disabled = state.stepActive);
  $("teachFrameSelect").disabled = !manualReady || state.jogActive || state.teachFramePending || Boolean(state.recording);
  ["gripperOpen", "gripperClose"].forEach((id) => $(id).disabled = !gripperReady);
  const gripperSetupIdle = !coupledHeld && connected && !gripperBusy && !state.jogActive && !state.stepActive &&
    !state.teachFramePending && !playbackActive && !state.recording && !state.recordingInterrupted;
  $("gripperInitialize").disabled = !gripperSetupIdle || !motionReady || !state.gripperCanInitialize;
  $("gripperRefresh").disabled = !gripperSetupIdle;
  $("zeroButton").disabled = state.activeButton?.id === "zeroButton" ? false : !manualReady || state.jogActive || Boolean(state.recording) || !state.resetPoseSaved;
  $("saveResetButton").disabled = !manualReady || state.jogActive || Boolean(state.recording);
  ["bendNegative", "bendPositive"].forEach(id => {
    const button = $(id);
    button.disabled = state.activeButton === button ? false : !manualReady || state.jogActive || Boolean(state.recording);
  });
  $("disconnectButton").disabled = !connected;
  $("connectButton").disabled = connected;
  $("robotIp").disabled = connected;
  $("robotPort").disabled = connected;
  $("settingsDisconnect").disabled = !connected;
  $("stopButton").disabled = !connected;
  $("startRemoteRecordingButton").disabled = !motionReady || gripperBusy || coupledHeld || state.jogActive || state.stepActive || state.teachFramePending || playbackActive || Boolean(state.recording);
  $("startDragRecordingButton").disabled = !motionReady || gripperBusy || coupledHeld || state.jogActive || state.stepActive || state.teachFramePending || !catalogReady || playbackActive || Boolean(state.recording);
  $("resumeRecordingButton").disabled = !motionReady || gripperBusy || coupledHeld || state.jogActive || state.stepActive || state.teachFramePending;
  $("refreshTrajectoryButton").disabled = !connected || state.trajectoryCatalog.loading;
  $("importTrajectoryButton").disabled = !motionReady || gripperBusy || coupledHeld || state.jogActive || state.stepActive || state.teachFramePending || !catalogReady || playbackActive || Boolean(state.recording);
  document.querySelectorAll("[data-action-run]").forEach((button) => {
    button.disabled = !motionReady || gripperBusy || coupledHeld || state.jogActive || state.stepActive || state.teachFramePending || playbackActive || Boolean(state.recording);
  });
}

function updateSpeedControl() {
  state.speed = Math.max(1, Math.min(state.maxSpeed, Math.round(state.speed)));
  $("speedValue").textContent = `${state.speed}%`;
  $("speedMinus").disabled = state.speed <= 1;
  $("speedPlus").disabled = state.speed >= state.maxSpeed;
}

function applyDirectionalLimitButtons(data, motionReady) {
  if (state.manualStopPending || state.coupledBendActive || state.savedResetActive || state.activeButton?.id === "zeroButton" || state.activeButton?.dataset?.coupled === "true" || !motionReady || state.stepActive || state.playback.state !== "idle" ||
      state.gripperBusy || state.recording?.gripper_pending || state.teachFramePending ||
      state.recordingInterrupted || state.recording?.interrupted || state.recording?.recording_error ||
      state.recording?.recording_type === "drag" || state.recording?.waiting_for_waypoint) return;
  const joints = data.arm?.joint || [];
  const minimum = data.limits?.control_joint_min || [];
  const maximum = data.limits?.control_joint_max || [];
  const margins = data.limits?.joint_limit_margin_deg || [];
  if (joints.length < 6 || minimum.length < 6 || maximum.length < 6 || margins.length < 6) return;

  document.querySelectorAll('.jog-button[data-mode="joint"]').forEach((button) => {
    const index = Number(button.dataset.axis) - 1;
    const direction = Number(button.dataset.direction);
    const angle = Number(joints[index]);
    button.disabled = direction > 0
      ? angle >= Number(maximum[index]) - Number(margins[index])
      : angle <= Number(minimum[index]) + Number(margins[index]);
  });

  const cartesianBlocked = joints.some((value, index) =>
    Number(value) <= Number(minimum[index]) + Number(margins[index]) ||
    Number(value) >= Number(maximum[index]) - Number(margins[index])
  );
  document.querySelectorAll('.jog-button:not([data-mode="joint"])').forEach((button) => {
    button.disabled = cartesianBlocked || !state.teachFrameReady;
  });
}

function syncConnectionEndpoint(data) {
  if ($("settingsOverlay").classList.contains("hidden")) {
    $("robotIp").value = data.robot?.ip || $("robotIp").value;
    $("robotPort").value = data.robot?.port || $("robotPort").value;
  }
  $("robotAddress").textContent = `${data.robot?.ip || $("robotIp").value}:${data.robot?.port || $("robotPort").value}`;
}

function finishConnectionSettingsSave(result) {
  $("settingsSave").disabled = false;
  if (result.success) closeSettings(false);
  else $("settingsError").textContent = result.error;
}

function updateStatus(data) {
  state.lastStatus = data;
  state.connected = Boolean(data.connected);
  state.coupledBendActive = Boolean(data.coupled_bend_active);
  state.manualStopPending = Boolean(data.manual_stop_pending);
  state.alarmClearBusy = Boolean(data.alarms?.busy);
  state.savedResetActive = Boolean(data.reset_return_active);
  state.resetPoseSaved = Boolean(data.reset_pose_saved);
  $("resetPoseLabel").textContent = `${data.reset_pose_label || "复位姿态尚未保存"} · 速度跟随顶部设置 · 夹爪单独控制`;
  state.jogActive = Boolean(data.jog_active);
  state.gripperBusy = Boolean(data.gripper_busy);
  state.recordingInterrupted = Boolean(data.recording_interrupted);
  state.stepActive = Boolean(data.step_active);
  state.teachFrame = data.teach_frame === 0 || data.teach_frame === 1 ? data.teach_frame : null;
  state.teachFrameReady = Boolean(data.teach_frame_ready);
  state.teachFramePending = data.teach_frame_pending === 0 || data.teach_frame_pending === 1;
  updateEndpointLabels();
  state.maxSpeed = Math.max(1, Number(data.limits?.max_speed) || 30);
  state.jogWatchdogEnabled = data.limits?.jog_watchdog_enabled !== false;
  state.jogWatchdogSeconds = Math.max(1, Number(data.limits?.jog_watchdog_seconds) || 5);
  state.actualJointMinimum = data.limits?.joint_min || [];
  state.actualJointMaximum = data.limits?.joint_max || [];
  if (!state.speedInitialized) {
    state.speed = Number(data.limits?.default_speed) || 5;
    state.speedInitialized = true;
  }
  updateSpeedControl();
  $("watchdogText").textContent = "按住信号 0.5 秒 · 单次 ≤5 秒";
  const controlMinimum = data.limits?.control_joint_min || [];
  const controlMaximum = data.limits?.control_joint_max || [];
  const margins = data.limits?.joint_limit_margin_deg || [];
  for (let index = 0; index < 6; index++) {
    const joint = index + 1;
    $(`actualMin${joint}`).textContent = state.actualJointMinimum[index] == null ? "—" : Number(state.actualJointMinimum[index]).toFixed(3);
    $(`actualMax${joint}`).textContent = state.actualJointMaximum[index] == null ? "—" : Number(state.actualJointMaximum[index]).toFixed(3);
  }
  if ($("settingsOverlay").classList.contains("hidden")) {
    $("watchdogEnabled").checked = state.jogWatchdogEnabled;
    $("watchdogSeconds").value = String(state.jogWatchdogSeconds);
    $("watchdogSeconds").disabled = !state.jogWatchdogEnabled;
    for (let index = 0; index < 6; index++) {
      const joint = index + 1;
      $(`controlMin${joint}`).value = controlMinimum[index] == null ? "" : Number(controlMinimum[index]).toFixed(3);
      $(`controlMax${joint}`).value = controlMaximum[index] == null ? "" : Number(controlMaximum[index]).toFixed(3);
      if (margins[index] != null) $(`limitMargin${joint}`).value = Number(margins[index]).toFixed(1);
    }
  } else {
    for (let index = 0; index < 6; index++) {
      const joint = index + 1;
      if ($(`controlMin${joint}`).value === "" && controlMinimum[index] != null) {
        $(`controlMin${joint}`).value = Number(controlMinimum[index]).toFixed(3);
      }
      if ($(`controlMax${joint}`).value === "" && controlMaximum[index] != null) {
        $(`controlMax${joint}`).value = Number(controlMaximum[index]).toFixed(3);
      }
    }
  }
  syncConnectionEndpoint(data);
  const workFrame = data.frames?.work || "未知";
  const toolFrame = data.frames?.tool || "未知";
  $("frameText").textContent = `${workFrame} / ${toolFrame}`;
  connectionBadge.className = `badge ${data.connected ? "online" : "offline"}`;
  connectionBadge.innerHTML = `<i></i>${data.connected ? "已连接" : "未连接"}`;
  if (data.action_playback_state && data.action_playback_state !== "idle") {
    state.playback.state = data.action_playback_state;
  }
  const motionText = data.manual_stop_pending ? (data.manual_stop_unconfirmed ? "未确认停住 · 重试中" : "确认停稳") : data.action_playback_state !== "idle" ? "动作执行" :
    (data.drag_recording ? "拖动录制" : (data.action_recording ? "动作录制" :
      (data.reset_return_active ? "复位中" : (data.step_active ? "步进中" : (data.jog_active ? "移动中" : "静止")))));
  $("motionBadge").textContent = motionText;
  $("motionBadge").className = `badge ${motionText === "静止" ? "neutral" : "warning"}`;

  // Controller telemetry may precede the press command. It must not end the user's local gesture.
  if (!data.connected) stopJog();
  if (!data.jog_active && !state.activeButton) {
    clearInterval(state.jogHeartbeatTimer);
    state.jogHeartbeatTimer = null;
    state.activePointerId = null;
  }

  const joints = data.arm?.joint || [];
  if (joints.length >= 6 && joints.slice(0, 6).every(value => typeof value === "number" && Number.isFinite(value))) {
    state.joints = joints.slice(0, 6);
    state.hasJointPose = true;
    state.joints.forEach((value, index) => {
      $(`joint${index + 1}`).textContent = Number(value).toFixed(2);
    });
  }

  const pose = data.arm?.pose || [];
  ["poseX", "poseY", "poseZ", "poseRx", "poseRy", "poseRz"].forEach((id, index) => {
    const value = pose[index] == null ? null : Number(pose[index]);
    $(id).textContent = value == null ? "—" : (index < 3 ? value * 1000 : value * 180 / Math.PI).toFixed(2);
  });
  const stateAge = Number(data.arm_state_age_ms);
  const sequence = Number(data.arm_state_sequence || 0);
  if (!data.connected || sequence <= 0) {
    state.armFrameSequence = 0;
    state.armFrameSeenAt = 0;
  } else if (sequence !== state.armFrameSequence) {
    state.armFrameSequence = sequence;
    state.armFrameSeenAt = performance.now() - Math.max(0, stateAge);
  }
  updateStateAgeDisplay();

  renderGripperStatus(data);

  const jointErrors = data.joints?.joint_err_code?.slice(0, 6) || [];
  const enabled = data.joints?.joint_en_flag?.slice(0, 6) || [];
  const systemErrors = data.joints?.system_error || [];
  const statusHealthy = jointErrors.length === 6 && enabled.length === 6 &&
    systemErrors.length > 0 && systemErrors.every((value) => value === 0) &&
    jointErrors.every((value) => value === 0) && enabled.every((value) => value === 1);
  const healthy = Boolean(data.connected && data.motion_ready && statusHealthy);
  state.motionReady = healthy;
  setEnabled(Boolean(data.connected), healthy);
  applyDirectionalLimitButtons(data, healthy);
  $("healthDot").className = data.connected ? (healthy ? "good" : "bad") : "";
  const healthText = $("healthText");
  const blockReason = String(data.motion_block_reason || "等待状态或请检查故障");
  healthText.textContent = data.connected ? (healthy ? "关节状态正常" : blockReason) : "等待连接";
  healthText.title = data.connected && !healthy ? blockReason : "";
  renderAlarmStatus(data);
  if (!data.last_error) {
    state.lastShownError = "";
  } else if (data.last_error !== state.lastShownError) {
    state.lastShownError = data.last_error;
    showMessage(data.last_error, true);
  }
  drawArm(state.joints);
}

let alarmListSignature = "";
function renderAlarmStatus(data) {
  const alarms = data.alarms || {};
  const items = Array.isArray(alarms.items) ? alarms.items : [];
  const known = data.connected && alarms.known === true;
  const canClear = known && alarms.can_clear === true && !alarms.busy;
  const count = items.length;
  $("alarmButton").textContent = count ? `${known ? "报警" : "上次报警"} ${count}` : known ? "无报警" : "报警待确认";
  $("alarmButton").classList.toggle("danger", count > 0);
  $("alarmSummary").textContent = !data.connected ? `未连接，无法读取当前报警${count ? ` · 上次读到 ${count} 条报警` : ""}` :
    !known ? `报警状态缺失或已过期${count ? ` · 上次读到 ${count} 条报警` : ""}` :
      count ? `当前 ${count} 条报警` : "当前系统和关节无报警";
  $("alarmSummary").classList.toggle("error", count > 0 || Boolean(data.connected && !known));
  $("alarmFreshness").textContent = data.connected ?
    `系统状态 ${alarms.arm_age_ms >= 0 ? Math.round(alarms.arm_age_ms) + " ms" : "未收到"} · 关节状态 ${alarms.safety_age_ms >= 0 ? Math.round(alarms.safety_age_ms) + " ms" : "未收到"}` : "";
  $("alarmRefresh").disabled = !data.connected || Boolean(alarms.busy);
  $("alarmClearSystem").disabled = !canClear || !items.some(item => item.target === 0);
  $("alarmOperation").textContent = alarms.message || "";
  $("alarmBlockReason").textContent = alarms.clear_block_reason || "";
  const signature = JSON.stringify([items, canClear]);
  if (signature === alarmListSignature) return;
  alarmListSignature = signature;
  $("alarmList").replaceChildren();
  const targets = new Set();
  items.forEach(item => {
    const row = document.createElement("article"); row.className = "alarm-item";
    const title = document.createElement("strong");
    title.textContent = `${item.source} · ${item.label} · ${item.hex}（${item.code}）`;
    const detail = document.createElement("p"); detail.textContent = `等级：${item.severity}。${item.hint}`;
    row.append(title, detail);
    if (Number.isInteger(item.target) && item.target >= 1 && item.target <= 6 && !targets.has(item.target)) {
      targets.add(item.target);
      const button = document.createElement("button"); button.className = "button danger"; button.type = "button";
      button.textContent = `清除 J${item.target} 报警`; button.disabled = !canClear;
      button.addEventListener("click", () => nativeCall("clearAlarm", item.target));
      row.append(button);
    }
    $("alarmList").append(row);
  });
}

function renderGripperStatus(data) {
  const gripper = data.gripper || {};
  const health = data.gripper_health;
  const ready = health ? health.ready === true : gripper.status === 1 && gripper.enable === 1 && gripper.error === 0;
  state.gripperAvailable = Boolean(data.connected && ready);
  state.gripperCanInitialize = Boolean(data.connected && health?.can_initialize);
  const busyText = data.gripper_initializing ? "正在启用并张开" : ({ 4: "正在闭合", 5: "正在张开" }[gripper.mode] || "等待完成确认");
  $("gripperBadge").textContent = !data.connected ? "未连接" : state.gripperBusy ? busyText :
    (health?.label || (ready ? "在线" : "等待夹爪回复"));
  $("gripperBadge").className = `badge ${state.gripperAvailable ? "online" : data.connected ? "warning" : "neutral"}`;
  const age = health?.age_ms >= 0 ? ` · ${Math.round(health.age_ms)} ms` : "";
  $("gripperDetail").textContent = (health?.detail || (ready ? "夹爪状态正常" : "正在读取夹爪状态")) + age;
  $("gripperInitialize").classList.toggle("hidden", !state.gripperCanInitialize && !data.gripper_initializing);
  $("gripperPosition").textContent = data.connected ? gripper.actpos ?? "—" : "—";
  $("gripperForce").textContent = !data.connected || gripper.current_force == null ? "—" : `${gripper.current_force} g`;
  $("gripperTemperature").textContent = !data.connected || gripper.temperature == null ? "—" : `${gripper.temperature} ℃`;
}

function refreshActionControls() {
  setEnabled(state.connected, state.motionReady);
  if (state.lastStatus) applyDirectionalLimitButtons(state.lastStatus, state.motionReady);
}

function updateEndpointLabels() {
  const tool = state.teachFrame === 1;
  const names = tool
    ? ["工具 +X", "工具 +Y", "工具 −Y", "工具 −X", "沿工具前进", "沿工具后退"]
    : ["前移", "左移", "右移", "后移", "上升", "下降"];
  ["moveForward", "moveLeft", "moveRight", "moveBack", "moveUp", "moveDown"].forEach((id, index) => {
    const button = $(id);
    button.querySelector('.direction-name').textContent = names[index];
    button.setAttribute("aria-label", `${names[index]} ${button.querySelector('small').textContent}，${state.stepMm ? `步进 ${state.stepMm} 毫米` : "按住移动"}`);
  });
  $("teachFrameSelect").value = state.teachFrame == null ? "" : String(state.teachFrame);
  $("teachFrameSelect").options[0].textContent = state.teachFramePending ? "切换确认中" : "待确认方向";
  $("teachFrameStatus").textContent = state.teachFrameReady ? "坐标模式已确认" : "坐标模式尚未确认";
  $("holdHint").textContent = state.controlTab === "move" && state.stepMm > 0
    ? `单次移动 ${state.stepMm} mm · 到位后可再次操作`
    : "按住移动 · 松手确认停稳 · 单次最多 5 秒";
  $("directionHint").textContent = state.controlTab === "joint"
    ? "J2 / J3 / J5 弯曲；J1 / J4 / J6 旋转"
    : (state.controlTab === "orientation"
      ? `绕${tool ? "工具" : "工作"} X/Y/Z 轴旋转，保持控制点位置`
      : (tool ? "工具 X/Y 平移 · 前进沿工具 +Z（需标定）" : "前后=X · 左右=Y · 升降=Z，以工作系为准"));
}

function renderRecording(recording) {
  state.recording = recording && typeof recording === "object" ? recording : null;
  const active = Boolean(state.recording);
  $("recordingBar").classList.toggle("hidden", !active);
  if (!active) {
    renderPlayback(state.playback);
    refreshActionControls();
    return;
  }
  const isDrag = state.recording.recording_type === "drag";
  const segmentActive = Boolean(state.recording.drag_segment_active);
  const saving = Boolean(state.recording.saving_drag_segment);
  const starting = Boolean(state.recording.starting_drag_segment);
  const failed = Boolean(state.recording.drag_recording_error);
  const gripperPending = Boolean(state.recording.gripper_pending);
  const interrupted = Boolean(state.recording.interrupted);
  const recordingError = String(state.recording.recording_error || "");
  const waiting = Boolean(state.recording.waiting_for_waypoint);
  $("recordingTitle").textContent = `${isDrag ? "拖动录制" : "遥控录制"} · ${state.recording.display_name || "未命名"}`;
  $("recordingSummary").textContent = `${Number(state.recording.node_count) || 0} 个节点${failed ? " · 当前段保存失败，请查看提示并取消录制" : (starting ? " · 等待控制器开始录制" : (saving ? " · 正在保存并确认拖动段" : (waiting ? " · 正在读取实际路点" : "")))}`;
  if (interrupted) $("recordingSummary").textContent += " · 草稿已保留，重新连接后继续录制";
  else if (gripperPending) $("recordingSummary").textContent += " · 等待夹爪完成后记录";
  else if (recordingError) $("recordingSummary").textContent += " · 夹爪未确认，请重试或取消";
  $("resumeRecordingButton").classList.toggle("hidden", !interrupted);
  $("finishDragSegmentButton").classList.toggle("hidden", !isDrag || !segmentActive);
  $("nextDragSegmentButton").classList.toggle("hidden", !isDrag || segmentActive || saving || failed);
  $("finishDragSegmentButton").disabled = starting || saving || failed;
  $("nextDragSegmentButton").disabled = starting || saving || failed || gripperPending || interrupted || Boolean(recordingError);
  $("insertWaitButton").disabled = state.jogActive || state.stepActive || waiting || segmentActive || saving || failed || gripperPending || interrupted || Boolean(recordingError);
  $("waitDuration").disabled = segmentActive || saving || failed || gripperPending || interrupted || Boolean(recordingError);
  $("finishRecordingButton").disabled = state.jogActive || state.stepActive || starting || saving || waiting || failed || gripperPending || interrupted || Boolean(recordingError);
  renderPlayback(state.playback);
  refreshActionControls();
}

function renderPlayback(playback) {
  state.playback = playback && typeof playback === "object"
    ? playback
    : { state: "idle", action_id: "", display_name: "", node_index: 0, node_count: 0 };
  const active = state.playback.state !== "idle";
  const failure = state.playback.last_failure;
  $("playbackBar").classList.toggle("hidden", !active && (!failure || Boolean(state.recording)));
  $("copyDiagnosticsButton").classList.toggle("hidden", !failure);
  $("stopActionButton").classList.toggle("hidden", !active);
  $("pauseActionButton").classList.toggle("hidden", state.playback.state !== "running");
  $("resumeActionButton").classList.toggle("hidden", state.playback.state !== "paused");
  if (!active && failure) {
    $("playbackTitle").textContent = `最近一次动作中断 · ${failure.action || "未命名"}`;
    $("playbackSummary").textContent = `节点 ${Number(failure.node_index) + 1} · ${failure.reason || "原因未知"}`;
  }
  if (active) {
    const stateNames = { aligning: "正在低速回到起点", running: "正在执行", paused: "已暂停" };
    $("playbackTitle").textContent = `动作执行 · ${state.playback.display_name || "未命名"}`;
    $("playbackSummary").textContent = `${stateNames[state.playback.state] || state.playback.state} · ${Math.min(Number(state.playback.node_index) + 1, Number(state.playback.node_count) || 1)}/${Number(state.playback.node_count) || 0}`;
    if (state.playback.waiting_for) $("playbackSummary").textContent += ` · ${state.playback.waiting_for}`;
    if (state.playback.control_pending) $("playbackSummary").textContent += " · 等待控制器确认暂停/继续";
    $("pauseActionButton").disabled = Boolean(state.playback.control_pending);
    $("resumeActionButton").disabled = Boolean(state.playback.control_pending);
    $("pauseActionButton").classList.toggle("hidden", state.playback.state !== "running");
    $("resumeActionButton").classList.toggle("hidden", state.playback.state !== "paused");
  }
  refreshActionControls();
}

function renderControllerTrajectories(value, metadata) {
  state.controllerTrajectories = Array.isArray(value) ? value : [];
  if (metadata) state.trajectoryCatalog = {
    loading: Boolean(metadata.loading), received: Boolean(metadata.received),
    error: String(metadata.error || ""), software_info: metadata.software_info || null,
  };
  const select = $("controllerTrajectorySelect");
  const previous = select.value;
  select.replaceChildren();
  const placeholder = document.createElement("option");
  placeholder.value = "";
  const catalog = state.trajectoryCatalog;
  placeholder.textContent = catalog.loading ? "正在读取控制器轨迹……" : (catalog.error ? "轨迹列表读取失败" :
    (!catalog.received ? "尚未成功读取" : (state.controllerTrajectories.length ? "请选择控制器轨迹" : "控制器中没有轨迹")));
  select.appendChild(placeholder);
  state.controllerTrajectories.forEach((trajectory) => {
    const option = document.createElement("option");
    option.value = String(trajectory.name || "");
    option.textContent = `${trajectory.name || "未命名"} · ${Number(trajectory.point_count) || 0} 点`;
    select.appendChild(option);
  });
  if (state.controllerTrajectories.some((item) => item.name === previous)) select.value = previous;
  const info = catalog.software_info;
  const version = info ? [info.Product_version || info.product_version,
    info.robot_controller_version ? `控制器 ${info.robot_controller_version}` : "",
    info.ctrl_info?.version ? `软件 ${info.ctrl_info.version}` : ""].filter(Boolean).join(" · ") : "";
  const status = catalog.error || (catalog.loading ? "正在检查轨迹文件接口……" :
    (catalog.received ? `轨迹列表读取成功，共 ${state.controllerTrajectories.length} 个文件。` : "尚未确认轨迹文件接口支持。"));
  $("controllerTrajectoryStatus").textContent = [version, status].filter(Boolean).join("；");
  refreshActionControls();
}

function renderActionLibrary(value) {
  state.actions = actionDomain.normalizeActions(value);
  $("actionCount").textContent = `${state.actions.length} 个动作`;
  const list = $("actionList");
  list.replaceChildren();
  if (!state.actions.length) {
    const empty = document.createElement("p");
    empty.className = "empty-state";
    empty.textContent = "还没有动作。可用遥控录制、拖动录制，或绑定控制器已有轨迹。";
    list.appendChild(empty);
    return;
  }
  state.actions.forEach((action) => {
    const card = document.createElement("article");
    card.className = "action-card";
    const description = document.createElement("div");
    const title = document.createElement("h4");
    title.textContent = action.display_name;
    const meta = document.createElement("div");
    meta.className = "action-meta";
    meta.textContent = `${action.recording_type === "drag" ? "拖动" : "遥控"} · ${action.nodes.length} 个节点 · 默认 ${Number(action.default_speed) || 5}%`;
    description.append(title, meta);
    const nodeSummary = document.createElement("div");
    nodeSummary.className = "action-nodes";
    nodeSummary.textContent = action.nodes.map(actionDomain.nodeLabel).join(" → ") || "没有节点";
    const controls = document.createElement("div");
    controls.className = "action-card-actions";
    const speed = document.createElement("input");
    speed.className = "address-input port";
    speed.type = "number";
    speed.min = "1";
    speed.max = String(state.maxSpeed);
    speed.value = String(Math.min(state.maxSpeed, Number(action.default_speed) || state.speed));
    speed.setAttribute("aria-label", `${action.display_name} 执行速度`);
    const run = document.createElement("button");
    run.className = "button primary";
    run.textContent = "执行";
    run.dataset.actionRun = action.id;
    run.disabled = !state.motionReady || state.playback.state !== "idle" || Boolean(state.recording);
    run.addEventListener("click", () => {
      const requestedSpeed = Math.max(1, Math.min(state.maxSpeed, Number(speed.value) || action.default_speed || 5));
      if (window.confirm(actionDomain.executionPrompt(action))) nativeCall("runAction", action.id, requestedSpeed);
    });
    const rename = document.createElement("button");
    rename.className = "button ghost";
    rename.textContent = "改名";
    rename.addEventListener("click", () => {
      const name = window.prompt("输入新的动作名称", action.display_name);
      if (name != null && name.trim()) nativeCall("renameAction", action.id, name.trim());
    });
    const remove = document.createElement("button");
    remove.className = "button danger";
    remove.textContent = "删除";
    remove.addEventListener("click", () => {
      if (window.confirm(`确认从动作库删除“${action.display_name}”？控制器中的拖动轨迹会保留，避免误删。`)) {
        nativeCall("deleteAction", action.id);
      }
    });
    controls.append(speed, run, rename, remove);
    card.append(description, nodeSummary, controls);
    list.appendChild(card);
  });
}

const RM65_URDF_JOINTS = [
  { xyz: [0, 0, 0.2405], rpy: [0, 0, 0] },
  { xyz: [0, 0, 0], rpy: [Math.PI / 2, -Math.PI / 2, 0] },
  { xyz: [0.256, 0, 0], rpy: [0, 0, Math.PI / 2] },
  { xyz: [0, -0.21, 0], rpy: [Math.PI / 2, 0, 0] },
  { xyz: [0, 0, 0], rpy: [-Math.PI / 2, 0, 0] },
  { xyz: [0, -0.1725, 0], rpy: [Math.PI / 2, 0, 0] },
];

function matrixMultiply(a, b) {
  const out = new Array(16).fill(0);
  for (let row = 0; row < 4; row++) {
    for (let col = 0; col < 4; col++) {
      for (let k = 0; k < 4; k++) out[row * 4 + col] += a[row * 4 + k] * b[k * 4 + col];
    }
  }
  return out;
}

function urdfTransform(xyz, rpy) {
  const [roll, pitch, yaw] = rpy;
  const cr = Math.cos(roll), sr = Math.sin(roll);
  const cp = Math.cos(pitch), sp = Math.sin(pitch);
  const cy = Math.cos(yaw), sy = Math.sin(yaw);
  return [
    cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr, xyz[0],
    sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr, xyz[1],
    -sp, cp * sr, cp * cr, xyz[2],
    0, 0, 0, 1,
  ];
}

function rotationZ(angle) {
  const c = Math.cos(angle), s = Math.sin(angle);
  return [c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
}

function matrixPosition(matrix) {
  return { x: matrix[3], y: matrix[7], z: matrix[11] };
}

function rm65JointPoints(joints) {
  let transform = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
  const points = [{ x: 0, y: 0, z: 0 }];
  RM65_URDF_JOINTS.forEach((joint, index) => {
    transform = matrixMultiply(transform, urdfTransform(joint.xyz, joint.rpy));
    points.push(matrixPosition(transform));
    transform = matrixMultiply(transform, rotationZ(Number(joints[index] || 0) * Math.PI / 180));
  });
  return points;
}

function drawArm(joints) {
  if (poseViewer) {
    poseViewer.update(joints);
    return;
  }
  const width = Math.max(1, canvas.clientWidth);
  const height = Math.max(1, canvas.clientHeight);
  const ratio = window.devicePixelRatio || 1;
  if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
  }
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, width, height);
  const points3d = rm65JointPoints(joints);
  const projected = points3d.map((point) => ({
    x: point.x * 0.82 - point.y * 0.58,
    y: point.x * 0.30 + point.y * 0.42 - point.z * 0.86,
  }));
  const scale = Math.min(width, height) * 1.05;
  const points = projected.map((point) => ({
    x: width * 0.48 + point.x * scale,
    y: height * 0.82 + point.y * scale,
  }));
  const baseY = points[0].y;

  ctx.save();
  ctx.strokeStyle = "rgba(64, 167, 255, .22)";
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(25, baseY + 22);
  ctx.lineTo(width - 45, baseY + 28);
  ctx.stroke();
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.strokeStyle = "#d9e8e7";
  ctx.lineWidth = Math.max(5, Math.min(15, scale * 0.025));
  ctx.beginPath();
  ctx.moveTo(points[0].x, points[0].y);
  points.slice(1).forEach((point) => ctx.lineTo(point.x, point.y));
  ctx.stroke();
  points.forEach((point, index) => {
    ctx.fillStyle = index === 0 ? "#40a7ff" : "#071019";
    ctx.strokeStyle = index === points.length - 1 ? "#4ee2b8" : "#4e6875";
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.arc(point.x, point.y, index === 0 ? 11 : 8, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    if (index > 0 && height > 200) {
      ctx.fillStyle = "#8ca3ad";
      ctx.font = "700 13px ui-monospace, monospace";
      const offsetX = index % 2 === 0 ? -24 : 11;
      ctx.fillText(`J${index}`, point.x + offsetX, point.y - 9);
    }
  });
  ctx.fillStyle = "#132c3c";
  ctx.fillRect(points[0].x - 28, baseY + 10, 56, 20);
  ctx.restore();
}

function updatePoseDataLabel() {
  const age = state.armFrameSeenAt ? performance.now() - state.armFrameSeenAt : Infinity;
  $("poseDataState").textContent = window.RM65Pose3D?.poseLabel(state.connected, state.hasJointPose, age) || "等待关节数据";
}

function initializePoseViewer() {
  const viewCanvas = $("arm3DCanvas");
  const toolbar = $("poseViewToolbar");
  function fallback() {
    poseViewer = null;
    viewCanvas.classList.add("hidden");
    toolbar.classList.add("hidden");
    canvas.classList.remove("hidden");
    $("poseRenderMode").textContent = "兼容姿态视图";
    $("poseViewHint").textContent = "当前 WebView 未启用 3D";
    drawArm(state.joints);
  }
  try {
    viewCanvas.classList.remove("hidden");
    poseViewer = window.RM65Pose3D.create({
      T: window.THREE, canvas: viewCanvas, data: window.RM65MeshData,
      specs: RM65_URDF_JOINTS, toolbar, onFailure: fallback,
    });
    canvas.classList.add("hidden");
    toolbar.classList.remove("hidden");
    $("poseRenderMode").textContent = "RM65 · 3D";
    $("poseViewHint").textContent = "单指旋转 · 双指缩放";
  } catch (error) {
    fallback();
  }
  updatePoseDataLabel();
}

$("connectButton").addEventListener("click", () => {
  const host = $("robotIp").value.trim();
  const port = Number($("robotPort").value);
  if (!host || !Number.isInteger(port) || port < 1 || port > 65535) {
    showMessage("请输入有效的机械臂 IP 和端口。", true);
    return;
  }
  nativeCall("connect", host, port);
});

$("disconnectButton").addEventListener("click", () => {
  stopJog();
  nativeCall("disconnect");
});

$("shortcutButton").addEventListener("click", () => nativeCall("addDesktopShortcut"));

$("stopButton").addEventListener("click", () => {
  stopJog();
  nativeCall("stopMotion");
});

$("saveResetButton").addEventListener("click", () => nativeCall("saveResetPose"));
const zeroButton = $("zeroButton");
zeroButton.addEventListener("pointerdown", (event) => {
  event.preventDefault();
  if (!state.connected || zeroButton.disabled || state.activeButton || state.jogActive) return;
  state.activePointerId = event.pointerId;
  zeroButton.setPointerCapture?.(event.pointerId);
  startSavedReset(zeroButton);
});
["pointerup", "pointercancel", "lostpointercapture"].forEach((name) => {
  zeroButton.addEventListener(name, stopJog);
});

$("speedMinus").addEventListener("click", () => {
  state.speed -= 1;
  updateSpeedControl();
});
$("speedPlus").addEventListener("click", () => {
  state.speed += 1;
  updateSpeedControl();
});

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    stopJog();
    state.controlTab = tab.dataset.tab;
    document.querySelector(".control-panel").classList.toggle("joint-mode", tab.dataset.tab === "joint");
    document.querySelectorAll(".tab").forEach((item) => {
      item.classList.toggle("active", item === tab);
      item.setAttribute("aria-selected", String(item === tab));
    });
    $("moveControls").classList.toggle("hidden", tab.dataset.tab !== "move");
    $("orientationControls").classList.toggle("hidden", tab.dataset.tab !== "orientation");
    $("jointControls").classList.toggle("hidden", tab.dataset.tab !== "joint");
    $("coupledBendControls").classList.toggle("hidden", tab.dataset.tab !== "joint");
    $("controlOptions").classList.toggle("hidden", tab.dataset.tab === "joint");
    $("motionPolicies").classList.toggle("hidden", tab.dataset.tab !== "move");
    updateEndpointLabels();
  });
});

document.querySelectorAll(".motion-policy").forEach((button) => {
  button.addEventListener("click", () => {
    stopJog();
    state.stepMm = Number(button.dataset.step);
    document.querySelectorAll(".motion-policy").forEach((item) => {
      item.classList.toggle("active", item === button);
      item.setAttribute("aria-pressed", String(item === button));
    });
    updateEndpointLabels();
  });
});

$("teachFrameSelect").addEventListener("change", (event) => {
  const frame = Number(event.target.value);
  if (event.target.value === "" || ![0, 1].includes(frame)) return;
  if (nativeCall("setTeachFrame", frame)) {
    state.teachFramePending = true;
    state.teachFrameReady = false;
    state.teachFrame = null;
    updateEndpointLabels();
    refreshActionControls();
  }
});

$("gripperOpen").addEventListener("click", () => nativeCall("gripperOpen"));
$("gripperClose").addEventListener("click", () => nativeCall("gripperClose"));
$("gripperInitialize").addEventListener("click", () => nativeCall("initializeGripper"));
$("gripperRefresh").addEventListener("click", () => nativeCall("refreshGripper"));
$("gripperDiagnostics").addEventListener("click", () => nativeCall("copyGripperDiagnostics"));
$("alarmButton").addEventListener("click", () => {
  stopManualMotion();
  $("alarmOverlay").classList.remove("hidden");
});
$("alarmClose").addEventListener("click", () => $("alarmOverlay").classList.add("hidden"));
$("alarmRefresh").addEventListener("click", () => nativeCall("refreshAlarms"));
$("alarmClearSystem").addEventListener("click", () => nativeCall("clearAlarm", 0));

function readNewAction() {
  const displayName = $("newActionName").value.trim();
  const speed = Number($("newActionSpeed").value);
  if (!displayName) {
    showMessage("请先输入动作名称。", true);
    return null;
  }
  if (!Number.isInteger(speed) || speed < 1 || speed > state.maxSpeed) {
    showMessage(`动作速度请输入 1～${state.maxSpeed}% 的整数。`, true);
    return null;
  }
  return { displayName, speed };
}

function openActionLibrary() {
  stopManualMotion();
  $("newActionSpeed").max = String(state.maxSpeed);
  $("newActionSpeed").value = String(Math.min(state.speed, state.maxSpeed));
  $("actionOverlay").classList.remove("hidden");
  renderActionLibrary(state.actions);
  if (state.connected) nativeCall("refreshControllerTrajectories");
}

function closeActionLibrary() {
  $("actionOverlay").classList.add("hidden");
  $("actionLibraryButton").focus();
}

$("actionLibraryButton").addEventListener("click", openActionLibrary);
$("actionLibraryClose").addEventListener("click", closeActionLibrary);
$("startRemoteRecordingButton").addEventListener("click", () => {
  const input = readNewAction();
  if (input) nativeCall("startRemoteRecording", input.displayName, input.speed);
});
$("startDragRecordingButton").addEventListener("click", () => {
  const input = readNewAction();
  if (!input) return;
  if (!window.confirm("请等待页面确认控制器已开始录制后手动拖动机械臂。完成后可点击“结束并保存”；连续轨迹由控制器记录。确认开始？")) return;
  nativeCall("startDragRecording", input.displayName, input.speed);
});
$("refreshTrajectoryButton").addEventListener("click", () => nativeCall("refreshControllerTrajectories"));
$("controllerTrajectorySelect").addEventListener("change", () => {
  if (!$("importActionName").value.trim() && $("controllerTrajectorySelect").value) {
    $("importActionName").value = $("controllerTrajectorySelect").value;
  }
});
$("importTrajectoryButton").addEventListener("click", () => {
  const controllerName = $("controllerTrajectorySelect").value;
  const displayName = $("importActionName").value.trim();
  const speed = Number($("newActionSpeed").value);
  if (!controllerName || !displayName) {
    showMessage("请选择控制器轨迹并输入中文显示名称。", true);
    return;
  }
  if (!Number.isInteger(speed) || speed < 1 || speed > state.maxSpeed) {
    showMessage(`动作速度请输入 1～${state.maxSpeed}% 的整数。`, true);
    return;
  }
  if (window.confirm("请确认机械臂当前正处于所选轨迹的真实起点；当前位置会被保存为自动回起点目标。")) {
    nativeCall("importControllerTrajectory", controllerName, displayName, speed);
  }
});
$("finishDragSegmentButton").addEventListener("click", () => nativeCall("finishDragSegment"));
$("nextDragSegmentButton").addEventListener("click", () => nativeCall("startNextDragSegment"));
$("insertWaitButton").addEventListener("click", () => nativeCall("insertActionWait", Number($("waitDuration").value)));
$("finishRecordingButton").addEventListener("click", () => nativeCall("finishActionRecording"));
$("cancelRecordingButton").addEventListener("click", () => {
  if (window.confirm("确认取消本次录制？已保存到控制器的拖动轨迹会保留，避免误删。")) nativeCall("cancelActionRecording");
});
$("resumeRecordingButton").addEventListener("click", () => {
  if (window.confirm("请确认当前机械臂状态及位置。未确认的动作不会自动重发；当前未完成的拖动段需重新录制。")) nativeCall("resumeRecording");
});
$("copyDiagnosticsButton").addEventListener("click", () => nativeCall("copyDiagnostics"));
$("pauseActionButton").addEventListener("click", () => nativeCall("pauseAction"));
$("resumeActionButton").addEventListener("click", () => nativeCall("resumeAction"));
$("stopActionButton").addEventListener("click", () => nativeCall("stopAction"));

window.addEventListener("blur", stopManualMotion);
document.addEventListener("visibilitychange", () => { if (document.hidden) stopManualMotion(); });
window.addEventListener("pointerup", stopJog);
window.addEventListener("pointercancel", stopJog);
window.addEventListener("pagehide", stopManualMotion);
window.addEventListener("beforeunload", stopManualMotion);

window.RM65App = {
  onNativeEvent(rawEvent) {
    try {
      const event = typeof rawEvent === "string" ? JSON.parse(rawEvent) : rawEvent;
      if (event.type === "status") updateStatus(event.data);
      if (event.type === "message") showMessage(event.message, Boolean(event.error));
      if (event.type === "manual_hold_released") finishNativeHold(event.data);
      if (event.type === "connection_settings_saved") {
        finishConnectionSettingsSave(event.data);
      }
      if (event.type === "action_library") renderActionLibrary(event.data);
      if (event.type === "controller_trajectories") renderControllerTrajectories(event.data, event);
      if (event.type === "recording") {
        renderRecording(event.data);
        if (event.data) closeActionLibrary();
      }
      if (event.type === "playback") renderPlayback(event.data);
    } catch (error) {
      showMessage(`Android 状态数据无效：${error.message}`, true);
    }
  },
};

function snapshotJointLimitForm() {
  return Array.from({ length: 6 }, (_, index) => {
    const joint = index + 1;
    return {
      minimum: $(`controlMin${joint}`).value,
      maximum: $(`controlMax${joint}`).value,
      margin: $(`limitMargin${joint}`).value,
    };
  });
}

function restoreJointLimitForm(snapshot) {
  if (!snapshot) return;
  snapshot.forEach((value, index) => {
    const joint = index + 1;
    $(`controlMin${joint}`).value = value.minimum;
    $(`controlMax${joint}`).value = value.maximum;
    $(`limitMargin${joint}`).value = value.margin;
  });
}

function readJointLimitForm() {
  const raw = snapshotJointLimitForm();
  if (raw.every((value) => value.minimum === "" && value.maximum === "")) return { skip: true };
  const config = { minimum: [], maximum: [], margin: [] };
  for (let index = 0; index < 6; index++) {
    const joint = index + 1;
    const minimum = Number(raw[index].minimum);
    const maximum = Number(raw[index].maximum);
    const margin = Number(raw[index].margin);
    if (raw[index].minimum === "" || raw[index].maximum === "" || raw[index].margin === "" ||
        !Number.isFinite(minimum) || !Number.isFinite(maximum) || !Number.isFinite(margin)) {
      return { error: `J${joint} 请填写完整的数字范围。` };
    }
    if (margin < 0 || margin > 90) return { error: `J${joint} 提前停止角度必须为 0～90°。` };
    if (maximum - minimum <= margin * 2) {
      return { error: `J${joint} 软件范围必须大于两倍提前停止角度。` };
    }
    if (state.actualJointMinimum.length >= 6 && minimum < Number(state.actualJointMinimum[index])) {
      return { error: `J${joint} 软件最小值不能小于实际最小限位。` };
    }
    if (state.actualJointMaximum.length >= 6 && maximum > Number(state.actualJointMaximum[index])) {
      return { error: `J${joint} 软件最大值不能大于实际最大限位。` };
    }
    config.minimum.push(minimum);
    config.maximum.push(maximum);
    config.margin.push(margin);
  }
  return { config };
}

let savedEndpoint = null;
function closeSettings(restore) {
  if (restore && savedEndpoint) {
    $("robotIp").value = savedEndpoint.ip;
    $("robotPort").value = savedEndpoint.port;
    $("watchdogEnabled").checked = savedEndpoint.watchdogEnabled;
    $("watchdogSeconds").value = savedEndpoint.watchdogSeconds;
    $("watchdogSeconds").disabled = !savedEndpoint.watchdogEnabled;
    restoreJointLimitForm(savedEndpoint.jointLimits);
  }
  $("settingsOverlay").classList.add("hidden");
  $("robotAddress").textContent = `${$("robotIp").value}:${$("robotPort").value}`;
  $("settingsButton").focus();
}
$("settingsButton").addEventListener("click", () => {
  stopManualMotion();
  savedEndpoint = {
    ip: $("robotIp").value,
    port: $("robotPort").value,
    watchdogEnabled: $("watchdogEnabled").checked,
    watchdogSeconds: $("watchdogSeconds").value,
    jointLimits: snapshotJointLimitForm(),
  };
  $("settingsError").textContent = state.connected ? "连接中可修改失联停止设置；修改地址需先断开。" : "";
  $("settingsOverlay").classList.remove("hidden");
});
$("settingsCancel").addEventListener("click", () => closeSettings(true));
$("settingsDisconnect").addEventListener("click", () => {
  stopManualMotion();
  nativeCall("disconnect");
  $("settingsError").textContent = "断开后即可修改地址，保存后点击连接机械臂。";
});
$("settingsSave").addEventListener("click", () => {
  const port = Number($("robotPort").value);
  const watchdogSeconds = Number($("watchdogSeconds").value);
  const jointLimitResult = readJointLimitForm();
  if (!$("robotIp").value.trim() || !Number.isInteger(port) || port < 1 || port > 65535) {
    $("settingsError").textContent = "请输入有效 IP 和 1～65535 端口。";
    return;
  }
  if (!Number.isInteger(watchdogSeconds) || watchdogSeconds < 1 || watchdogSeconds > 30) {
    $("settingsError").textContent = "失联停止时间请输入 1～30 秒的整数。";
    return;
  }
  if (jointLimitResult.error) {
    $("settingsError").textContent = jointLimitResult.error;
    return;
  }
  nativeCall("setJogWatchdog", $("watchdogEnabled").checked, watchdogSeconds);
  if (!jointLimitResult.skip) {
    nativeCall("setJointSafetyConfig", JSON.stringify(jointLimitResult.config));
  }
  $("settingsSave").disabled = true;
  if (!nativeCall("saveConnectionSettings", $("robotIp").value.trim(), port)) {
    $("settingsSave").disabled = false;
    $("settingsError").textContent = "地址未能保存，请检查控制层连接后重试。";
  }
});
$("watchdogEnabled").addEventListener("change", () => {
  $("watchdogSeconds").disabled = !$("watchdogEnabled").checked;
});
document.addEventListener("keydown", event => {
  if (event.key !== "Escape") return;
  if (!$("actionOverlay").classList.contains("hidden")) closeActionLibrary();
  else if (!$("settingsOverlay").classList.contains("hidden")) closeSettings(true);
});
initializePoseViewer();
window.addEventListener("resize", () => { poseViewer?.resize(); drawArm(state.joints); });
if (window.ResizeObserver) new ResizeObserver(() => { poseViewer?.resize(); drawArm(state.joints); }).observe(canvas.parentElement);
drawArm(state.joints);
["bendNegative", "bendPositive"].forEach(id => bindCoupledBend($(id)));
setEnabled(false, false);
updateSpeedControl();
renderActionLibrary([]);
renderControllerTrajectories([]);
renderRecording(null);
renderPlayback(null);
setInterval(updateStateAgeDisplay, 100);
nativeCall("pageReady");
