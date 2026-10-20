# RM65 Android Control

用于睿尔曼 RM65 机械臂的 Android 遥控程序。Kotlin 原生层通过 TCP JSON 协议直连控制器，WebView 提供操作界面和离线三维姿态显示，无需 Python 后端或官方 SDK。

当前版本：**1.15.4**。

## 功能

- 六轴关节点动、末端位置与姿态调整，以及 1 / 5 / 10mm 位置步进。
- J2 / J3 / J5 等角度联动、保存六轴姿态和按住复位。
- 夹爪开关、状态检测和动作完成确认。
- 遥控路点录制、控制器拖动轨迹、夹爪及等待节点的顺序回放。
- 系统与关节报警展示、指定报警清除及结果读回。
- 基于官方 RM65-6F 模型的离线三维姿态视图，支持观察视角旋转和缩放。

1.15.4 对全部手动运动统一执行停止回执与静止确认。停止确认失败时保持连接、锁住新运动并重试停止；按住心跳期限为 500ms，单次按住上限为 5 秒。具体时序见[开发者技术文档](docs/DEVELOPER_1_15_4.md)。

## 架构

```text
Android WebView（HTML / CSS / JavaScript）
    ↕ RobotBridge / onNativeEvent
Kotlin（状态机、录制回放、本地存储）
    ↕ TCP · UTF-8 JSON · CRLF
RM65 控制器
```

三维模块只显示关节姿态，拖动模型改变观察相机，不发送机械臂运动指令。当前代码没有接入自动奇异点规避。

## 构建

环境：JDK 17、Android SDK 35。工程使用 Gradle 8.9、Android Gradle Plugin 8.7.3、Kotlin 2.0.21。可直接用 Android Studio 打开仓库目录，或使用 Gradle Wrapper：

```sh
git clone https://github.com/yezi12366/rm65-android-control.git
cd rm65-android-control
./gradlew -PsideBySide=true :app:assembleDebug :app:testDebugUnitTest
```

Windows 使用 `gradlew.bat`。命令行构建通过 `ANDROID_HOME` 或本机 `local.properties` 指定 Android SDK。首次构建下载 Gradle 和 Maven 依赖。

APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。

| 构建方式 | applicationId | 应用名称 |
| --- | --- | --- |
| `-PsideBySide=true` | `com.realman.rm65webview.trial1154` | RM65 1.15.4 试用版 |
| 不传 `sideBySide` | `com.realman.rm65webview` | RM65 网页直连遥控台 |

试用包与标准包使用独立的数据目录，可以同时安装。源码不包含本机签名密钥；未配置本机密钥时使用 Android 默认 debug 签名。

## 安装与连接

```sh
adb -s DEVICE_SERIAL install app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL shell am start -W -n com.realman.rm65webview.trial1154/com.realman.rm65webview.MainActivity
```

上述启动命令对应试用包。更新同一包名且签名一致的安装使用 `adb install -r`。

应用不会自动连接机械臂。设备与控制器网络可达后，在“连接设置”中填写地址；当前默认值是 `192.168.33.80:8080`。连接初始化会发送停止命令，并读取关节状态、限位、参考系与固件信息。

## 测试

```sh
./gradlew -PsideBySide=true :app:testDebugUnitTest
node --test tests/*.test.js
```

Kotlin 测试包含本机模拟 TCP 控制器；Node 测试覆盖界面 Bridge 调用、动作状态、连接表单、报警和三维模型。1.15.4 的发布验证记录为 134 项 Kotlin 测试与 60 项 Node 测试，不包含实机运动测试结果。

GitHub Actions 在推送和 Pull Request 时执行这两组测试、构建试用版，并保留 APK 与测试报告为工作流产物。

## 目录

```text
app/src/main/java/com/realman/rm65webview/   TCP、协议、状态机与 Android Bridge
app/src/main/assets/                       操作界面、离线模型与 Three.js
app/src/test/                              Kotlin / JUnit 测试
scripts/build-model.py                     将源 STL 转换为离线模型资源
tests/                                     Node 界面与模型测试
docs/                                      开发文档、模型来源与版本记录
.github/workflows/                         自动测试与构建
```

## 文档

- [文档索引](docs/README.md)
- [1.15.4 开发者技术文档](docs/DEVELOPER_1_15_4.md)：架构、40 个 Bridge 接口、协议单位、状态机和存储格式。
- [停止确认与报警](docs/STOP_AND_ALARMS.md)：当前停止与清错流程。
- [构建说明](docs/LOCAL_BUILD.md)
- [模型和渲染资源来源](docs/RM65_MODEL_SOURCE.md)
- [版本记录](CHANGELOG.md)

## 第三方资源

官方机械臂模型按 Apache-2.0 保留来源与声明，Three.js 按 MIT 保留许可证，Gradle Wrapper 保留其 Apache-2.0 声明。完整索引见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
