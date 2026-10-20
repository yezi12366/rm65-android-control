# RM65-6F 三维模型与离线打包

1.15.0 使用官方七个 STL 实体网格和六轴 URDF 关节关系。来源为睿尔曼官方 [rm_models](https://github.com/RealManRobot/rm_models/tree/9d37f52605aa00c7b0ed8143e5e46b8ffdb9317a/RM65/urdf/RM65-6F)，固定提交 `9d37f52605aa00c7b0ed8143e5e46b8ffdb9317a`，目录 `RM65/urdf/RM65-6F`。该仓库的 [LICENSE](https://github.com/RealManRobot/rm_models/blob/9d37f52605aa00c7b0ed8143e5e46b8ffdb9317a/LICENSE) 声明 Apache-2.0，Copyright 2024 realman-robotics；声明和许可证全文均位于 `app/src/main/assets/vendor/`。

原始 URDF 与 STL 保存在 `docs/model-source/`。`python3 scripts/build-model.py` 将二进制 STL 的顶点转换为 `rm65-model.js` 中的 Base64 Float32 数据，共 36,112 个三角形，保留米制坐标；各源文件 SHA-256 写在生成资产中。显示时重新计算面法线，使用银色材质与绿色末端法兰，未更改实体几何形状。所有 visual origin 均为零。关节 origin 与原版本一致，URDF 中四舍五入的 1.5708 在代码中使用精确 π/2。

渲染引擎为 [Three.js 0.186.1](https://www.npmjs.com/package/three/v/0.186.1)，MIT，随附 `vendor/THREE-LICENSE.txt`。从 npm 官方包 `three-0.186.1.tgz` 的 `build/three.module.js` 使用 esbuild 0.28.2 打包为 classic IIFE，输出全局 `THREE`，适配 Android 的本地 asset 页面，无需开放跨文件请求或联网加载。

```sh
esbuild three/package/build/three.module.js --bundle --format=iife --global-name=THREE --minify --target=chrome90 --outfile=app/src/main/assets/vendor/three.min.js
```

npm 包已按注册表的 SHA-512 integrity 校验：

```text
three@0.186.1: sha512-blFeqb49wRCSGUGj7gtpfnSGHy2lwDk94RhUmS1c/hTby70kvChbWpkJ4Pm1390LqzzvTmzgXKHPEafJwCb8jA==
@esbuild/darwin-arm64@0.28.2: sha512-n4KqkOQrraxHJcgjM1RvwbigfQKIKJVpM7xp+KsxiyUSrRdIXnt73VhrPAx0fV44hgfmIVKjxMN9J1t5jySVkw==
```

只在关节角、视角或画布大小变化时按需渲染，最多 30 fps，像素比上限 1.5。单指旋转、双指缩放、鼠标滚轮以及视角按钮只操作观察相机。渲染模块没有 `RobotBridge` 调用。非法角度保留最后有效姿态；未连接、未收到关节数据和数据过期均有独立提示。WebGL2 不可用或上下文丢失时显示明确标识的兼容骨架视图。

模型表示 RM65-6F 本体和法兰坐标轴，不包含实际安装的夹爪、TCP 标定、工作坐标变换或现场障碍物。它用于观察关节姿态，不执行碰撞判断。
