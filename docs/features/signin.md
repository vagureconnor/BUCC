# 课程签到

课程签到模块查询当天可签到课程，并提交签到动作。它依赖上游课堂签到状态，属于写操作功能。

## 用户能力

- 查看当天签到课程和当前签到状态。
- 对可签到课程提交签到。
- 刷新当天签到状态。

## 技术路径

- `SigninViewModel` 管理当天签到状态和提交结果。
- `SigninApiBackend` 暴露 today 和 do 两个动作；当前原生客户端使用本地 backend。
- `LocalSigninApiBackend` 在本地连接模式下复用本地 SSO Cookie，先访问 iclass MyCenter 跳转页提取 `loginName`，再访问上游签到接口。


## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/signin/SigninScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/signin/SigninViewModel.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/SigninApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/SigninLoginNameSupport.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalSigninApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/model/dto/Signin.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/SigninLoginNameSupportTest.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/LocalSigninApiBackendTest.kt`
- `shared/src/jvmTest/kotlin/app/bucc/api/LocalSigninRealIntegrationTest.kt`
