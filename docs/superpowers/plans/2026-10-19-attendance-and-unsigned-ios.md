# Attendance and unsigned iOS implementation plan

**Goal:** 学期出勤查询、两种视图、完整 Judge 作业与官网未签名 IPA。
**Architecture:** 共享只读 API 与分组模型；Compose 查询状态/学期课程筛选；平台无新增权限。现有签到写接口保持隔离。
**Spec:** docs/superpowers/specs/2026-10-19-attendance-and-unsigned-ios.md

- [x] 只读核实 iClass 官方学期/课程/历史 API，不编造接口；认证信息只在安全存储/内存使用。
- [x] 共享课程/出勤模型、稳定 ID 与日期/课程两种分组；对错误、未知状态、完整课程数组和旧请求覆盖编写回归。
- [x] 新增独立查询入口与原生筛选、视图、错误/官方入口/重试；UI fixture 验证。
- [x] 修正 Judge 实际缺失路径与回归；保持学期策略和账号隔离。
- [x] Java 21 JVM 全量回归、Android 构建、物理 iOS unsigned 构建与归档检查。
- [x] 官网更新；部署静态页和经过检查的 unsigned IPA，明确不能直接安装，验证线上文件/摘要。
- [x] 本地提交、报告证据及仍无法验收部分；不对校园系统做写业务测试。
