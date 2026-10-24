# 博雅课程

博雅课程模块覆盖课程发现、筛选、详情、已选课程、统计、选课、退课和签到签退。它是读写混合模块，所有写操作都需要明确的上游返回结果。

## 用户能力

- 查看个人博雅资料和修读统计。
- 分页浏览课程，支持状态、类别、校区等筛选。
- 查看课程详情和已选课程。
- 选择课程、退选课程。
- 对支持签到的博雅课程执行签到或签退。

## 技术路径

- UI 分为首页、课程列表、课程详情、已选课程和统计页。
- `BykcApiBackend` 定义业务动作，由客户端本地实现访问学校上游。
- `LocalBykcApiBackend` 实现直连/WebVPN 模式，包含上游登录、加密请求、分页、签到配置和动作解析。


## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/bykc/BykcHomeScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/bykc/BykcCoursesScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/bykc/BykcViewModel.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/BykcApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalBykcApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/model/dto/Bykc.kt`
- `composeApp/src/commonTest/kotlin/app/bucc/ui/screens/bykc/BykcCourseFiltersTest.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/LocalBykcApiBackendTest.kt`
