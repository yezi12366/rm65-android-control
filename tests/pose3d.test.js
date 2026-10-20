const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const api = require('../app/src/main/assets/pose3d.js');
const assets = path.join(__dirname, '../app/src/main/assets');
const context = vm.createContext({ console, atob });
vm.runInContext(fs.readFileSync(path.join(assets, 'vendor/three.min.js'), 'utf8'), context);
vm.runInContext('window = {}; ' + fs.readFileSync(path.join(assets, 'rm65-model.js'), 'utf8'), context);
const T = context.THREE, data = context.window.RM65MeshData;
const app = fs.readFileSync(path.join(assets, 'app.js'), 'utf8');
vm.runInContext(app.slice(app.indexOf('const RM65_URDF_JOINTS'), app.indexOf('function drawArm(')), context);
const specs = vm.runInContext('RM65_URDF_JOINTS', context);
const model = api.buildRobot(T, data, specs);
const near = (a, b) => assert.ok(Math.abs(a - b) < 1e-8, `${a} != ${b}`);

test('official solid meshes are finite, in metres, and cover all seven links', () => {
  assert.equal(data.meshes.length, 7);
  assert.equal(data.commit, '9d37f52605aa00c7b0ed8143e5e46b8ffdb9317a');
  const box = new T.Box3().setFromObject(model.robot), size = box.getSize(new T.Vector3());
  assert.ok(size.z > 0.8 && size.z < 1.1);
  assert.ok(size.x < 0.5 && size.y < 0.5);
  model.robot.traverse(node => {
    if (node.isMesh) {
      const positions = node.geometry.attributes.position.array;
      assert.ok(positions.every(Number.isFinite));
      assert.ok(node.geometry.boundingSphere.radius < 0.5);
    }
  });
});

for (const angles of [[0,0,0,0,0,0], [90,-30,60,45,-80,170], [-170,120,-100,-80,110,-90]]) {
  test(`3D hierarchy agrees with the original URDF kinematics: ${angles}`, () => {
    model.update(angles);
    context.angles = angles;
    const points = vm.runInContext('rm65JointPoints(angles)', context);
    model.joints.forEach((joint, i) => {
      const p = joint.getWorldPosition(new T.Vector3());
      near(p.x, points[i+1].x); near(p.y, points[i+1].y); near(p.z, points[i+1].z);
    });
    // Check final orientation too: a sixth-axis error is invisible in a stick preview.
    const expected = vm.runInContext(`RM65_URDF_JOINTS.reduce((m, j, i) =>
      matrixMultiply(matrixMultiply(m, urdfTransform(j.xyz, j.rpy)), rotationZ(angles[i]*Math.PI/180)),
      [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1])`, context);
    const actual = model.joints[5].matrixWorld.elements;
    for (let row = 0; row < 4; row++) for (let col = 0; col < 4; col++) near(actual[col*4+row], expected[row*4+col]);
  });
}

test('invalid joint data leaves the last valid pose intact; duplicate telemetry needs no render', () => {
  const values = [10,20,30,40,50,60];
  assert.equal(model.update(values), true);
  assert.equal(model.update(values), false);
  const before = model.joints.map(j => j.rotation.z);
  for (const invalid of [[1,2], [0,0,0,NaN,0,0], [0,0,0,Infinity,0,0], ['2',0,0,0,0,0]]) {
    assert.equal(model.update(invalid), false);
    assert.deepEqual(model.joints.map(j => j.rotation.z), before);
  }
});

function gestureFixture() {
  const listeners = new Map();
  const canvas = { addEventListener: (k, f) => listeners.set(k, f),
    removeEventListener: k => listeners.delete(k), setPointerCapture() {} };
  const view = { azimuth: 0, elevation: 0, distance: 2 };
  let updates = 0;
  const orbit = api.bindOrbit(canvas, view, () => updates++);
  const fire = (name, id, x = 0, y = 0, extra = {}) => listeners.get(name)?.({
    pointerId: id, clientX: x, clientY: y, button: 0, preventDefault() {}, ...extra });
  return { view, orbit, fire, listeners, updates: () => updates };
}

test('orbit release, cancellation and capture loss clear only their own pointer', () => {
  for (const release of ['pointerup','pointercancel','lostpointercapture']) {
    const f = gestureFixture();
    f.fire('pointerdown', 1); f.fire('pointermove', 1, 30, 20);
    assert.notEqual(f.view.azimuth, 0);
    f.fire(release, 2); f.fire('pointermove', 1, 40, 30);
    const before = { ...f.view };
    f.fire(release, 1); f.fire('pointermove', 1, 200, 200);
    assert.deepEqual(f.view, before);
    f.orbit.dispose(); assert.equal(f.listeners.size, 0);
  }
});

test('two-finger pinch zooms; returning to one finger has no jump; zoom stays bounded', () => {
  const f = gestureFixture();
  f.fire('pointerdown', 1, 0, 0); f.fire('pointerdown', 2, 100, 0);
  f.fire('pointermove', 2, 200, 0);
  assert.equal(f.view.distance, 1); assert.equal(f.view.azimuth, 0);
  f.fire('pointerup', 2); f.fire('pointermove', 1, 0, 0);
  assert.equal(f.view.azimuth, 0);
  f.orbit.zoom(100); assert.equal(f.view.distance, 6);
  f.orbit.zoom(0.0001); assert.equal(f.view.distance, 0.25);
  f.orbit.clear(); const before = f.updates();
  f.fire('pointermove', 1, 50, 50); assert.equal(f.updates(), before);
});

test('pose status distinguishes schematic, disconnected, fresh and stale data', () => {
  assert.match(api.poseLabel(false, false, 0), /零位示意/);
  assert.match(api.poseLabel(true, false, 0), /等待/);
  assert.match(api.poseLabel(false, true, 0), /未连接.*最后姿态/);
  assert.match(api.poseLabel(true, true, 200), /实时/);
  assert.match(api.poseLabel(true, true, 1000), /过期/);
  assert.match(api.poseLabel(true, true, Infinity), /过期/);
});

test('all page scripts load from the APK; no module/fetch or robot bridge is needed by the viewer', () => {
  const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
  for (const [, src] of html.matchAll(/<script src="([^"]+)"/g)) {
    assert.doesNotMatch(src, /^https?:/); assert.ok(fs.existsSync(path.join(assets, src)), src);
  }
  const viewer = fs.readFileSync(path.join(assets, 'pose3d.js'), 'utf8');
  assert.doesNotMatch(viewer, /RobotBridge|nativeCall|fetch\(|XMLHttpRequest/);
});

test('joint labels match bending and rotation controls with distinct colors', () => {
  assert.equal(api.jointStyles.length,6);
  api.jointStyles.forEach((style,i)=>{
    const bending=[2,3,5].includes(i+1);
    assert.equal(style.label,`J${i+1} · ${bending?'弯曲':'旋转'}`);
    assert.equal(style.color,bending?'#4ee2b8':'#40a7ff');
  });
});

test('coincident joint projections get separate legible labels inside the canvas', () => {
  const labels=api.layoutJointLabels(Array.from({length:6},()=>({x:170,y:160,z:0})),340,320);
  labels.forEach((box,i)=>{
    assert.equal(box.visible,true);assert.ok(box.x>=0&&box.y>=0&&box.x+box.w<=340&&box.y+box.h<=320);
    for(const other of labels.slice(0,i))assert.ok(box.x+box.w<=other.x||other.x+other.w<=box.x||box.y+box.h<=other.y||other.y+other.h<=box.y);
  });
});

test('joint labels move with projected anchors and hide invalid or offscreen joints', () => {
  const a=api.layoutJointLabels([{x:100,y:100,z:0}],340,320)[0];
  const b=api.layoutJointLabels([{x:210,y:210,z:0}],340,320)[0];
  assert.notEqual(a.x,b.x);assert.notEqual(a.y,b.y);assert.equal(b.pointX,210);assert.equal(b.pointY,210);
  for(const p of [{x:NaN,y:20,z:0},{x:100,y:100,z:2},{x:-10,y:100,z:0}])assert.equal(api.layoutJointLabels([p],340,320)[0].visible,false);
});
