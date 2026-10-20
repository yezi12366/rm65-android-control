/* Offline pose display. This module has no access to the robot control bridge. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.RM65Pose3D = api;
})(typeof window === 'object' ? window : globalThis, function () {
  'use strict';
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  function validJoints(joints) {
    return Array.isArray(joints) && joints.length >= 6 &&
      joints.slice(0, 6).every(v => typeof v === 'number' && Number.isFinite(v));
  }
  function poseLabel(connected, hasPose, age) {
    if (!hasPose) return connected ? '等待关节数据' : '未连接 · 零位示意';
    if (!connected) return '未连接 · 最后姿态';
    return age < 1000 ? '实时关节姿态' : '数据过期 · 最后姿态';
  }

  // Pointer ownership is local to this canvas; leaving/cancelling never leaves a drag latched.
  function bindOrbit(canvas, view, change) {
    const pointers = new Map(), listeners = [];
    const on = (name, fn, options) => {
      canvas.addEventListener(name, fn, options);
      listeners.push([name, fn, options]);
    };
    const span = () => {
      const p = [...pointers.values()];
      return p.length < 2 ? 0 : Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y);
    };
    function zoom(factor) {
      view.distance = clamp(view.distance * factor, 0.25, 6);
      change();
    }
    on('pointerdown', e => {
      if (e.button != null && e.button !== 0) return;
      e.preventDefault();
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      canvas.setPointerCapture?.(e.pointerId);
    });
    on('pointermove', e => {
      const previous = pointers.get(e.pointerId);
      if (!previous) return;
      e.preventDefault();
      const before = span();
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (pointers.size === 1) {
        view.azimuth -= (e.clientX - previous.x) * 0.009;
        view.elevation = clamp(view.elevation + (e.clientY - previous.y) * 0.009, -1.4, 1.55);
        change();
      } else {
        const after = span();
        if (before > 2 && after > 2) zoom(before / after);
      }
    });
    const release = e => pointers.delete(e.pointerId);
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(name => on(name, release));
    on('wheel', e => {
      e.preventDefault();
      zoom(Math.exp(clamp(e.deltaY, -100, 100) * 0.002));
    }, { passive: false });
    return { zoom, clear: () => pointers.clear(), dispose() {
      listeners.forEach(args => canvas.removeEventListener(...args));
      pointers.clear();
    } };
  }

  function buildRobot(T, data, specs) {
    const robot = new T.Group(), joints = [];
    let previous = null;
    let parent = robot;
    const silver = new T.MeshPhongMaterial({ color: 0xd7e4e9, specular: 0x718493, shininess: 55 });
    const flange = new T.MeshPhongMaterial({ color: 0x70cdbb, specular: 0x52756e, shininess: 50 });
    data.meshes.forEach((part, index) => {
      if (index > 0) {
        const origin = new T.Group(), turn = new T.Group(), spec = specs[index - 1];
        origin.position.fromArray(spec.xyz);
        // URDF fixed-axis Rz(yaw) Ry(pitch) Rx(roll), then the joint's local Z rotation.
        origin.rotation.set(...spec.rpy, 'ZYX');
        parent.add(origin);
        origin.add(turn);
        parent = turn;
        joints.push(turn);
      }
      const bytes = Uint8Array.from(atob(part.positions), c => c.charCodeAt(0));
      const geometry = new T.BufferGeometry();
      geometry.setAttribute('position', new T.BufferAttribute(new Float32Array(bytes.buffer), 3));
      geometry.computeVertexNormals();
      geometry.computeBoundingSphere();
      const mesh = new T.Mesh(geometry, index === 6 ? flange : silver);
      mesh.name = part.name;
      parent.add(mesh);
    });
    parent.add(new T.AxesHelper(0.075)); // Flange frame, not a calibrated gripper/TCP model.
    return { robot, joints, update(values) {
      if (!validJoints(values)) return false;
      if (previous && values.slice(0, 6).every((v, i) => v === previous[i])) return false;
      previous = values.slice(0, 6);
      joints.forEach((joint, i) => { joint.rotation.z = values[i] * Math.PI / 180; });
      robot.updateMatrixWorld(true);
      return true;
    } };
  }

  const jointStyles = Array.from({ length: 6 }, (_, i) => ({
    label: `J${i + 1} · ${[2, 3, 5].includes(i + 1) ? '弯曲' : '旋转'}`,
    color: [2, 3, 5].includes(i + 1) ? '#4ee2b8' : '#40a7ff',
  }));

  // Resolve labels in screen space so coincident joint origins (e.g. J1/J2) remain distinguishable.
  function layoutJointLabels(points, width, height) {
    const placed = [], labelW = Math.min(68, Math.max(30, width - 8)), labelH = 22;
    const top = Math.min(54, height * 0.18), bottom = Math.max(top, height - labelH - 26);
    return points.map((point, index) => {
      if (![point.x, point.y, point.z].every(Number.isFinite) || point.z < -1 || point.z > 1 ||
          point.x < 0 || point.x > width || point.y < 0 || point.y > height) return { visible: false };
      const sides = index % 2 ? [-labelW - 14, 14] : [14, -labelW - 14];
      let best = null, bestCost = Infinity;
      for (const offset of sides) for (let n = 0; n < 16; n++) {
        const shift = n === 0 ? 0 : Math.ceil(n / 2) * 26 * (n % 2 ? 1 : -1);
        const box = { x: clamp(point.x + offset, 4, Math.max(4, width - labelW - 4)),
          y: clamp(point.y - labelH / 2 + shift, top, bottom), w: labelW, h: labelH };
        const overlaps = placed.filter(p => box.x < p.x + p.w + 3 && box.x + box.w + 3 > p.x &&
          box.y < p.y + p.h + 3 && box.y + box.h + 3 > p.y).length;
        const cost = overlaps * 1e6 + Math.abs(box.y + labelH / 2 - point.y) + Math.abs(offset) * .01;
        if (cost < bestCost) { best = box; bestCost = cost; }
      }
      placed.push(best);
      return { ...best, visible: true, pointX: point.x, pointY: point.y };
    });
  }

  function makeJointLabels(stage) {
    const layer = document.createElement('div');
    layer.className = 'joint-label-layer';
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    layer.appendChild(svg);
    const items = jointStyles.map(style => {
      const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
      line.setAttribute('stroke', style.color); svg.appendChild(line);
      const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('fill', style.color); dot.setAttribute('r', '3'); svg.appendChild(dot);
      const text = document.createElement('span'); text.className = 'joint-model-label';
      text.textContent = style.label; text.style.color = style.color; layer.appendChild(text);
      return { line, dot, text };
    });
    stage.appendChild(layer);
    return { remove: () => layer.remove(), update(points, width, height) {
      svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
      layoutJointLabels(points, width, height).forEach((box, i) => {
        const { line, dot, text } = items[i];
        [line, dot, text].forEach(node => node.style.display = box.visible ? '' : 'none');
        if (!box.visible) return;
        text.style.transform = `translate(${box.x}px,${box.y}px)`;
        text.style.width = `${box.w}px`;
        line.setAttribute('x1', box.pointX); line.setAttribute('y1', box.pointY);
        line.setAttribute('x2', clamp(box.pointX, box.x, box.x + box.w));
        line.setAttribute('y2', box.y + box.h / 2);
        dot.setAttribute('cx', box.pointX); dot.setAttribute('cy', box.pointY);
      });
    } };
  }

  function create(options) {
    const { T, canvas, data, specs, toolbar, onFailure } = options;
    const renderer = new T.WebGLRenderer({ canvas, antialias: true, alpha: false, powerPreference: 'low-power' });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
    const scene = new T.Scene();
    scene.background = new T.Color(0x08131c);
    const camera = new T.PerspectiveCamera(40, 1, 0.01, 20);
    camera.up.set(0, 0, 1);
    const model = buildRobot(T, data, specs);
    scene.add(model.robot);
    const labels = makeJointLabels(canvas.parentElement);
    const ambient = new T.HemisphereLight(0xcceaff, 0x263c40, 2.1);
    ambient.up.set(0, 0, 1);
    ambient.position.set(0, 0, 2);
    scene.add(ambient);
    const key = new T.DirectionalLight(0xffffff, 2.5);
    key.position.set(1, -2, 3);
    scene.add(key);
    const fill = new T.DirectionalLight(0x70c1ff, 0.9);
    fill.position.set(-2, 1, 1);
    scene.add(fill);
    const grid = new T.GridHelper(2, 20, 0x36505c, 0x182d39);
    grid.rotation.x = Math.PI / 2;
    grid.position.z = -0.002;
    scene.add(grid, new T.AxesHelper(0.18));
    const view = { azimuth: -0.9, elevation: 0.3, distance: 1.8 };
    const target = new T.Vector3(0, 0, 0.38);
    let pending = 0, lost = false, lastFrame = -Infinity;
    function draw(now) {
      pending = 0;
      if (lost || document.hidden) return;
      // Coalesce telemetry and touch updates; render at most 30 fps without a permanent animation loop.
      if (now - lastFrame < 33) { pending = requestAnimationFrame(draw); return; }
      lastFrame = now;
      const w = Math.max(1, canvas.clientWidth), h = Math.max(1, canvas.clientHeight);
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      const horizontal = view.distance * Math.cos(view.elevation);
      camera.position.set(target.x + horizontal * Math.cos(view.azimuth),
        target.y + horizontal * Math.sin(view.azimuth), target.z + view.distance * Math.sin(view.elevation));
      camera.lookAt(target);
      try {
        renderer.render(scene, camera);
        labels.update(model.joints.map(joint => {
          const p = joint.getWorldPosition(new T.Vector3()).project(camera);
          return { x: (p.x + 1) * w / 2, y: (1 - p.y) * h / 2, z: p.z };
        }), w, h);
      }
      catch (error) { fail(error); }
    }
    function requestDraw() {
      if (!pending && !lost && !document.hidden) pending = requestAnimationFrame(draw);
    }
    const orbit = bindOrbit(canvas, view, requestDraw);
    function fit() {
      const sphere = new T.Box3().setFromObject(model.robot).getBoundingSphere(new T.Sphere());
      target.copy(sphere.center);
      const aspect = Math.max(0.1, canvas.clientWidth / Math.max(1, canvas.clientHeight));
      const angle = Math.atan(Math.tan(20 * Math.PI / 180) * Math.min(1, aspect));
      view.distance = clamp(sphere.radius / Math.sin(angle) * 1.14, 0.6, 6);
      requestDraw();
    }
    function preset(name) {
      orbit.clear();
      if (name === 'in' || name === 'out') { orbit.zoom(name === 'in' ? 0.8 : 1.25); return; }
      view.azimuth = name === 'front' ? -Math.PI / 2 : -0.9;
      view.elevation = name === 'top' ? Math.PI / 2 - 0.01 : name === 'front' ? 0 : 0.3;
      fit();
    }
    const buttons = [...toolbar.querySelectorAll('[data-view]')];
    const buttonHandlers = buttons.map(button => {
      const fn = () => preset(button.dataset.view);
      button.addEventListener('click', fn);
      return fn;
    });
    function fail(error) {
      if (lost) return;
      lost = true;
      orbit.dispose();
      buttons.forEach((button, i) => button.removeEventListener('click', buttonHandlers[i]));
      labels.remove();
      renderer.dispose();
      onFailure(error);
    }
    canvas.addEventListener('webglcontextlost', event => {
      event.preventDefault();
      fail(new Error('WebGL context lost'));
    });
    document.addEventListener('visibilitychange', () => { orbit.clear(); requestDraw(); });
    window.addEventListener('blur', orbit.clear);
    fit();
    return { update(joints) { if (model.update(joints)) requestDraw(); }, resize: requestDraw };
  }
  return { create, buildRobot, bindOrbit, validJoints, poseLabel, jointStyles, layoutJointLabels };
});
