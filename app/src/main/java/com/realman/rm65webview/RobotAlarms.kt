package com.realman.rm65webview

import org.json.JSONObject

/** Gen3 JSON error appendix. Exact matches only: unknown codes retain their raw value. */
object RobotAlarms {
    private val systemNames = mapOf(
        0x1001 to "关节通信异常", 0x1002 to "目标角度超过限位", 0x1003 to "不可达／奇异点",
        0x1004 to "实时内核通信错误", 0x1005 to "关节通信总线错误", 0x1006 to "规划层内核错误",
        0x1007 to "关节超速", 0x1008 to "末端接口板无法连接", 0x1009 to "超速度限制",
        0x100A to "超加速度限制", 0x100B to "关节抱闸未打开", 0x100C to "拖动示教超速",
        0x100D to "机械臂发生碰撞", 0x100E to "工作坐标系不存在", 0x100F to "工具坐标系不存在",
        0x1010 to "关节掉使能", 0x1011 to "圆弧规划错误", 0x1012 to "自碰撞",
        0x1013 to "碰撞到电子围栏", 0x1014 to "超关节软限位", 0x1016 to "限位附近拖动使能失败",
        0x1017 to "外受力数据校验失败", 0x1101 to "碰撞到虚拟墙", 0x2001 to "夹爪异常",
        0x2002 to "灵巧手异常", 0x2003 to "六维力模块异常", 0x2004 to "一维力模块异常",
        0x2005 to "输出电流异常", 0x5003 to "控制器过温", 0x5005 to "控制器过流",
        0x5006 to "控制器欠流", 0x5007 to "控制器过压", 0x5008 to "控制器欠压", 0x5009 to "实时层无法通信"
    )
    private val jointNames = mapOf(
        0x0001 to "FOC 错误", 0x0002 to "过压", 0x0004 to "欠压", 0x0008 to "过温",
        0x0010 to "启动失败", 0x0020 to "编码器错误", 0x0040 to "过流", 0x0080 to "软件错误",
        0x0100 to "温度传感器错误", 0x0200 to "位置超限", 0x0400 to "关节 ID 非法",
        0x0800 to "位置跟踪错误", 0x1000 to "电流检测错误", 0x2000 to "抱闸打开失败",
        0x4000 to "位置指令阶跃警告", 0x8000 to "多圈关节丢圈数", 0xF000 to "通信丢帧"
    )
    private val seriousSystem = setOf(0x1001, 0x1004, 0x1005, 0x1006, 0x1008, 0x2003, 0x2004,
        0x2005, 0x5003, 0x5005, 0x5006, 0x5007, 0x5008, 0x5009)
    private val seriousJoint = setOf(1, 0x80, 0x100, 0x1000, 0x8000)

    fun item(code: Int, joint: Int = 0): JSONObject {
        val name = (if (joint == 0) systemNames else jointNames)[code]
        val serious = if (joint == 0) code in seriousSystem else code in seriousJoint
        val hint = when {
            name == null -> "未收录的错误码，请保留原始代码并对照对应固件手册。"
            serious -> "官方列为严重错误，请停止使用并排查硬件或联系技术支持；清除不能修复故障。"
            joint == 0 && code == 0x1003 -> "当前姿态或路径涉及奇异区域；清除后仍需调整姿态或路径。"
            joint == 0 && code in setOf(0x100D, 0x1012) -> "先检查碰撞、负载和周围空间，排除阻挡后再清除。"
            joint == 0 && code == 0x1010 -> "清除不会给关节上使能；请另行检查掉使能原因。"
            joint == 0 && code in setOf(0x1002, 0x1014, 0x1016) -> "检查关节位置和运动目标，保持原有限位保护。"
            joint > 0 && code == 0x2000 -> "抱闸打开失败可能涉及供电、卡滞或内部故障；请先排查，无法消除时联系技术支持。"
            joint > 0 && code in setOf(2, 4, 0x10, 0x40) -> "检查供电、负载和抱闸状态；故障未排除时可能无法清除或再次报警。"
            joint > 0 && code == 8 -> "等待关节降温并检查负载，勿反复清除后继续运行。"
            else -> "先排除报警原因，再清除并重新检查状态。"
        }
        return JSONObject().put("code", code).put("hex", "0x" + code.toUInt().toString(16).uppercase().padStart(4, '0'))
            .put("target", joint).put("source", if (joint == 0) "系统" else "J$joint 关节")
            .put("label", name ?: "未知错误").put("severity", when {
                name == null -> "未知"
                joint > 0 && code == 0x2000 -> "依故障原因判断"
                serious -> "严重"
                else -> "一般"
            })
            .put("hint", hint)
    }
}
