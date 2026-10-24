# BUCC (智慧北航 Remake)

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-blue.svg?style=flat&logo=kotlin)
![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-1.10.3-blueviolet.svg?style=flat&logo=jetpack-compose)
![Ktor](https://img.shields.io/badge/Ktor-3.4.1-orange.svg?style=flat&logo=ktor)
![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS%20%7C%20macOS-lightgrey.svg?style=flat)

**BUCC** 是一款面向北京航空航天大学学生的跨平台客户端，基于 **Kotlin Multiplatform**、**Compose Multiplatform** 和 **Ktor** 构建，覆盖 Android、iOS 和 macOS。

客户端通过直连或北航 WebVPN 访问校内接口，不依赖项目自建的 API 中转服务。

### Arch Linux (AUR)

```bash
yay -S bucc
```

## 核心能力

- 多端统一：Android、iOS 和 macOS 共用核心能力与大部分业务代码。
- 校园服务聚合：统一认证、课表、考试、成绩、空闲教室、博雅、SPOC、希冀、图书馆座位、签到、研讨室、阳光打卡、评教等能力集中接入。
- 现代体验：基于 Material Design 3，支持系统主题适配。

详细说明保留在仓库 `docs/`，可通过 VitePress 本地预览或构建：`npm install` 后运行 `npm run docs:dev` 或 `npm run docs:build`。

## 仓库概览

```text
BUCC/
├── composeApp/   # 跨平台客户端 UI
├── shared/       # 跨平台 API 与通用逻辑
├── androidApp/   # Android 壳工程
├── iosApp/       # iOS 壳工程
├── buildSrc/     # 自定义 Gradle 任务
├── docs/         # Markdown 项目说明
└── .github/      # 原生客户端 CI 与发布 workflow
```
