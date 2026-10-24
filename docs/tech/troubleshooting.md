# 排障指南

## 构建失败

- 确认本机 JDK、Gradle 与平台 SDK 满足工程配置。
- 先运行受影响模块的 Gradle 编译或测试任务，再根据首个编译错误定位问题。
- iOS 构建失败时，检查 Xcode 工程、签名配置与共享 framework 集成状态。

## 登录或连接模式异常

- 确认当前模式为直连或 WebVPN，并检查网络是否可访问相应学校系统。
- 切换模式后应清理旧 Token、Cookie 和本地上游客户端。
- 直连问题应在 Android、iOS 或 macOS 上复现，并分别记录上游状态码与解析错误；不得记录凭据或认证票据。

## 功能数据为空

- 检查 shared 本地 backend 返回和学校上游原始响应。
- 若上游 raw 数据存在但解析结果为空，优先检查 HTML/JSON parser。
- 对课程、成绩、图书馆及校园服务接口变更，补充对应 parser/backend 回归测试。

## 来源文件

- `shared/src/commonMain/kotlin/app/bucc/api/ConnectionRuntime.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/auth/NetworkUtils.kt`
