# 希冀作业

希冀模块聚合 judge.buaa.edu.cn 的课程作业、提交状态、分数和题目明细。列表默认面向未截止未提交作业；用户勾选显示已截止作业后，刷新会以包含历史/已截止作业的模式重新拉取。

## 用户能力

- 查看希冀作业列表、课程名、截止时间、提交状态和得分。
- 按筛选与排序查看未完成作业。
- 进入详情页查看题目、满分、个人得分和问题列表。
- 首页待办聚合未提交且未截止的希冀作业。

## 技术路径

- `JudgeViewModel` 负责作业列表、详情增量补全和批处理详情请求。
- `JudgeApiBackend` 支持作业列表、单个详情和批量详情。
- `LocalJudgeApiBackend` 处理直连/WebVPN 模式下的希冀登录、课程发现、作业解析、历史课程缓存和详情缓存。


## 维护重点

- 希冀页面 HTML 容易变化，修 parser 时优先补充 `JudgeSupportTest` 或本地 API backend 测试。
- 多行链接解析、历史课程包含、已截止作业开关和详情批次顺序都是高风险回归点。

## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/judge/JudgeAssignmentsScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/judge/JudgeAssignmentDetailScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/judge/JudgeViewModel.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/menu/HomeTodo.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/JudgeApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalJudgeApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/model/dto/Judge.kt`
- `composeApp/src/commonTest/kotlin/app/bucc/ui/JudgeViewModelTest.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/LocalJudgeApiBackendTest.kt`
