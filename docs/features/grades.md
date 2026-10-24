# 成绩查询

成绩模块读取北航成绩页面并转换为统一成绩 DTO。客户端支持选定学期查询，也支持全部学期加载；全部加载路径会并发发起多个学期请求，并优先保留用户当前选择学期的展示语义。

## 用户能力

- 查看课程名称、成绩、学分、绩点等信息。
- 按学期切换成绩列表。
- 一键加载全部学期成绩。
- 出错时通过 shared 的用户友好错误映射展示提示。

## 技术路径

- `GradeViewModel` 管理学期选择、全部成绩加载和 stale response 防护。
- `GradeApiBackend` 定义成绩查询契约；客户端本地实现负责请求和解析成绩数据。
- `LocalGradeApiBackend` 在直连/WebVPN 模式下直接访问成绩上游页面。

## 注意事项

- 全部成绩加载属于性能敏感路径，修改时需要覆盖并发和当前学期优先展示。
- 上游成绩页面结构可能变化，调整解析逻辑时应保持 DTO 和用户可读错误语义稳定。

## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/grade/GradeScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/grade/GradeViewModel.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/GradeApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalGradeApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/model/dto/Grade.kt`
- `composeApp/src/commonTest/kotlin/app/bucc/ui/GradeViewModelTest.kt`
