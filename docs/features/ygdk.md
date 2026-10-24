# 阳光打卡

阳光打卡模块读取体育活动概览和历史记录，并支持提交带活动类型、时间和图片的打卡记录。

## 用户能力

- 查看学期体育活动概览。
- 分页查看历史打卡记录。
- 选择活动项目、开始结束时间和图片后提交打卡。
- 表单校验会阻止缺少必要字段的提交。

## 技术路径

- `YgdkViewModel` 管理概览、记录分页、表单状态和提交状态。
- `YgdkApiBackend` 提供概览、记录和提交契约。
- `LocalYgdkApiBackend` 在本地连接模式下处理上游登录、分类、记录分页和上传字段转换。


## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/ygdk/YgdkHomeScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/ygdk/YgdkClockinFormScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/ygdk/YgdkViewModel.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/YgdkApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalYgdkApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/model/dto/Ygdk.kt`
- `composeApp/src/commonTest/kotlin/app/bucc/ui/YgdkViewModelTest.kt`
