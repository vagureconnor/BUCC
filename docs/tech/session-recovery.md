# 会话恢复与自动重登边界

BUCC 的登录恢复以当前主认证会话为准。业务模块不得把旧的业务 Token 或角色信息当作仍然有效的用户会话，也不得在认证失败后重放已经发出的业务写请求。

## 自动恢复条件

应用执行 `preloadLoginState` 时，优先尝试使用当前连接模式下已有的主认证会话恢复登录状态。若会话过期，只有同时满足以下条件，客户端才可尝试后台自动登录：

- 用户此前开启了记住账号，并且账号仍是当前账号。
- 安全凭据存储中仍有该账号对应的非空凭据。
- `preloadLoginState` 没有返回验证码要求或其他需用户完成的挑战。

每次过期恢复流程最多自动登录一次。若上游要求验证码或多因素认证，界面应将挑战交给用户完成。用户主动退出、未启用记住账号，或恢复流程超时时，不自动提交保存的密码；超时应保留当前界面状态并等待网络或用户操作。

## 主会话检查与业务重试

`LocalSessionExpiryEvents` 仅触发当前主认证会话校验。会话仍有效时保留登录状态；认证明确失效时清理会话并进入 `preloadLoginState` 恢复流程；上游超时不视为确认失效。

SPOC 本地客户端以连接模式、账号和 `authenticatedAt` 作为认证缓存 scope。scope 改变时，在登录互斥区内清除旧 Token、RoleCode 和纸飞机学生身份映射，再为当前 scope 登录。登录期间若 scope 再次变化，会丢弃旧 scope 的登录结果并重新获取当前 scope 的认证。

SPOC 读取操作收到认证失败时最多重新登录一次并重试该读取。业务写入不会在认证失败后自动重放，避免上游已经受理写入但响应丢失时产生重复操作。

## 验证范围

`LocalSpocApiBackendTest` 的 22 项 JVM 测试通过，覆盖模式切换、同模式新认证会话、旧纸飞机身份映射清除，以及并发读取期间的认证刷新。该结果验证本地行为，不等于所有 WebVPN 业务都已实网验收。iOS 重新进入 VPN 纸飞机后已读取当天 counts；JVM 使用同一私有 VPN Cookie 的只读任务和累计积分请求也成功。仍需按各自业务边界完成后续实网验收。

## 来源

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/auth/AuthViewModel.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/App.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/auth/AuthApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalSpocApi.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/LocalSpocApiBackendTest.kt`
