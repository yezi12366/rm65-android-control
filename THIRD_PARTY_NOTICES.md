# 第三方资源与许可证

## RealMan RM65-6F 模型

- 来源：<https://github.com/RealManRobot/rm_models>
- 固定提交：`9d37f52605aa00c7b0ed8143e5e46b8ffdb9317a`
- 上游路径：`RM65/urdf/RM65-6F`
- 本仓库位置：`docs/model-source/` 和生成文件 `app/src/main/assets/rm65-model.js`。
- 许可证：Apache-2.0。
- [来源及版权声明](app/src/main/assets/vendor/RM65-MODEL-LICENSE.txt)
- [Apache-2.0 全文](app/src/main/assets/vendor/Apache-2.0.txt)

模型转换由 `scripts/build-model.py` 完成，源文件 SHA-256 记录在生成资源中。

## Three.js

- 版本：0.186.1。
- 来源：npm 官方 `three` 包。
- 本仓库位置：`app/src/main/assets/vendor/three.min.js`。
- 许可证：MIT。
- [许可证全文](app/src/main/assets/vendor/THREE-LICENSE.txt)

打包方式及依赖校验记录见 [docs/RM65_MODEL_SOURCE.md](docs/RM65_MODEL_SOURCE.md)。

## Gradle Wrapper

`gradlew`、`gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar` 来自 Gradle，按 Apache-2.0 分发。脚本保留上游版权和许可证声明；[Apache-2.0 全文](app/src/main/assets/vendor/Apache-2.0.txt) 随仓库保留。

本文件说明第三方资源的授权，不为项目自身代码另行指定许可证。
