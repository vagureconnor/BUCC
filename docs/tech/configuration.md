# 配置说明

本项目包含 Gradle、原生平台工程与 GitHub Actions 构建配置。私密签名材料不得写入仓库。

## 本地配置

- `local.properties`：本机 Android SDK 等本地配置，不提交。
- `gradle.properties`：项目版本和 Gradle 参数。
- Apple 签名、App Store Connect 凭据等仅通过本地安全配置或 GitHub Actions Secrets 提供。

## 构建配置

- `project.version` 和 `project.version.code` 来自 `gradle.properties`。
- Gradle 子模块和 Xcode 工程定义 Android、macOS、iOS、Windows 与 Linux 原生构建任务。
- 当前客户端支持直连和 WebVPN，不配置项目自建 API 服务地址。
- 项目不包含文档网站生成器、网站部署流程或应用内更新地址配置；本目录中的 Markdown 是源码文档。

## GitHub Secrets

Release workflow 使用的 Secrets 依平台而定，iOS App Store Connect 发布所需的签名与上传凭据说明见[发布与部署](./release-deployment.md)。

## 来源文件

- `gradle.properties`
- `shared/build.gradle.kts`
- `composeApp/build.gradle.kts`
- `androidApp/build.gradle.kts`
- `iosApp/Configuration/Config.xcconfig`
- `.github/workflows/release.yml`
- `.github/workflows/test.yml`
