# 空教室查询

空教室模块按校区、日期和节次查询可用教室，结果由客户端转换为统一的 `ClassroomQueryResponse`。

## 用户能力

- 选择校区、日期和节次组合。
- 查看按楼宇或教室分组的空闲结果。
- 查询失败时显示可读错误信息。

## 技术路径

- `ClassroomQueryScreen` 和 `ClassroomViewModel` 管理查询表单与结果状态。
- `ClassroomApiBackend` 定义查询契约。
- `LocalClassroomApiBackend` 在本地连接模式下访问上游空教室接口。

## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/classroom/ClassroomQueryScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/classroom/ClassroomViewModel.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/ClassroomApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalClassroomApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/model/dto/Classroom.kt`
- `composeApp/src/commonTest/kotlin/app/bucc/ui/ClassroomViewModelTest.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/LocalClassroomApiBackendTest.kt`
