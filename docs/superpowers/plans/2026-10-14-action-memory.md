# RM65 Action Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add remote waypoint recording, controller-native drag recording, a Chinese-named action library, gripper/wait nodes, and guarded one-click playback to the existing Android WebView controller.

**Architecture:** Pure Kotlin action models and persistence keep the Android/controller mapping explicit. `MainActivity` owns the recording/playback state machine and reuses the existing TCP client and safety snapshots. The WebView remains presentation-only and calls new bridge methods; controller-native drag paths are referenced by short names rather than copied into Android storage.

**Tech Stack:** Kotlin/Android `SharedPreferences`, `org.json`, raw TCP JSON protocol, HTML/CSS/JavaScript WebView, JUnit 4, Node syntax tests.

---

### Task 1: Action domain and persistence

**Files:**
- Create: `app/src/main/java/com/realman/rm65webview/ActionModels.kt`
- Create: `app/src/main/java/com/realman/rm65webview/ActionStore.kt`
- Create: `app/src/test/java/com/realman/rm65webview/ActionModelsTest.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Write failing tests** for JSON round trips, waypoint deduplication at 0.05°, wait validation and joint envelope calculation.
- [ ] **Step 2: Run `gradlew.bat testDebugUnitTest`** and confirm failure because action classes do not exist; if JDK is unavailable, record that environment blocker and run source-independent Node tests later.
- [ ] **Step 3: Implement sealed `ActionNode`, `ActionRecord`, `ActionDraft`, JSON serializers and `ActionStore`.** Use stable field names `id`, `display_name`, `recording_type`, `default_speed`, `nodes`, `created_at`, and `updated_at`.
- [ ] **Step 4: Run unit tests** and confirm model and persistence helpers pass in a JDK-equipped environment.

### Task 2: Controller protocol and response parsing

**Files:**
- Modify: `app/src/main/java/com/realman/rm65webview/RobotProtocol.kt`
- Modify: `app/src/main/java/com/realman/rm65webview/RobotModels.kt`
- Create: `app/src/test/java/com/realman/rm65webview/RobotActionProtocolTest.kt`

- [ ] **Step 1: Write failing protocol tests** asserting exact commands for `start_drag_teach`, `stop_drag_teach`, `save_trajectory_file`, `get_trajectory_file_list`, `set_run_trajectory_file`, pause/continue/stop and waypoint `movej`.
- [ ] **Step 2: Verify RED** because the new protocol methods and response types are absent.
- [ ] **Step 3: Add minimal command builders and parser variants** for trajectory lists, trajectory completion and generic acknowledgements.
- [ ] **Step 4: Verify GREEN** for command JSON and representative old/new controller responses.

### Task 3: Recording and playback state machine

**Files:**
- Create: `app/src/main/java/com/realman/rm65webview/ActionSession.kt`
- Modify: `app/src/main/java/com/realman/rm65webview/MainActivity.kt`
- Create: `app/src/test/java/com/realman/rm65webview/ActionSessionTest.kt`

- [ ] **Step 1: Write failing state tests** for remote capture after a fresh arm frame, drag segment transitions, explicit wait nodes, start-position alignment, sequential playback and stop-on-fault.
- [ ] **Step 2: Verify RED** because `ActionSession` does not exist.
- [ ] **Step 3: Implement recording methods** `startRemoteRecording`, `startDragRecording`, `finishDragSegment`, `insertWait`, `finishRecording`, `cancelRecording`, and controller trajectory refresh/import.
- [ ] **Step 4: Implement playback methods** `runAction`, `pauseAction`, `resumeAction`, `stopAction`, `renameAction`, and `deleteAction`, with safety checks before every motion node.
- [ ] **Step 5: Verify GREEN** and ensure existing jog/zero/gripper behavior remains unchanged outside recording.

### Task 4: Web action library

**Files:**
- Create: `app/src/main/assets/action-domain.js`
- Create: `tests/action-domain.test.js`
- Modify: `app/src/main/assets/index.html`
- Modify: `app/src/main/assets/styles.css`
- Modify: `app/src/main/assets/app.js`

- [ ] **Step 1: Write failing Node tests** for safe action list normalization, node labels and execution confirmation text.
- [ ] **Step 2: Run `node --test tests/action-domain.test.js`** and confirm missing module failure.
- [ ] **Step 3: Implement the pure action UI helpers** in `action-domain.js` and rerun until tests pass.
- [ ] **Step 4: Add the action-library overlay** with record, import, rename, delete, speed, run, pause/resume, stop and explicit wait controls.
- [ ] **Step 5: Wire native events** `action_library`, `recording`, `playback`, and `controller_trajectories` without changing the existing status rendering path.
- [ ] **Step 6: Run `node --check`** for both JavaScript files and the Node tests.

### Task 5: Documentation, version and packaging

**Files:**
- Modify: `docs/BRIDGE.md`
- Modify: `README.md`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/assets/index.html`
- Create: `docs/ACTION_MEMORY.md`

- [ ] **Step 1: Document both recording modes**, controller/local storage boundaries, explicit waits, action execution, recovery and true limitations of imported controller trajectories.
- [ ] **Step 2: Document every new JavaScript bridge method and native event.**
- [ ] **Step 3: Bump the app to version `1.13.0`** in Gradle and the footer.
- [ ] **Step 4: Run all available verification commands:** Node tests, JavaScript syntax, JSON-string scans, Gradle tests/build when JDK exists, and `git diff --check` equivalent whitespace checks for this non-Git directory.
- [ ] **Step 5: Create `RM65_Android_WebView_Direct_Demo_Safe_v1.13.0_source.zip`** excluding build caches and IDE transient files.

## Self-review

- Spec coverage: both recording modes, native controller drag storage, Chinese aliases, action selection, one-click execution, gripper, explicit waits, rename/delete, auto-align and safety stop each map to Tasks 1–4.
- Placeholder scan: no deferred implementation placeholders are used; controller limitations are explicit product behavior rather than hidden work.
- Type consistency: bridge method and event names are fixed in Task 3 and documented in Task 5; action JSON field names are fixed in Task 1.
