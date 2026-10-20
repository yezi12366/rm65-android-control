"""RM65原始TCP JSON协议测试。默认只读；运动命令需要输入PLAY确认。"""

import argparse
import json
import socket
import sys
import time
import urllib.error
import urllib.request


MAX_SPEED = 30
MAX_DURATION = 1.0


def parse_args():
    parser = argparse.ArgumentParser(description="测试Android Demo使用的RM65直连JSON协议")
    parser.add_argument("--ip", default="192.168.1.18")
    parser.add_argument("--port", type=int, default=8080)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument(
        "--jog",
        choices=[
            *(f"J{i}{sign}" for i in range(1, 7) for sign in ("+", "-")),
            *(f"{axis}{sign}" for axis in ("X", "Y", "Z", "RX", "RY", "RZ") for sign in ("+", "-")),
        ],
        help="执行一次有限时长点动，例如 J1+、Z-、RX+",
    )
    actions.add_argument("--gripper", choices=("open", "close"))
    actions.add_argument("--gripper-position", type=int, metavar="1..1000")
    parser.add_argument("--speed", type=int, default=5, help="点动速度百分比，1~30")
    parser.add_argument("--duration", type=float, default=0.2, help="点动持续秒数，0.05~1.0")
    return parser.parse_args()


def errors(value):
    if isinstance(value, list):
        return [int(item) for item in value]
    return [int(value)]


def ensure_web_backend_disconnected():
    try:
        with urllib.request.urlopen("http://127.0.0.1:8000/api/status", timeout=1) as response:
            status = json.load(response)
    except (OSError, urllib.error.URLError, json.JSONDecodeError):
        return
    if status.get("connected"):
        raise RuntimeError("网页控制后端仍连接着机械臂；请先在网页点击‘停止并断开’，避免JSON客户端冲突")


class RobotConnection:
    def __init__(self, ip, port):
        self.sock = socket.create_connection((ip, port), timeout=3)
        self.sock.settimeout(3)
        self.reader = self.sock.makefile("r", encoding="utf-8", newline="\n")
        self.writer = self.sock.makefile("w", encoding="utf-8", newline="")

    def request(self, payload):
        self.send(payload)
        line = self.reader.readline()
        if not line:
            raise ConnectionError("控制器关闭了连接")
        return json.loads(line)

    def send(self, payload):
        self.writer.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\r\n")
        self.writer.flush()

    def safe_stop(self):
        for command in ("set_stop_teach", "set_arm_slow_stop"):
            try:
                self.send({"command": command})
            except OSError:
                pass

    def close(self):
        try:
            self.reader.close()
        finally:
            try:
                self.writer.close()
            finally:
                self.sock.close()


def read_and_print_state(robot):
    current = robot.request({"command": "get_current_arm_state"})["arm_state"]
    all_state = robot.request({"command": "get_arm_all_state"})["all_state"]

    joints = [value / 1000 for value in current["joint"][:6]]
    pose = [value / 1_000_000 for value in current["pose"][:3]]
    pose += [value / 1000 for value in current["pose"][3:6]]
    enabled = [int(value) for value in all_state["en_flag"][:6]]
    joint_errors = [int(value) for value in all_state["err_flag"][:6]]
    system_errors = errors(all_state["err"]) + errors(current["err"])

    print("连接和JSON解析成功")
    print("关节角(°)：", "  ".join(f"J{i + 1}={value:.3f}" for i, value in enumerate(joints)))
    print("末端位姿：", "  ".join(f"{name}={value:.6f}" for name, value in zip(("X", "Y", "Z", "RX", "RY", "RZ"), pose)))
    print("六轴使能：", enabled)
    print("关节错误：", joint_errors)
    print("系统错误：", system_errors)

    ready = (
        len(enabled) == 6
        and len(joint_errors) == 6
        and all(enabled)
        and not any(joint_errors)
        and not any(system_errors)
    )
    print("运动条件：", "允许低速测试" if ready else "不允许运动")
    return ready


def jog_payload(name, speed):
    positive = name.endswith("+")
    axis = name[:-1]
    direction = "pos" if positive else "neg"
    if axis.startswith("J"):
        return {
            "command": "set_joint_teach",
            "teach_joint": int(axis[1:]),
            "direction": direction,
            "v": speed,
        }
    axis = axis.lower()
    return {
        "command": "set_pos_teach" if axis in ("x", "y", "z") else "set_ort_teach",
        "teach_type": axis,
        "direction": direction,
        "v": speed,
    }


def require_play(description):
    print(f"即将执行：{description}")
    print("确认工作区无人、实体急停在手边。输入精确的 PLAY 才执行：")
    if input("> ").strip() != "PLAY":
        raise RuntimeError("用户取消，未发送动作命令")


def main():
    args = parse_args()
    if not 1 <= args.speed <= MAX_SPEED:
        raise ValueError(f"speed必须为1~{MAX_SPEED}")
    if not 0.05 <= args.duration <= MAX_DURATION:
        raise ValueError(f"duration必须为0.05~{MAX_DURATION}")
    if args.gripper_position is not None and not 1 <= args.gripper_position <= 1000:
        raise ValueError("gripper-position必须为1~1000")

    ensure_web_backend_disconnected()
    robot = RobotConnection(args.ip, args.port)
    try:
        ready = read_and_print_state(robot)
        if args.jog:
            if not ready:
                raise RuntimeError("机械臂状态不满足运动条件")
            require_play(f"{args.jog}，速度{args.speed}%，持续{args.duration:.2f}秒")
            reply = robot.request(jog_payload(args.jog, args.speed))
            print("启动回复：", reply)
            time.sleep(args.duration)
            robot.safe_stop()
            print("已发送示教停止和缓停")
        elif args.gripper:
            require_play("夹爪张开" if args.gripper == "open" else "夹爪力控夹取")
            payload = (
                {"command": "set_gripper_release", "speed": 200, "block": False}
                if args.gripper == "open"
                else {"command": "set_gripper_pick", "speed": 200, "force": 100, "block": False}
            )
            print("夹爪回复：", robot.request(payload))
        elif args.gripper_position is not None:
            require_play(f"夹爪移动到位置{args.gripper_position}")
            print(
                "夹爪回复：",
                robot.request(
                    {"command": "set_gripper_position", "position": args.gripper_position, "block": False}
                ),
            )
        else:
            print("只读测试完成，没有发送任何运动或夹爪命令")
    finally:
        if args.jog:
            robot.safe_stop()
        robot.close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已收到Ctrl+C，正在退出", file=sys.stderr)
        raise SystemExit(130)
    except Exception as error:
        print(f"错误：{error}", file=sys.stderr)
        raise SystemExit(1)
