# 网页与 Kotlin 桥接接口

1.15.4 的完整接口和状态机见 [开发者技术文档](DEVELOPER_1_15_4.md)。下文保留了此前版本的演进说明。

本页供安卓和前端开发人员联调。网页只能调用 Android 注入的 `window.RobotBridge`，不直接建立 TCP 连接。

## JavaScript 调用 Kotlin

```javascript
RobotBridge.pageReady()
RobotBridge.connect("192.168.1.18", 8080)
RobotBridge.disconnect()

RobotBridge.startJog("joint", "1", 1, 5, 1)
RobotBridge.startJog("position", "z", -1, 5, 2)
RobotBridge.startJog("orientation", "rx", 1, 5, 3)
RobotBridge.saveResetPose()
RobotBridge.startSavedReset(5, 4)
RobotBridge.startCoupledBend(1, 3, 5)
RobotBridge.jogHeartbeat()
RobotBridge.stopJog()
RobotBridge.stopMotion()
RobotBridge.setJogWatchdog(true, 5)
RobotBridge.setJointSafetyConfig('{"minimum":[-170,-110,-170,-110,-170,-110],"maximum":[170,110,170,110,170,110],"margin":[10,10,10,10,10,10]}')

RobotBridge.gripperOpen()
RobotBridge.gripperClose()
RobotBridge.refreshGripper() // 重新检测供电和夹爪，不开合
RobotBridge.initializeGripper() // 用户显式请求：首次启用并张开夹爪，不录入动作库
RobotBridge.copyGripperDiagnostics()

RobotBridge.startRemoteRecording("抓取样品", 5)
RobotBridge.startDragRecording("拖动搬运", 5)
RobotBridge.finishDragSegment()
RobotBridge.startNextDragSegment()
RobotBridge.insertActionWait(1000)
RobotBridge.finishActionRecording()
RobotBridge.cancelActionRecording()
RobotBridge.resumeRecording()
RobotBridge.refreshControllerTrajectories()
RobotBridge.importControllerTrajectory("A123S1", "搬运", 5)
RobotBridge.runAction("A1234567890", 5)
RobotBridge.pauseAction()
RobotBridge.resumeAction()
RobotBridge.stopAction()
RobotBridge.copyDiagnostics()
RobotBridge.renameAction("A1234567890", "新的中文名称")
RobotBridge.deleteAction("A1234567890")
```

参数约束：

- `mode`：`joint`、`position` 或 `orientation`。
- 关节轴：字符串 `1`~`6`。
- 位置轴：`x`、`y`、`z`。
- 姿态轴：`rx`、`ry`、`rz`。
- `direction`：`-1` 或 `1`。
- `speed`：整数 `1`~`30`。
- 手动夹爪只提供张开和闭合；已有动作库的位置节点仍可回放。
- 点动开始后，松开时调用 `stopJog()`。
- 1.14.1 的手动点动/按住复位还需传入本次递增 `holdId`，原生检测不到持续按压时拒绝开始。原生 `manual_hold_released` 事件包含 `data.hold_id`，网页只能结束编号匹配的本地手势；状态刷新不能清除仍在按住的指针。详见 [松手停止修复](RELEASE_STOP_FIX.md)。
- 失联停止开关打开时，运动期间网页每 500 ms 调用一次 `jogHeartbeat()`；`setJogWatchdog(enabled, seconds)` 设置开关及 1～30 秒超时值。
- `setJointSafetyConfig(json)` 设置六轴软件最小值、软件最大值和逐轴提前停止角度；三个数组都必须正好包含六个数值。
- `startSavedReset(speed, holdId)` 返回保存姿态，使用顶部 1～30% 设置；`startCoupledBend(direction, speed, holdId)` 联动三轴，使用顶部 1～30% 设置、每轴最多 10°。正常松手确认停稳后保留连接，确认失败才断开。

所有参数会在 Kotlin 层再次校验。JavaScript 接口是异步触发式调用，成功连接和动作状态通过下面的事件回传，而不是通过函数返回值判断。

## Kotlin 推送网页事件

Kotlin 执行：

```javascript
window.RM65App.onNativeEvent(jsonString)
```

消息事件：

```json
{
  "type": "message",
  "message": "连接成功。",
  "error": false
}
```

状态事件：

```json
{
  "type": "status",
  "data": {
    "connected": true,
    "jog_active": false,
    "reset_return_active": false,
    "reset_pose_saved": false,
    "reset_pose_label": "复位姿态尚未保存",
    "coupled_bend_active": false,
    "motion_ready": true,
    "motion_block_reason": "",
    "last_error": "",
    "arm_state_age_ms": 23,
    "arm_state_sequence": 182,
    "frames": {
      "work": "World",
      "tool": "Arm_Tip"
    },
    "limits": {
      "default_speed": 5,
      "max_speed": 30,
      "jog_watchdog_enabled": true,
      "jog_watchdog_seconds": 5,
      "joint_limit_margin_deg": [10, 10, 10, 10, 10, 10],
      "joint_min": [-170, -110, -170, -110, -170, -110],
      "joint_max": [170, 110, 170, 110, 170, 110],
      "control_joint_min": [-160, -100, -160, -100, -160, -100],
      "control_joint_max": [160, 100, 160, 100, 160, 100]
    },
    "robot": {
      "ip": "192.168.1.18",
      "port": 8080
    },
    "arm": {
      "joint": [0, 0, 0, 0, 0, 0],
      "pose": [0, 0, 0, 0, 0, 0]
    },
    "joints": {
      "joint_en_flag": [1, 1, 1, 1, 1, 1],
      "joint_err_code": [0, 0, 0, 0, 0, 0],
      "system_error": [0]
    },
    "gripper": {
      "enable": 1,
      "status": 1,
      "error": 0,
      "actpos": 500,
      "current_force": 0
    }
  }
}
```

连接初期尚未获得数据时，`arm` 和 `gripper` 可以是 `null`，关节状态及限位数组可以为空。`joint_min`/`joint_max` 是控制器实际限位，`control_joint_min`/`control_joint_max` 是用户设置的软件运动范围。只有六轴角度、使能/错误状态、实际限位及软件范围均有效时，`motion_ready` 才会变为 `true`。

动作功能另有四类事件：

- `action_library`：`data` 是动作数组，每个动作含 `id`、`display_name`、`recording_type`、`default_speed` 和 `nodes`。
- `recording`：未录制时 `data=null`；录制时含节点数、模式、拖动段/保存/路点读取状态。
- `playback`：含 `state`（`idle`、`aligning`、`running`、`paused`）、动作名称和节点进度。
- `controller_trajectories`：`data` 是控制器轨迹数组，元素含 `name`、`point_count` 和 `created_at`；事件顶层另含 `loading`、`received`、`error`、`software_info`。只有 `received=true` 且无错误时，空数组才代表“控制器中没有轨迹”；未回复、失败或缺失 `list` 不可显示为空列表。`software_info` 为 `get_arm_software_info` 的实际回复，未读取时为 `null`，不可根据缺失字段猜测控制器代际。

`recording.data` 另含 `starting_drag_segment` 和 `drag_recording_error`。拖动段开始等待确认期间禁止结束；确认进入录制后“结束并保存”可直接调用 `finishActionRecording()`，Kotlin 会完成停止、保存、文件确认和动作入库。保存中禁止重复开始或插入夹爪/等待节点，失败时显示原因并允许取消录制。

1.13.2 新增字段及行为：

- `status.data.gripper_busy` 表示存在待确认夹爪动作，`recording_interrupted` 表示录制中断。夹爪状态另含 `mode` 与可为空的 `temperature`。
- `recording.data.gripper_pending` 为夹爪确认屏障，期间禁止夹爪重复操作、手动运动、插入等待和结束保存。节点数只在确认完成后增加。`interrupted` / `recording_error` 保留中断与错误状态；`resumeRecording()` 在重新连接并确认安全状态后明确恢复当前进程的草稿。
- `playback.data.waiting_for` 显示等待内容，`control_pending` 表示待确认的暂停/继续命令，期间禁用暂停和继续按钮。`last_failure` 为最近一次中断诊断或 `null`，空闲时仍需展示。
- `copyDiagnostics()` 仅在用户点击时复制最近一次回放中断的诊断 JSON 到系统剪贴板。包含原因、动作/节点、接收状态、规划状态、角度/错误和近期关键事件。

指令确认超时或执行失败不会自动重发运动。异常中断关闭本次连接；继续执行需由用户重新连接并重新选择动作。暂停夹爪只阻止下一节点，当前夹爪可能继续完成。

节点类型为 `waypoint`、`drag_trajectory`、`gripper` 和 `wait`。网页只负责显示与触发，录制互斥、参数校验、动作顺序和运动安全由 Kotlin 层负责。

## 修改时必须保留的安全行为

- `pointerdown` 才启动点动，`pointerup`、`pointercancel` 和 `lostpointercapture` 都要停止。
- 页面 `blur`、变为隐藏或卸载时停止点动。
- 网页可以提前禁用已接近限位的方向，但 Kotlin 层的方向限位检查与运动中截停必须保留，不能只依赖按钮状态。
- 每轴软件范围必须位于控制器实际限位内，且范围宽度必须大于该轴提前停止角度的两倍；网页和 Kotlin 都要校验。
- 不要在网页中实现绕过 Kotlin 速度上限或状态检查的第二条网络控制路径。
- WebView 只能加载受信任的本地资源；不要在保留 `addJavascriptInterface` 的同时允许导航到任意网站。
- App 进入后台时由 Kotlin 停止并断开，前端不应假设回到页面后连接仍存在。
- “立即停止”是一次停止命令，不是锁定状态；用户重新按住方向键即可再次点动，无需额外的“恢复控制”接口。
- 按住复位返回用户保存的六个关节角，夹爪保持不变，不得改为单击后无人值守完成。
- 普通点动失联停止默认 5 秒，可配置或关闭；联动和复位始终强制每 500ms 心跳与 1.5 秒截停。松手、取消、失焦及后台仍必须调用 stopJog。

## 1.15.2 状态

`reset_pose_saved`、`reset_pose_label`、`reset_return_active`、`coupled_bend_active` 分别表示是否已保存、保存时间标签、正在复位、正在联动。

详细行为与保护范围见 [关节与复位控制](JOINT_AND_RESET_CONTROL.md)。旧全零回零、三维目标控制和奇异脱离接口已撤下。

1.15.3 状态 `manual_stop_pending` 表示正在确认停稳，此时所有运动和夹爪入口必须禁用，原生也会拒绝。新指令不得绕过此屏障。详见 [RELEASE_CONNECTION_FIX.md](RELEASE_CONNECTION_FIX.md)。

## 1.15.4 停止及报警

`jogHeartbeat(holdId: Int)` 必须携带当前手势 ID，150ms 一次。所有按住操作固定 500ms 超时、单次最长 5 秒，旧兼容失联设置不能关闭该保护。

状态增加 `manual_stop_unconfirmed`、`hold_signal_timeout_ms`、`hold_max_ms` 和 `alarms`。`manual_stop_pending` 覆盖全部手动运动停止；超时保持连接并重试，不得由前端自行解锁。普通松手请求示教停止+缓停，`stopMotion()` 无条件请求示教停止+受控快速停止。详见 [STOP_AND_ALARMS.md](STOP_AND_ALARMS.md)，该规则取代 1.15.3 文档中的停止确认失败断联规则。

新增 `refreshAlarms()`（仅查询）、`clearAlarm(target: Int)`（0 系统，1～6 指定关节）。`alarms.items` 保留来源、代码和中文描述；`known=false` 表示缺失/过期/断联，不能显示为“无报警”。`alarms.busy` 或 `can_clear=false` 时不能清除，原生仍会复核。清除后必须确认新状态，不自动运动或上使能。
