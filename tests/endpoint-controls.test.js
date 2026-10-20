const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/app.js'), 'utf8');

function fixture() {
  const calls = [], elements = new Map();
  function element(mode = 'joint', axis = '1', direction = '1') {
    const flags = new Set(), listeners = {};
    return {
      children: [],
      append(...children) { this.children.push(...children); },
      replaceChildren(...children) { this.children = [...children]; },
      dataset: { mode, axis, direction }, disabled: false,
      classList: { add: key => flags.add(key), remove: key => flags.delete(key), contains: key => flags.has(key),
        toggle(key, on) { if (on) flags.add(key); else flags.delete(key); } },
      setPointerCapture() {},
      addEventListener(name, fn) { (listeners[name] ||= []).push(fn); },
      dispatch(name, pointerId, detail = 1) {
        for (const fn of listeners[name] || []) fn({ pointerId, detail, preventDefault() {} });
      },
    };
  }
  const joint = element(), move = element('position', 'x'), orientation = element('orientation', 'rx');
  const state = { connected: true, motionReady: true, speed: 5, stepMm: 0, stepActive: false,
    teachFrameReady: true, teachFramePending: false, jogActive: false,
    gripperBusy: false, gripperAvailable: true, recordingInterrupted: false,
    trajectoryCatalog: { received: true, loading: false, error: '' },
    activeButton: null, activePointerId: null, playback: { state: 'idle' }, recording: null,
    jogWatchdogEnabled: false };
  const context = vm.createContext({
    state, Number, Boolean, clearInterval() {}, setInterval() { return 1; },
    connectionBadge: element(), performance: { now: () => 100 },
    updateEndpointLabels() {}, updateSpeedControl() {}, updateStateAgeDisplay() {}, drawArm() {}, showMessage() {},
    $: id => { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); },
    document: { createElement: () => element(), querySelectorAll(selector) {
      if (selector === '.jog-button') return [joint, move, orientation];
      if (selector === '.jog-button[data-mode="joint"]') return [joint];
      if (selector === '.jog-button:not([data-mode="joint"])') return [move, orientation];
      return [];
    } },
    nativeCall(name, ...args) { calls.push({ name, args }); return true; },
    refreshActionControls() {},
  });
  const ranges = [
    ['function bindHoldButton(', 'makeControls($("jointControls")'],
    ['function startJog(', 'function startSavedReset('],
    ['function nextHoldId(', 'function updateSpeedControl('],
    ['function applyDirectionalLimitButtons(', 'function updateStatus('],
    ['function updateStatus(', 'function refreshActionControls('],
  ];
  for (const [start, end] of ranges) {
    const begin = source.indexOf(start), finish = source.indexOf(end, begin);
    assert.ok(begin >= 0 && finish > begin);
    vm.runInContext(source.slice(begin, finish), context);
  }
  context.bindHoldButton(move);
  return { context, state, calls, elements, joint, move, orientation };
}

test('only the pointer holding a direction may release continuous motion', () => {
  const f = fixture();
  f.move.dispatch('pointerdown', 7);
  f.move.dispatch('pointerup', 8);
  assert.equal(f.state.activeButton, f.move);
  assert.equal(f.calls.filter(c => c.name === 'stopJog').length, 0);
  f.move.dispatch('pointerup', 7);
  f.move.dispatch('lostpointercapture', 7);
  assert.equal(f.state.activeButton, null);
  assert.equal(f.calls.filter(c => c.name === 'stopJog').length, 1);
});

test('touch cancellation stops the held direction', () => {
  const f = fixture();
  f.move.dispatch('pointerdown', 4);
  f.move.dispatch('pointercancel', 4);
  assert.equal(f.calls.at(-1).name, 'stopJog');
});

test('fixed-distance steps do not become hold motion or stop on finger release', () => {
  const f = fixture();
  f.state.stepMm = 5;
  f.move.dispatch('pointerdown', 7);
  f.move.dispatch('pointerup', 7);
  assert.equal(f.calls[0].name, 'stepPosition');
  assert.deepEqual(Array.from(f.calls[0].args), ['x', 1, 5, 5]);
  assert.equal(f.calls.length, 1);
  assert.equal(f.state.stepActive, true);
  assert.equal(f.move.disabled, true);
  assert.equal(f.elements.get('gripperClose').disabled, true);
});

test('a second press cannot queue another active step', () => {
  const f = fixture();
  f.state.stepMm = 1;
  f.move.dispatch('pointerdown', 7);
  f.move.dispatch('pointerdown', 8);
  assert.equal(f.calls.length, 1);
});

test('opening a dialog or losing focus explicitly stops a pending step', () => {
  const f = fixture();
  f.state.stepActive = true;
  f.context.stopManualMotion();
  assert.equal(f.calls.at(-1).name, 'stopJog');
});

test('unconfirmed coordinate mode disables all Cartesian controls but allows joint recovery', () => {
  const f = fixture();
  f.state.teachFrameReady = false;
  f.context.setEnabled(true, true);
  assert.equal(f.joint.disabled, false);
  assert.equal(f.move.disabled, true);
  assert.equal(f.orientation.disabled, true);
});

test('directional limit refresh cannot re-enable controls during a step or recording barrier', () => {
  const f = fixture();
  const data = { arm: { joint: [0,0,0,0,0,0] }, limits: {
    control_joint_min: [-180,-180,-180,-180,-180,-180],
    control_joint_max: [180,180,180,180,180,180], joint_limit_margin_deg: [10,10,10,10,10,10] } };
  f.state.stepActive = true;
  f.context.setEnabled(true, true);
  f.context.applyDirectionalLimitButtons(data, true);
  assert.equal(f.move.disabled, true);
  f.state.stepActive = false;
  f.state.recording = { recording_type: 'remote', waiting_for_waypoint: true };
  f.context.setEnabled(true, true);
  f.context.applyDirectionalLimitButtons(data, true);
  assert.equal(f.joint.disabled, true);
  assert.equal(f.move.disabled, true);
});

test('preview simulation never replaces an existing Android bridge', () => {
  const bridge = { original: true };
  const context = vm.createContext({ window: { RobotBridge: bridge } });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/preview-bridge.js'), 'utf8'), context);
  assert.equal(context.window.RobotBridge, bridge);
});

test('normal browser entry never enables the simulation implicitly', () => {
  const context = vm.createContext({ window: {}, location: { search: '' }, URLSearchParams });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/preview-bridge.js'), 'utf8'), context);
  assert.equal(context.window.RobotBridge, undefined);
});

test('limit refresh preserves pending gripper, recovery and coordinate barriers', () => {
  const data = { arm: { joint: [0,0,0,0,0,0] }, limits: {
    control_joint_min: [-180,-180,-180,-180,-180,-180],
    control_joint_max: [180,180,180,180,180,180], joint_limit_margin_deg: [10,10,10,10,10,10] } };
  for (const barrier of [
    { gripperBusy: true },
    { recording: { recording_type: 'remote', gripper_pending: true } },
    { recordingInterrupted: true },
    { recording: { recording_type: 'remote', interrupted: true, recording_error: '断线' } },
    { teachFramePending: true },
  ]) {
    const f = fixture();
    Object.assign(f.state, barrier);
    f.context.setEnabled(true, true);
    f.context.applyDirectionalLimitButtons(data, true);
    assert.equal(f.joint.disabled, true);
    assert.equal(f.move.disabled, true);
    assert.equal(f.orientation.disabled, true);
    assert.equal(f.elements.get('gripperClose').disabled, true);
  }
});

test('active step blocks action playback and recording even with healthy position frames', () => {
  const f = fixture();
  f.state.stepActive = true;
  f.context.setEnabled(true, true);
  for (const id of ['zeroButton', 'startRemoteRecordingButton', 'startDragRecordingButton', 'importTrajectoryButton']) {
    assert.equal(f.elements.get(id).disabled, true);
  }
});

function healthyStatus(jogActive) {
  return { connected: true, jog_active: jogActive, motion_ready: true, action_playback_state: 'idle',
    robot: { ip: '192.168.33.80', port: 8080 },
    limits: { jog_watchdog_enabled: false },
    joints: { joint_en_flag: [1,1,1,1,1,1], joint_err_code: [0,0,0,0,0,0], system_error: [0] } };
}

test('stale idle status between press and release cannot swallow the stop command', () => {
  const f = fixture();
  f.move.dispatch('pointerdown', 7);
  f.context.updateStatus(healthyStatus(false));
  f.context.updateStatus(healthyStatus(true));
  f.move.dispatch('pointerup', 7);
  assert.equal(f.calls.filter(c => c.name === 'stopJog').length, 1);
});

test('releasing before start acknowledgement still sends stop after a stale idle frame', () => {
  const f = fixture();
  f.move.dispatch('pointerdown', 7);
  f.context.updateStatus(healthyStatus(false));
  f.move.dispatch('pointerup', 7);
  assert.equal(f.calls.at(-1).name, 'stopJog');
});

test('window cancellation and native active fallback stop a hold even without a button latch', () => {
  const f = fixture();
  f.context.updateStatus(healthyStatus(true));
  f.context.stopManualMotion();
  assert.equal(f.calls.at(-1)?.name, 'stopJog');
});

test('native physical release clears the local gesture even when DOM release is missing', () => {
  const f = fixture();
  f.move.dispatch('pointerdown', 7);
  f.context.finishNativeHold({hold_id: f.state.activeHoldId});
  assert.equal(f.state.activeButton, null);
  assert.equal(f.state.activePointerId, null);
  assert.equal(f.calls.at(-1).name, 'stopJog');
});

test('late native release from a previous gesture cannot stop a new hold', () => {
  const f = fixture();
  f.move.dispatch('pointerdown', 7);
  const oldId = f.state.activeHoldId;
  f.move.dispatch('pointerup', 7);
  f.move.dispatch('pointerdown', 8);
  f.context.finishNativeHold({hold_id: oldId});
  assert.equal(f.state.activeButton, f.move);
  assert.equal(f.state.activePointerId, 8);
  f.move.dispatch('pointerup', 8);
});

function bendFixture() {
  const f = fixture(), timers = new Map();
  vm.runInContext(source.slice(source.indexOf('function bindCoupledBend('),source.indexOf('function nextHoldId(')),f.context);
  f.context.setInterval = callback => { timers.set(1,callback); return 1; };
  f.context.clearInterval = id => timers.delete(id);
  f.context.refreshActionControls = () => f.context.setEnabled(f.state.connected,f.state.motionReady);
  f.context.setEnabled(true,true);
  const button=f.elements.get('bendPositive');
  button.dataset={coupled:'true',direction:'1'};
  f.context.bindCoupledBend(button);
  return {...f,button,timers};
}

test('coupled bend requires physical press and does not queue another move', () => {
  const f=bendFixture();
  f.button.dispatch('click',7,0);assert.equal(f.calls.length,0);
  f.button.dispatch('pointerdown',7);
  assert.equal(f.calls[0].name,'startCoupledBend');
  assert.deepEqual(Array.from(f.calls[0].args),[1,5,f.state.activeHoldId]);
  f.button.dispatch('pointerdown',8);
  assert.equal(f.calls.filter(c=>c.name==='startCoupledBend').length,1);
});

test('coupled bend keeps mandatory heartbeat and release through stale idle telemetry with jog watchdog off', () => {
  const f=bendFixture(); f.button.dispatch('pointerdown',7);
  assert.equal(f.state.jogWatchdogEnabled,false);
  assert.equal(f.calls.at(-1).name,'jogHeartbeat');assert.equal(f.timers.size,1);
  f.context.updateStatus(healthyStatus(false));
  assert.equal(f.state.activeButton,f.button);assert.equal(f.timers.size,1);
  assert.equal(f.button.disabled,false);
  for(const id of ['bendNegative','zeroButton','gripperClose','gripperInitialize','startRemoteRecordingButton','startDragRecordingButton']) assert.equal(f.elements.get(id).disabled,true,id);
  f.timers.get(1)(); assert.equal(f.calls.at(-1).name,'jogHeartbeat');
  f.button.dispatch('pointerup',8); assert.equal(f.state.activeButton,f.button);
  f.button.dispatch('pointerup',7); assert.equal(f.calls.at(-1).name,'stopJog');assert.equal(f.timers.size,0);
});

test('native release, cancellation and disconnect all clear coupled bend gesture', () => {
  for(const release of ['native','cancel','disconnect']) {
    const f=bendFixture();f.button.dispatch('pointerdown',7);
    if(release==='native')f.context.finishNativeHold({hold_id:f.state.activeHoldId});
    else if(release==='cancel')f.button.dispatch('pointercancel',7);
    else f.context.updateStatus({...healthyStatus(false),connected:false});
    assert.equal(f.state.activeButton,null);assert.equal(f.timers.size,0);assert.equal(f.calls.at(-1).name,'stopJog');
  }
});

test('coupled bend cannot start during recording, playback, gripper action, step or stale feedback', () => {
  for(const barrier of [{recording:{recording_type:'remote'}},{playback:{state:'running'}},{gripperBusy:true},{stepActive:true},{teachFramePending:true},{motionReady:false}]) {
    const f=bendFixture();Object.assign(f.state,barrier);f.context.setEnabled(true,f.state.motionReady);
    assert.equal(f.button.disabled,true);f.button.dispatch('pointerdown',7);assert.equal(f.calls.length,0);
  }
});

test('joint cards show bending or rotation beside every joint and in the accessible button name', () => {
  const cards=[];
  const context=vm.createContext({
    document:{createElement(){const card={innerHTML:'',children:[],appendChild(c){this.children.push(c);},querySelector(){return this;},dataset:{},setAttribute(k,v){this[k]=v;}};return card;}},
    bindHoldButton(){}, $(){return {appendChild(c){cards.push(c);}};}
  });
  vm.runInContext(source.slice(source.indexOf('function makeControls('),source.indexOf('function bindHoldButton(')),context);
  vm.runInContext(source.slice(source.indexOf('makeControls($("jointControls")'),source.indexOf('makeControls($("orientationControls")')),context);
  assert.equal(cards.length,6);
  cards.forEach((card,i)=>{
    const type=[2,3,5].includes(i+1)?'弯曲':'旋转';
    assert.match(card.innerHTML,new RegExp(`<strong>J${i+1}</strong><span>${type}</span>`));
    card.children.forEach(button=>assert.match(button['aria-label'],new RegExp(`J${i+1}.*${type}`)));
  });
});

test('rollback removes experimental singularity and 3D target movement APIs', () => {
  const native=path.join(__dirname,'../app/src/main/java/com/realman/rm65webview');
  const main=fs.readFileSync(path.join(native,'MainActivity.kt'),'utf8');
  assert.equal(fs.existsSync(path.join(native,'SingularityAssist.kt')),false);
  assert.equal(fs.existsSync(path.join(native,'PoseTarget.kt')),false);
  assert.doesNotMatch(main,/fun (?:analyzeSingularity|startSingularityAssist|startPoseTarget|preparePoseTarget)/);
  assert.doesNotMatch(source,/nativeCall\("(?:analyzeSingularity|startSingularityAssist|startPoseTarget|preparePoseTarget)"/);
});

function resetFixture() {
  const f=bendFixture();
  vm.runInContext(source.slice(source.indexOf('function startSavedReset('),source.indexOf('function bindCoupledBend(')),f.context);
  vm.runInContext(source.slice(source.indexOf('$("saveResetButton").addEventListener'),source.indexOf('$("speedMinus").addEventListener')),f.context);
  const button=f.elements.get('zeroButton');button.id='zeroButton';
  return {...f,button};
}

test('saving reset pose is separate from motion and a missing saved pose disables reset', () => {
  const f=resetFixture();f.context.setEnabled(true,true);
  assert.equal(f.button.disabled,true);
  f.button.dispatch('pointerdown',4);assert.equal(f.calls.length,0);
  f.elements.get('saveResetButton').dispatch('click',4);
  assert.equal(f.calls.length,1);assert.equal(f.calls[0].name,'saveResetPose');
  f.state.resetPoseSaved=true;f.context.setEnabled(true,true);
  assert.equal(f.button.disabled,false);
});

test('saved reset uses a mandatory held heartbeat and survives stale idle frames until release', () => {
  const f=resetFixture();f.state.resetPoseSaved=true;f.context.setEnabled(true,true);
  f.button.dispatch('pointerdown',4);
  assert.equal(f.calls[0].name,'startSavedReset');assert.equal(f.timers.size,1);
  f.context.updateStatus({...healthyStatus(false),reset_pose_saved:true});
  assert.equal(f.timers.size,1);assert.equal(f.button.disabled,false);
  for(const id of ['saveResetButton','gripperOpen','gripperClose','bendPositive','startRemoteRecordingButton']) assert.equal(f.elements.get(id).disabled,true,id);
  f.button.dispatch('pointerup',5);assert.equal(f.state.activeButton,f.button);
  f.button.dispatch('pointerup',4);assert.equal(f.calls.at(-1).name,'stopJog');assert.equal(f.timers.size,0);
});

test('native release and cancellation stop saved reset and stale release cannot stop the next hold', () => {
  const f=resetFixture();f.state.resetPoseSaved=true;f.context.setEnabled(true,true);
  f.button.dispatch('pointerdown',4);const old=f.state.activeHoldId;
  f.context.finishNativeHold({hold_id:old});assert.equal(f.state.activeButton,null);
  f.context.setEnabled(true,true);f.button.dispatch('pointerdown',5);
  f.context.finishNativeHold({hold_id:old});assert.equal(f.state.activeButton,f.button);
  f.button.dispatch('pointercancel',5);assert.equal(f.calls.at(-1).name,'stopJog');assert.equal(f.timers.size,0);
});

test('gripper UI exposes only open and close and obsolete zero-return bridge is removed', () => {
  const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/index.html'),'utf8');
  assert.doesNotMatch(html,/id="(?:gripperMove|gripperSlider|gripperTarget)"/);
  assert.match(html,/id="gripperOpen"/);assert.match(html,/id="gripperClose"/);
  const main=fs.readFileSync(path.join(__dirname,'../app/src/main/java/com/realman/rm65webview/MainActivity.kt'),'utf8');
  assert.doesNotMatch(main,/fun (?:startReturnToZero|gripperPosition)\(/);
});

test('pending stop confirmation locks every actuation despite otherwise healthy telemetry', () => {
  const f=fixture();f.state.manualStopPending=true;f.state.resetPoseSaved=true;
  f.context.setEnabled(true,true);
  f.context.applyDirectionalLimitButtons({arm:{joint:[0,0,0,0,0,0]},limits:{control_joint_min:Array(6).fill(-180),control_joint_max:Array(6).fill(180),joint_limit_margin_deg:Array(6).fill(10)}},true);
  for(const button of [f.joint,f.move,f.orientation])assert.equal(button.disabled,true);
  for(const id of ['gripperOpen','gripperClose','zeroButton','saveResetButton','bendPositive','bendNegative','startRemoteRecordingButton','startDragRecordingButton'])assert.equal(f.elements.get(id).disabled,true,id);
  f.state.manualStopPending=false;f.context.setEnabled(true,true);
  assert.equal(f.joint.disabled,false);assert.equal(f.elements.get('zeroButton').disabled,false);
});

test('reset and coupled bend pass the selected top speed through the bridge', () => {
  const b=bendFixture();b.state.speed=23;b.button.dispatch('pointerdown',7);
  assert.equal(b.calls[0].args[1],23);
  const r=resetFixture();r.state.speed=17;r.state.resetPoseSaved=true;r.context.setEnabled(true,true);r.button.dispatch('pointerdown',8);
  assert.equal(r.calls[0].args[0],17);
});

test('J5 always sends gesture-specific heartbeats even when the legacy watchdog is disabled', () => {
  const f=fixture();let beat=null,cleared=0;
  f.context.setInterval=(fn,ms)=>{assert.equal(ms,150);beat=fn;return 77;};
  f.context.clearInterval=()=>{cleared++;};
  f.joint.dataset.axis='5';f.context.bindHoldButton(f.joint);
  f.joint.dispatch('pointerdown',9);
  const start=f.calls.find(c=>c.name==='startJog');
  assert.deepEqual(Array.from(start.args).slice(0,2),['joint','5']);
  const hold=start.args.at(-1);
  assert.deepEqual(Array.from(f.calls.at(-1).args),[hold]);
  f.context.updateStatus(healthyStatus(true));beat();
  assert.equal(f.calls.at(-1).name,'jogHeartbeat');
  assert.deepEqual(Array.from(f.calls.at(-1).args),[hold]);
  f.joint.dispatch('pointerup',9);
  assert.equal(f.calls.at(-1).name,'stopJog');assert.ok(cleared>0);
});

test('the immediate-stop button reaches native stop even when local UI thinks the arm is idle', () => {
  const f=fixture();
  vm.runInContext(source.slice(source.indexOf('$("stopButton").addEventListener'),source.indexOf('$("saveResetButton").addEventListener')),f.context);
  f.elements.get('stopButton').dispatch('click',1);
  assert.equal(f.calls.at(-1).name,'stopMotion');
});
