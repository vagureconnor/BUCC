# 连接模式

BUCC 支持直连和 WebVPN 两种连接模式。模式选择影响上游 URL、Cookie 和本地会话管理。

## 模式

- `DIRECT`：客户端直接访问校内上游。
- `WEBVPN`：客户端通过北航 WebVPN 访问上游。

Android、iOS 和 Desktop 客户端均使用本地连接 backend；项目不提供服务器中转模式。

## 切换语义

切换模式会调用 `resetSession()`，清理模式作用域下的 Token、ClientId、本地认证会话、Cookie 和相关缓存。

## WebVPN URL

本地 WebVPN 访问通过 `LocalWebVpnSupport` 和上游 URL 包装完成。部分公开可访问的系统可能使用独立 URL 处理逻辑。

## 来源文件

- `shared/src/commonMain/kotlin/app/bucc/api/ConnectionRuntime.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/core/ApiFactory.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalConnectionAuth.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalWebVpnSupport.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/ConnectionRuntimeTest.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/LocalWebVpnSupportTest.kt`
