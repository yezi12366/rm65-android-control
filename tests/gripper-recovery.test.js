const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/app.js'), 'utf8');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');

function fixture() {
  const elements = new Map(), calls = [];
  const state = { connected: true, playback: { state: 'idle' }, trajectoryCatalog: {},
    motionReady: true, gripperBusy: false, recording: null };
  const $ = id => {
    assert.ok(html.includes(`id="${id}"`), id);
    if (!elements.has(id)) {
      const flags = new Set(), listeners = {};
      elements.set(id, { disabled: false, textContent: '', listeners,
        classList: { toggle(k, on) { if (on) flags.add(k); else flags.delete(k); }, contains: k => flags.has(k) },
        addEventListener: (k, fn) => { listeners[k] = fn; } });
    }
    return elements.get(id);
  };
  const context = vm.createContext({ state, $, document: { querySelectorAll: () => [] },
    nativeCall: (...args) => calls.push(args), Boolean, Math });
  for (const [from, to] of [['function setEnabled(', 'function updateSpeedControl('],
    ['function renderGripperStatus(', 'function refreshActionControls(']]) {
    vm.runInContext(source.slice(source.indexOf(from), source.indexOf(to)), context);
  }
  for (const id of ['gripperInitialize', 'gripperRefresh', 'gripperDiagnostics']) {
    const line = source.split('\n').find(x => x.startsWith(`$("${id}").addEventListener`));
    vm.runInContext(line, context);
  }
  const render = health => {
    context.renderGripperStatus({ connected: state.connected,
      gripper: { status: 1, enable: 1, error: 0, actpos: 400 }, gripper_health: health });
    context.setEnabled(state.connected, state.motionReady);
  };
  return { state, $, calls, context, render };
}

test('cached online data cannot enable controls when native feedback is stale or invalid', () => {
  const f = fixture();
  for (const label of ['夹爪状态超时', '回复异常', '确认供电中']) {
    f.render({ ready: false, label, detail: '保留最后读数，正在重试', age_ms: 4000 });
    assert.equal(f.$('gripperBadge').textContent, label);
    assert.equal(f.$('gripperClose').disabled, true);
    assert.match(f.$('gripperDetail').textContent, /保留最后读数/);
    assert.equal(f.$('gripperPosition').textContent, 400);
  }
  assert.deepEqual(f.calls, []);
});

test('cold attached gripper exposes explicit open initialization and recovers normal controls', () => {
  const f = fixture();
  f.render({ ready: false, can_initialize: true, label: '等待夹爪回复', detail: '点击启用并张开' });
  assert.equal(f.$('gripperInitialize').disabled, false);
  assert.equal(f.$('gripperInitialize').classList.contains('hidden'), false);
  for (const id of ['gripperOpen', 'gripperClose']) assert.equal(f.$(id).disabled, true);
  assert.deepEqual(f.calls, []); // Status rendering alone must never open a gripper.
  f.$('gripperInitialize').listeners.click();
  assert.deepEqual(f.calls, [['initializeGripper']]);
  f.render({ ready: true, can_initialize: false, label: '在线', detail: '状态正常', age_ms: 10 });
  assert.equal(f.$('gripperInitialize').classList.contains('hidden'), true);
  assert.equal(f.$('gripperClose').disabled, false);
});

test('initialization and recovery cannot race playback, recording, jog or an in-flight gripper action', () => {
  for (const barrier of [{ playback: { state: 'running' } }, { recording: { recording_type: 'remote' } },
    { jogActive: true }, { gripperBusy: true }, { stepActive: true }, { teachFramePending: true },
    { recordingInterrupted: true }]) {
    const f = fixture(); Object.assign(f.state, barrier);
    f.render({ ready: false, can_initialize: true, label: '等待启用', detail: '等待' });
    assert.equal(f.$('gripperInitialize').disabled, true, JSON.stringify(barrier));
    assert.equal(f.$('gripperRefresh').disabled, true, JSON.stringify(barrier));
  }
});

test('readiness cannot survive disconnect and diagnostics has its own non-movement entry', () => {
  const f = fixture(); f.state.connected = false;
  f.render({ ready: true, can_initialize: true, label: '在线', detail: '缓存值' });
  assert.equal(f.$('gripperBadge').textContent, '未连接');
  assert.equal(f.$('gripperPosition').textContent, '—');
  assert.equal(f.$('gripperClose').disabled, true);
  assert.equal(f.$('gripperInitialize').disabled, true);
  f.$('gripperDiagnostics').listeners.click();
  assert.deepEqual(f.calls, [['copyGripperDiagnostics']]);
});

test('refresh clicks only request a recheck and never initialize or open implicitly', () => {
  const f = fixture(); f.$('gripperRefresh').listeners.click();
  assert.deepEqual(f.calls, [['refreshGripper']]);
});
