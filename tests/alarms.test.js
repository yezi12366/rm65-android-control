const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/app.js'), 'utf8');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');

function fixture() {
  const elements = new Map(), calls = [];
  function element() {
    const flags = new Set();
    return { children: [], listeners: {}, disabled: false, textContent: '',
      classList: { toggle(key, on) { if (on) flags.add(key); else flags.delete(key); }, contains: key => flags.has(key) },
      append(...children) { this.children.push(...children); },
      replaceChildren(...children) { this.children = [...children]; },
      addEventListener(event, fn) { this.listeners[event] = fn; } };
  }
  const $ = id => {
    assert.ok(html.includes(`id="${id}"`), id);
    if (!elements.has(id)) elements.set(id, element());
    return elements.get(id);
  };
  const context = vm.createContext({ $, document: { createElement: element }, nativeCall: (...args) => calls.push(args) });
  vm.runInContext(source.slice(source.indexOf('let alarmListSignature ='), source.indexOf('function renderGripperStatus(')), context);
  for (const id of ['alarmRefresh', 'alarmClearSystem']) vm.runInContext(source.split('\n').find(line => line.startsWith(`$("${id}").addEventListener`)), context);
  const items = [
    { code: 4099, hex: '0x1003', target: 0, source: '系统', label: '不可达／奇异点', severity: '一般', hint: '调整路径' },
    { code: 4, hex: '0x0004', target: 2, source: 'J2 关节', label: '欠压', severity: '一般', hint: '检查供电' }
  ];
  const render = (changes = {}) => context.renderAlarmStatus({ connected: true,
    alarms: { known: true, items, can_clear: true, busy: false, arm_age_ms: 10, safety_age_ms: 20, ...changes } });
  return { $, calls, render, context };
}
test('renders system and joint faults with raw codes and only explicit clear clicks call bridge', () => {
  const f = fixture(); f.render();
  assert.equal(f.$('alarmButton').textContent, '报警 2');
  const rows = f.$('alarmList').children;
  assert.match(rows[0].children[0].textContent, /0x1003（4099）/);
  assert.match(rows[1].children[0].textContent, /J2 关节/);
  assert.deepEqual(f.calls, []);
  f.$('alarmClearSystem').listeners.click();
  rows[1].children[2].listeners.click();
  assert.deepEqual(f.calls, [['clearAlarm', 0], ['clearAlarm', 2]]);
});
test('missing, expired and disconnected states cannot appear as no alarms or enable clearing', () => {
  const f = fixture();
  f.render({ known: false });
  assert.match(f.$('alarmSummary').textContent, /已过期/);
  assert.equal(f.$('alarmClearSystem').disabled, true);
  assert.equal(f.$('alarmList').children[1].children[2].disabled, true);
  f.context.renderAlarmStatus({ connected: false, alarms: { known: true, items: [] } });
  assert.match(f.$('alarmSummary').textContent, /未连接/);
  assert.equal(f.$('alarmRefresh').disabled, true);
  assert.notEqual(f.$('alarmButton').textContent, '无报警');
});
test('clear in progress and active motion disable clearing while displaying native result', () => {
  const f = fixture();
  for (const barrier of [{ busy: true }, { can_clear: false }]) {
    f.render({ ...barrier, message: '重新读取确认中', clear_block_reason: '请先停止' });
    assert.equal(f.$('alarmClearSystem').disabled, true);
    assert.equal(f.$('alarmList').children[1].children[2].disabled, true);
    assert.equal(f.$('alarmOperation').textContent, '重新读取确认中');
  }
  assert.deepEqual(f.calls, []);
});
test('healthy state hides old faults and refresh never clears alarms', () => {
  const f = fixture(); f.render(); f.render({ items: [] });
  assert.equal(f.$('alarmList').children.length, 0);
  assert.equal(f.$('alarmButton').textContent, '无报警');
  assert.equal(f.$('alarmClearSystem').disabled, true);
  f.$('alarmRefresh').listeners.click();
  assert.deepEqual(f.calls, [['refreshAlarms']]);
});
