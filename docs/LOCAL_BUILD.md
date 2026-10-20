# Android 本地构建说明

## 环境要求

- Android Studio（建议使用其自带 JBR 17 或更新版本）
- Android SDK 35
- 工程编译目标 Java 17
- Android Gradle Plugin 8.7.3、Gradle 8.9、Kotlin 2.0.21
- 最低 Android 8.0（API 26）

## Android Studio

1. 打开工程根目录。
2. 在 Gradle 设置中选择 Android Studio 自带的 JBR。
3. 等待 Gradle 同步；首次同步需要下载构建依赖。
4. 执行 `Build > Make Project`，再选择安卓设备运行。

`local.properties` 不随源码包分发。Android Studio 会按当前电脑的 Android SDK 路径生成该文件。

## 命令行

Windows：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS / Linux：

```sh
./gradlew testDebugUnitTest assembleDebug
```

APK 输出目录为 `app/build/outputs/apk/debug/`。

若提示 `JAVA_HOME is not set`，请安装 JDK 17，或让 `JAVA_HOME` 指向 Android Studio 的 `jbr` 目录。不要把个人电脑上的绝对 JDK/SDK 路径写入 `gradle.properties` 后再交付给其他人。

macOS 也可以双击根目录的 `build-macos.command`；它优先使用当前 `JAVA_HOME`，未设置时尝试 Android Studio 的标准 JBR 路径。

编译成功只证明 Android 代码可以构建，不代表机械臂运动功能已经通过实机验收。首次实机测试请使用 1% 速度、清空工作区并保持实体急停可用。
