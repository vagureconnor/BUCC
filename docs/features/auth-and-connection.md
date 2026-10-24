# 登录与连接模式

登录模块负责处理用户凭据、验证码、Token 和连接模式。客户端启动时会恢复已保存的直连或 WebVPN 模式。

## 用户能力

- Android、iOS 和 macOS 支持直连模式和 WebVPN 模式。
- 支持验证码获取、预登录、登录、登录状态检查、Token 刷新和退出登录。
- 切换连接模式时会清理 Token、ClientId、本地会话、Cookie、本地上游客户端和学期缓存，避免跨模式复用旧状态。

## 技术路径

- `ConnectionRuntime` 决定可用模式、当前模式和切换时的会话清理。
- `DefaultApiFactory` 根据模式把 shared 的业务接口分发到本地实现。
- 本地直连/WebVPN 由 `LocalAuthServiceBackend` 登录统一认证，并把上游 Cookie 持久化到本地存储。

## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/auth/AuthViewModel.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/auth/LoginScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/auth/ConnectionModeSelectionScreen.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/ConnectionRuntime.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/core/ApiFactory.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/auth/AuthApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalConnectionAuth.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/ConnectionRuntimeTest.kt`
