# 模块职责

## shared

`shared` 负责跨平台契约和可复用业务逻辑。`model/dto` 定义网络与 UI 共用数据结构；`api/feature` 定义业务 backend 接口；`api/local` 定义直连/WebVPN 实现；`api/storage` 管理跨平台本地设置。当前启用 Android、iOS Arm64/iOS Simulator Arm64 和 JVM source set。

## composeApp

`composeApp` 负责跨平台 UI 和 ViewModel。`commonMain` 下保存大部分界面，平台 source set 提供少量平台差异能力。

## 平台壳工程

`androidApp` 和 `iosApp` 负责原生平台入口、图标、签名/配置和平台项目结构。业务逻辑主要位于共享模块。

## buildSrc

`buildSrc` 保存仓库自定义 Gradle 任务。根构建脚本中注册的任务以当前构建脚本为准。

## 来源文件

- `settings.gradle.kts`
- `build.gradle.kts`
- `shared/src/commonMain/kotlin/app/bucc/api/core/ApiFactory.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/App.kt`
- `androidApp/src/main/java/app/bucc/MainActivity.kt`
- `iosApp/iosApp/iOSApp.swift`
