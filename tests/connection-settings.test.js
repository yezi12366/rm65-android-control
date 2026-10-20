const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/app.js'), 'utf8');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
function fixture() {
  const elements = new Map(), calls = [];
  function $(id) {
    assert.ok(html.includes(`id="${id}"`), `HTML element missing: ${id}`);
    if (!elements.has(id)) {
      const flags = new Set(id === 'settingsOverlay' ? ['hidden'] : []), listeners = new Map();
      elements.set(id, { value: '', checked: false, disabled: false, textContent: '', focus() {},
        classList: { add: x => flags.add(x), remove: x => flags.delete(x), contains: x => flags.has(x) },
        addEventListener: (event, fn) => listeners.set(event, fn),
        click() { listeners.get('click')?.(); },
      });
    }
    return elements.get(id);
  }
  const state = { connected: false, playback: {state:'idle'}, recording: null, gripperAvailable: true,
    trajectoryCatalog:{received:true,loading:false,error:''}, motionReady:true };
  const ctx = vm.createContext({ $, state, Number, Boolean,
    document:{querySelectorAll:()=>[]},
    stopJog(){}, stopManualMotion(){}, snapshotJointLimitForm:()=>[], restoreJointLimitForm(){},
    readJointLimitForm:()=>({skip:true}),
    nativeCall(name,...args){ calls.push({name,args}); return true; },
  });
  for (const [start,end] of [
    ['function setEnabled(', 'function updateSpeedControl('],
    ['function syncConnectionEndpoint(', 'function updateStatus('],
    ['let savedEndpoint = null;', '$("watchdogEnabled").addEventListener("change"'],
  ]) {
    const a=source.indexOf(start), b=source.indexOf(end,a);
    assert.ok(a>=0&&b>a);
    vm.runInContext(source.slice(a,b),ctx);
  }
  $('robotIp').value='192.168.33.80'; $('robotPort').value='8080'; $('watchdogSeconds').value='5';
  return {ctx,$,state,calls};
}
test('IP draft survives repeated status refreshes and switching to the port field',()=>{
  const f=fixture(); f.$('settingsButton').click();
  f.$('robotIp').value='192.168.33.81'; f.$('robotPort').value='8081';
  for(let n=0;n<4;n++) f.ctx.syncConnectionEndpoint({robot:{ip:'192.168.33.80',port:8080}});
  assert.equal(f.$('robotIp').value,'192.168.33.81'); assert.equal(f.$('robotPort').value,'8081');
  assert.equal(f.$('robotAddress').textContent,'192.168.33.80:8080');
});
test('save writes edited endpoint through bridge and closes only after native acknowledgement',()=>{
  const f=fixture(); f.$('settingsButton').click(); f.$('robotIp').value='192.168.33.81'; f.$('robotPort').value='8081';
  f.$('settingsSave').click();
  assert.deepEqual(Array.from(f.calls.find(x=>x.name==='saveConnectionSettings').args),['192.168.33.81',8081]);
  assert.equal(f.$('settingsSave').disabled,true); assert.equal(f.$('settingsOverlay').classList.contains('hidden'),false);
  f.ctx.finishConnectionSettingsSave({success:true});
  assert.equal(f.$('settingsSave').disabled,false); assert.equal(f.$('settingsOverlay').classList.contains('hidden'),true);
  f.ctx.syncConnectionEndpoint({robot:{ip:'192.168.33.81',port:8081}});
  assert.equal(f.$('robotIp').value,'192.168.33.81'); assert.equal(f.$('robotPort').value,8081);
});
test('cancel restores endpoint without writing a new address',()=>{
  const f=fixture(); f.$('settingsButton').click(); f.$('robotIp').value='192.168.33.99'; f.$('settingsCancel').click();
  assert.equal(f.$('robotIp').value,'192.168.33.80'); assert.equal(f.calls.length,0);
});
test('native rejection retains editable draft and displays the reason',()=>{
  const f=fixture(); f.$('settingsButton').click(); f.$('robotIp').value='192.168.33.81';
  f.ctx.finishConnectionSettingsSave({success:false,error:'请先断开当前连接'});
  assert.equal(f.$('settingsOverlay').classList.contains('hidden'),false);
  assert.equal(f.$('settingsError').textContent,'请先断开当前连接'); assert.equal(f.$('robotIp').value,'192.168.33.81');
});
test('connected settings provide a disconnect entry and enable editing after disconnection',()=>{
  const f=fixture(); f.ctx.setEnabled(true,true);
  assert.equal(f.$('robotIp').disabled,true); assert.equal(f.$('settingsDisconnect').disabled,false);
  f.$('settingsDisconnect').click(); assert.equal(f.calls.at(-1).name,'disconnect');
  f.ctx.setEnabled(false,false); assert.equal(f.$('robotIp').disabled,false); assert.equal(f.$('robotPort').disabled,false);
});
test('invalid port never reaches native persistence',()=>{
  const f=fixture(); f.$('settingsButton').click(); f.$('robotPort').value='65536'; f.$('settingsSave').click();
  assert.equal(f.calls.length,0); assert.match(f.$('settingsError').textContent,/65535/);
});
