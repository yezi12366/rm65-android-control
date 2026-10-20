/* Browser-only simulation; never replaces the Android JavaScript interface. */
(() => {
  if (window.RobotBridge || new URLSearchParams(location.search).get("preview") !== "1") return;
  let connected = true, frame = 0, pendingFrame = null, held = null, step = false;
  let joints = [10, 35, 65, 20, 55, 10], grip = 500, sequence = 0;
  let stepTimer = null, frameTimer = null, lastTick = 0;
  let endpointIp = "192.168.33.80", endpointPort = 8080;
  try {
    const saved = JSON.parse(localStorage.getItem("rm65-preview-endpoint") || "null");
    if (saved && typeof saved.ip === "string" && Number.isInteger(saved.port)) {
      endpointIp = saved.ip; endpointPort = saved.port;
    }
  } catch (_) { /* The simulation also works when browser storage is unavailable. */ }
  let previewReset = null;
  const calls = [];
  const emit = (type, data) => window.RM65App?.onNativeEvent({ type, data });
  const message = (text, error = false) => window.RM65App?.onNativeEvent({ type: "message", message: text, error });

  function forward(values) {
    let transform = [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1];
    RM65_URDF_JOINTS.forEach((joint, i) => {
      transform = matrixMultiply(transform, urdfTransform(joint.xyz, joint.rpy));
      transform = matrixMultiply(transform, rotationZ(values[i] * Math.PI / 180));
    });
    return transform;
  }

  function poseOf(transform) {
    return [transform[3], transform[7], transform[11],
      Math.atan2(transform[9], transform[10]),
      Math.atan2(-transform[8], Math.hypot(transform[0], transform[4])),
      Math.atan2(transform[4], transform[0])];
  }

  function errorTo(current, target) {
    const orientation = [0, 0, 0];
    for (let col = 0; col < 3; col++) {
      const a = [current[col], current[4 + col], current[8 + col]];
      const b = [target[col], target[4 + col], target[8 + col]];
      orientation[0] += (a[1]*b[2] - a[2]*b[1]) / 2;
      orientation[1] += (a[2]*b[0] - a[0]*b[2]) / 2;
      orientation[2] += (a[0]*b[1] - a[1]*b[0]) / 2;
    }
    return [target[3]-current[3], target[7]-current[7], target[11]-current[11], ...orientation];
  }

  function solve(matrix, values) {
    const augmented = matrix.map((row, i) => [...row, values[i]]);
    for (let col = 0; col < 6; col++) {
      let pivot = col;
      for (let row = col + 1; row < 6; row++) if (Math.abs(augmented[row][col]) > Math.abs(augmented[pivot][col])) pivot = row;
      [augmented[col], augmented[pivot]] = [augmented[pivot], augmented[col]];
      const divisor = augmented[col][col];
      if (Math.abs(divisor) < 1e-10) return null;
      for (let k = col; k < 7; k++) augmented[col][k] /= divisor;
      for (let row = 0; row < 6; row++) if (row !== col) {
        const factor = augmented[row][col];
        for (let k = col; k < 7; k++) augmented[row][k] -= factor * augmented[col][k];
      }
    }
    return augmented.map(row => row[6]);
  }

  // Preview kinematics only. Real motion is solved and executed by the robot controller.
  function translate(axis, distanceM) {
    const start = forward(joints), target = [...start], index = { x: 0, y: 1, z: 2 }[axis];
    for (let row = 0; row < 3; row++) target[row * 4 + 3] += distanceM * (frame === 1 ? start[row * 4 + index] : Number(row === index));
    return solveTarget(target);
  }

  function rotate(axis, radians) {
    const current = forward(joints), rpy = [0, 0, 0];
    rpy[{ rx: 0, ry: 1, rz: 2 }[axis]] = radians;
    const rotation = urdfTransform([0, 0, 0], rpy);
    const target = frame === 1 ? matrixMultiply(current, rotation) : matrixMultiply(rotation, current);
    [3, 7, 11].forEach(index => target[index] = current[index]);
    return solveTarget(target);
  }

  function solveTarget(target) {
    const candidate = [...joints];
    for (let iteration = 0; iteration < 18; iteration++) {
      const current = forward(candidate), error = errorTo(current, target);
      if (Math.hypot(...error) < 0.00002) { joints = candidate; return true; }
      const columns = candidate.map((_, col) => {
        const shifted = [...candidate]; shifted[col] += 0.01;
        return errorTo(current, forward(shifted)).map(value => value / 0.01);
      });
      const matrix = Array.from({ length: 6 }, (_, row) => Array.from({ length: 6 }, (_, col) =>
        columns.reduce((sum, column) => sum + column[row] * column[col], 0) + (row === col ? 0.00000001 : 0)));
      const delta = solve(matrix, error);
      if (!delta) return false;
      candidate.forEach((value, col) => candidate[col] = value + Math.max(-2, Math.min(2, columns[col].reduce((sum, item, row) => sum + item * delta[row], 0))));
    }
    return false;
  }

  function publish() {
    if (!window.RM65App) return;
    sequence++;
    emit("status", {
      connected, preview: true, jog_active: Boolean(held || step), step_active: step,
      teach_frame: pendingFrame == null && connected ? frame : null,
      teach_frame_pending: pendingFrame, teach_frame_ready: connected && pendingFrame == null,
      motion_ready: connected, motion_block_reason: connected ? "" : "模拟已结束",
      reset_return_active: false, reset_pose_saved: Boolean(previewReset), coupled_bend_active: false, action_playback_state: "idle", last_error: "",
      arm_state_age_ms: 0, arm_state_sequence: connected ? sequence : 0,
      alarms: { known: connected, items: [], busy: false, can_clear: connected,
        arm_age_ms: 0, safety_age_ms: 0, message: "本地模拟状态", clear_block_reason: "" },
      frames: { work: "World", tool: "Arm_Tip" }, robot: { ip: endpointIp, port: endpointPort },
      limits: { default_speed: 5, max_speed: 30, jog_watchdog_enabled: true, jog_watchdog_seconds: 5,
        joint_min: [-178,-130,-135,-178,-128,-360], joint_max: [178,130,135,178,128,360],
        control_joint_min: [-178,-130,-135,-178,-128,-360], control_joint_max: [178,130,135,178,128,360],
        joint_limit_margin_deg: [10,10,10,10,10,10] },
      arm: { joint: [...joints], pose: poseOf(forward(joints)) },
      joints: { joint_en_flag: [1,1,1,1,1,1], joint_err_code: [0,0,0,0,0,0], system_error: [0] },
      gripper: { enable: 1, status: 1, error: 0, actpos: grip, current_force: 0, temperature: 28 }
    });
    document.getElementById("connectionBadge").innerHTML = `<i></i>${connected ? "模拟连接" : "模拟结束"}`;
  }

  function stop() {
    held = null; step = false;
    clearTimeout(stepTimer);
    publish();
  }
  const record = (name, args) => calls.push({ name, args: [...args] });
  window.RobotBridge = {
    pageReady() {
      const pill = document.createElement("span");
      pill.className = "preview-pill"; pill.textContent = "交互预览";
      document.querySelector(".topbar > div").appendChild(pill);
      document.getElementById("connectButton").textContent = "启动模拟";
      document.getElementById("disconnectButton").textContent = "结束模拟";
      publish(); emit("action_library", []);
      message("交互预览 · 所有操作均为本地模拟 · 工作方向以 World 坐标轴为准");
    },
    connect(host, port) { endpointIp = host; endpointPort = port; connected = true; publish(); },
    saveConnectionSettings(host, port) {
      endpointIp = host; endpointPort = port;
      try { localStorage.setItem("rm65-preview-endpoint", JSON.stringify({ip: host, port})); } catch (_) {}
      setTimeout(() => { emit("connection_settings_saved", {success:true}); publish(); }, 100);
    },
    disconnect() { stop(); connected = false; publish(); },
    startJog(mode, axis, direction, speed) {
      record("startJog", arguments);
      if (!connected || step || pendingFrame != null) return;
      held = { mode, axis, direction, speed }; publish();
    },
    stopJog() { record("stopJog", arguments); if (held || step) stop(); },
    stopMotion() { record("stopMotion", arguments); stop(); message("模拟运动已停止"); },
    jogHeartbeat() {},
    stepPosition(axis, direction, distanceMm, speed) {
      record("stepPosition", arguments);
      if (!connected || held || step || pendingFrame != null) return;
      step = true; publish();
      stepTimer = setTimeout(() => {
        const moved = translate(axis, distanceMm * direction / 1000);
        step = false; publish();
        message(moved ? `模拟步进 ${distanceMm} mm 已到位` : "该方向暂时无法继续移动，请调整构型", !moved);
      }, 450);
    },
    setTeachFrame(value) {
      record("setTeachFrame", arguments);
      if (held || step || !connected) return;
      pendingFrame = value; publish();
      clearTimeout(frameTimer);
      frameTimer = setTimeout(() => { frame = value; pendingFrame = null; publish(); }, 180);
    },
    saveResetPose() { previewReset = [...joints]; message("已保存模拟姿态；实机姿态请在安卓 App 中保存"); publish(); },
    startSavedReset(speed, holdId) {
      message("此预览保留示意姿态；按住复位请在安卓 App 中验证");
      setTimeout(() => emit("manual_hold_released", {hold_id: holdId}), 0); publish();
    },
    startCoupledBend(direction, speed, holdId) {
      message("联动试用请在安卓 App 中验证；此预览不执行联动");
      setTimeout(() => emit("manual_hold_released", {hold_id: holdId}), 0); publish();
    },
    gripperOpen() { grip = 1000; publish(); },
    gripperClose() { grip = 1; publish(); },
    refreshAlarms() { publish(); },
    clearAlarm() { message("模拟状态下没有机械臂报警，不发送实机清除指令"); },
    refreshControllerTrajectories() { emit("controller_trajectories", []); },
    addDesktopShortcut() {},
    setJointSafetyConfig() {},
    setJogWatchdog() {},
    startRemoteRecording() { message("此预览用于体验移动控制；动作录制请使用 Android App"); },
    startDragRecording() { message("拖动录制需要连接实体机械臂"); }
  };
  window.RM65Preview = { calls };
  setInterval(() => {
    const now = performance.now(), elapsed = lastTick ? Math.min(now - lastTick, 150) / 1000 : 0;
    lastTick = now;
    if (held) {
      if (held.mode === "position") {
        if (!translate(held.axis, held.direction * (held.speed / 5) * 0.012 * elapsed)) {
          held = null; message("该方向暂时无法继续移动，请调整构型", true);
        }
      } else if (held.mode === "joint") joints[Number(held.axis) - 1] += held.direction * held.speed * elapsed;
      else if (!rotate(held.axis, held.direction * held.speed * Math.PI / 180 * elapsed)) {
        held = null; message("该朝向暂时无法继续调整，请调整构型", true);
      }
    }
    publish();
  }, 80);
  window.addEventListener("blur", stop);
  document.addEventListener("visibilitychange", () => { if (document.hidden) stop(); });
})();
