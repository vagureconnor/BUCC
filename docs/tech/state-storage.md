# 状态与存储

BUCC 的业务状态由客户端本地管理。校内上游系统或 WebVPN 会话按其服务规则维护；项目不再包含服务端会话存储或业务缓存。

## 客户端本地状态

- 连接模式：`ConnectionModeStore`。
- Token 和 ClientId：按连接模式作用域存储。
- 认证会话与 Cookie：`LocalAuthSessionStore` 和 `LocalCookieStore`。
- 用户凭据与自动登录开关：`CredentialStore`。
- 功能偏好：由对应的本地存储类管理。

## 业务缓存

客户端本地实现可缓存解析后的数据或上游上下文，以减少重复请求。缓存具体范围由相关 feature 的实现定义。

## 来源文件

- `shared/src/commonMain/kotlin/app/bucc/api/storage/TokenStore.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/storage/CredentialStore.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/storage/AnnouncementReadStore.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalConnectionAuth.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalJudgeApi.kt`
