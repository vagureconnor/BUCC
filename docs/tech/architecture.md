# 架构总览

BUCC 是 Kotlin Multiplatform 原生客户端项目。核心模块为 `shared`、`composeApp`、`androidApp` 与 `iosApp`；应用直接访问学校上游系统，或通过北航 WebVPN 访问，不依赖项目自建 API 中转服务。

## 分层

- `composeApp`：Compose Multiplatform UI 与 ViewModel，覆盖 macOS、Android 和 iOS 的主要界面。
- `shared`：DTO、API 契约、连接模式、本地直连/WebVPN 实现与本地存储。
- `androidApp`：Android 原生入口、打包配置和签名配置。
- `iosApp`：SwiftUI 入口和共享 framework 集成。
- `buildSrc`：自定义 Gradle 构建任务。

## 请求流

1. UI 调用 ViewModel。
2. ViewModel 调用 shared 暴露的业务 API。
3. `DefaultApiFactory` 根据当前连接模式选择本地 backend。
4. 本地 backend 直接访问校内上游，或使用北航 WebVPN 包装后的上游地址。
5. DTO 从 shared 返回 UI，错误由 shared 的 API 错误处理转换为可展示信息。

## 来源文件

- `settings.gradle.kts`
- `composeApp/build.gradle.kts`
- `shared/build.gradle.kts`
- `shared/src/commonMain/kotlin/app/bucc/api/core/ApiFactory.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/ConnectionRuntime.kt`
