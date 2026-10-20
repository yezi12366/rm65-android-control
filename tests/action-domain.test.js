const test = require("node:test");
const assert = require("node:assert/strict");
const domain = require("../app/src/main/assets/action-domain.js");

test("normalizes unsafe library payloads", () => {
  assert.deepEqual(domain.normalizeActions(null), []);
  assert.deepEqual(domain.normalizeActions({}), []);
  assert.equal(domain.normalizeActions([{ id: "a", display_name: "抓取", nodes: [] }])[0].display_name, "抓取");
});

test("formats all supported node labels", () => {
  assert.equal(domain.nodeLabel({ type: "waypoint" }), "机械臂路点");
  assert.equal(domain.nodeLabel({ type: "drag_trajectory", point_count: 20 }), "拖动轨迹 · 20点");
  assert.equal(domain.nodeLabel({ type: "gripper", operation: "close" }), "夹爪 · 力控夹取");
  assert.equal(domain.nodeLabel({ type: "wait", duration_ms: 1500 }), "等待 · 1.5秒");
});

test("execution prompt says alignment happens first", () => {
  const prompt = domain.executionPrompt({ display_name: "放置样品", nodes: [{ type: "waypoint" }] });
  assert.match(prompt, /低速移动到动作起点/);
  assert.match(prompt, /放置样品/);
});
