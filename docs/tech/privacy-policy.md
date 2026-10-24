# 隐私政策

BUCC 尊重用户隐私。本页基于当前客户端实现，说明学号、密码、登录会话和教务数据的处理方式；实现发生变化时，本页也应同步更新。

## 核心结论

- 在 `DIRECT`（直连）和 `WEBVPN` 模式下，客户端请求由用户设备直接发送至学校上游系统或北航 WebVPN；项目不提供自建服务器中转。
- BUCC 不会经自建 API 服务收集或中转用户学号、密码及教务数据。学校系统或 WebVPN 对请求的处理遵循其自身规则。

## 客户端本地处理

- 连接模式、Token、ClientId、认证会话与 Cookie 保存在用户设备本地。
- 如果用户启用记住密码，学号和密码会保存在设备本地的 `CredentialStore` 中，用于后续自动填充或自动登录。
- 功能偏好设置保存在本地。

## 会话管理

- 切换连接模式时，应用会清理当前模式作用域下的 Token、ClientId、本地会话、Cookie 和相关缓存。
- 用户退出登录后，当前会话对应的认证状态会被清理。

## 来源文件

- `shared/src/commonMain/kotlin/app/bucc/api/ConnectionRuntime.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/storage/CredentialStore.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalConnectionAuth.kt`
