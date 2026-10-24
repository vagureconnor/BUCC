# 共享 API 与契约

shared API 为客户端 UI 与各校内业务系统之间提供统一的 Kotlin 接口。UI 通过业务 API 和本地 backend 获取数据；请求由客户端直接发往校内上游，或经北航 WebVPN 访问。

## 设计规则

- DTO 放在 `shared/src/commonMain/kotlin/app/bucc/model`，使用 kotlinx.serialization。
- 每个业务模块在 `api/feature` 中定义 backend 接口。
- 直连和 WebVPN 实现在 `api/local` 中提供同一 backend 接口。
- 错误响应通过共享错误处理转换为用户可读文案。
- 新增业务能力时扩展 shared 接口和相应本地实现。

## API 分类

认证与用户、课程与学习、校园服务 API 均由 `shared/src/commonMain/kotlin/app/bucc/api` 下的接口和本地实现组成。跨平台设置由 `api/storage` 管理。

BUCC 当前不提供自建服务端 API、服务端公告推送或客户端版本检查服务。

## 来源文件

- `shared/src/commonMain/kotlin/app/bucc/api/core/ApiClient.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/core/ApiFactory.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/auth/NetworkUtils.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/GradeApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/JudgeApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/LibBookApi.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/ApiFactoryDispatchTest.kt`
