# SPOC 作业

SPOC 模块读取当前学期作业、提交状态、开始和截止时间，并支持进入详情页查看作业内容。首页待办会过滤未开始和已截止的作业，只展示真正需要处理的条目。

## 用户能力

- 查看当前学期 SPOC 作业列表。
- 根据提交状态、时间和筛选条件查看未完成作业。
- 进入作业详情页查看说明和提交信息。
- 首页待办聚合未提交且已开始、未截止的 SPOC 作业。

## 技术路径

- `SpocViewModel` 管理作业列表、详情缓存和筛选。
- `SpocApiBackend` 提供作业列表和详情契约。
- `LocalSpocApiBackend` 复用 `LocalSpocSupport` 完成 CAS 登录、加密参数、分页作业和详情解析。


## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/spoc/SpocAssignmentsScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/spoc/SpocAssignmentDetailScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/spoc/SpocViewModel.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/menu/HomeTodo.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/SpocApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalSpocApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalSpocSupport.kt`
- `shared/src/commonMain/kotlin/app/bucc/model/dto/Spoc.kt`
- `composeApp/src/commonTest/kotlin/app/bucc/ui/SpocViewModelTest.kt`
- `composeApp/src/commonTest/kotlin/app/bucc/ui/HomeTodoTest.kt`
