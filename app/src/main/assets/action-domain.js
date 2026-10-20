(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.RM65ActionDomain = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function normalizeActions(value) {
    if (!Array.isArray(value)) return [];
    return value.filter((item) => item && typeof item === "object" && typeof item.id === "string")
      .map((item) => ({
        ...item,
        display_name: String(item.display_name || `未命名动作 ${item.id}`),
        nodes: Array.isArray(item.nodes) ? item.nodes : [],
      }));
  }

  function nodeLabel(node) {
    if (!node || typeof node !== "object") return "未知节点";
    if (node.type === "waypoint") return "机械臂路点";
    if (node.type === "drag_trajectory") return `拖动轨迹 · ${Number(node.point_count) || 0}点`;
    if (node.type === "wait") return `等待 · ${(Math.max(0, Number(node.duration_ms) || 0) / 1000).toFixed(1)}秒`;
    if (node.type === "gripper") {
      const names = { open: "完全张开", close: "力控夹取", position: `位置${node.position ?? "—"}` };
      return `夹爪 · ${names[node.operation] || "操作"}`;
    }
    return "未知节点";
  }

  function executionPrompt(action) {
    const name = String(action?.display_name || "未命名动作");
    const count = Array.isArray(action?.nodes) ? action.nodes.length : 0;
    return `即将执行“${name}”（${count}个节点）。机械臂将先低速移动到动作起点，然后自动执行完整动作链。请确认工作区已清空且实体急停可用。`;
  }

  return { normalizeActions, nodeLabel, executionPrompt };
});
