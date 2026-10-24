# 测试与质量

BUCC 的自动化测试覆盖 shared 与 composeApp。项目当前不包含 server 模块或 VitePress 网站构建流程。Markdown 文档保存在 `docs/`，客户端代码变更按影响范围运行 Gradle 检查。

## 常用命令

```bash
./gradlew :shared:jvmTest
./gradlew :composeApp:jvmTest
./gradlew spotlessCheck
```

跨平台共享逻辑变更可运行：

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileAndroidMain
```

## 测试分层

- shared tests：API 分发、本地直连/WebVPN、DTO 和存储。
- composeApp tests：ViewModel、首页待办、筛选排序与显示逻辑。

课表真实账号测试默认跳过。显式设置 `BUCC_REAL_SCHEDULE_TEST=true` 后，可运行 `:shared:jvmTest --tests app.bucc.api.LocalScheduleRealIntegrationTest --no-configuration-cache`；测试从本机 `local.properties` 读取账号，仅查询学校数据。不要把账号、密码或个人数据写入测试 fixture 或日志。
