# 课程签到

课程签到模块查询当天可签到课程，并提交签到动作。它依赖上游课堂签到状态，属于写操作功能。

## 用户能力

- 查看当天签到课程和当前签到状态。
- 对可签到课程提交签到。
- 刷新当天签到状态。

## 技术路径

- `SigninViewModel` 管理当天签到状态和提交结果。
- `SigninApiBackend` 暴露 today 和 do 两个动作；当前原生客户端使用本地 backend。
- `LocalSigninApiBackend` 复用本地 SSO Cookie，访问 iClass MyCenter 跳转页提取 `loginName`，再访问上游签到接口；登录会话按连接模式与学号隔离，签到日期使用北京时间。
- 查询上游失败、响应结构无效和登录失败会保留为错误状态，不会伪装成“今日没有可签到课程”；失效会话仅在识别为会话过期时尝试刷新一次。
- 从课表或首页课程详情进入时，只把当天且与课程匹配的可签到班次作为快捷签到目标。其它日期的课程不绑定当天签到；查询失败时可重试，或打开今日课堂列表继续查询。
- 签到成功后刷新当天可签到课程；失败和异常结果应显示为操作结果，不应清空错误或显示成功。


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
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/schedule/CourseDetailScreen.kt`
- `composeApp/src/commonTest/kotlin/app/bucc/ui/SigninViewModelTest.kt`
