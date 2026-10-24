# 图书馆座位

图书馆座位模块覆盖楼馆查询、分区查询、座位查询、座位预约、预约列表和取消预约。预约和取消预约属于写操作，审查或排障时不要用真实账号执行提交，除非用户明确授权。

## 用户能力

- 按日期查看可用楼馆和分区。
- 查看分区详情、座位列表和可预约时间段。
- 选择座位、日期、开始时间和结束时间后提交预约。
- 查看个人预约记录。
- 取消已有座位预约。

## 技术路径

- `RegularFeaturesScreen` 提供“图书馆座位”普通功能入口。
- `MainAppScreen` 注册 `LIBBOOK_HOME`、`LIBBOOK_RESERVE`、`LIBBOOK_BOOKINGS` 三个页面。
- `LibBookViewModel` 管理楼馆、分区、座位、预约和预约记录状态。
- `LibBookApiBackend` 是 shared 层契约；本地实现通过直连或 WebVPN 访问图书馆上游。


## 维护重点

- 本地实现需要保留 `libbook_auth_failed`、`libbook_not_found`、`libbook_seat_unavailable` 等稳定错误语义。
- 图书馆上游返回结构变化时，优先补充本地 backend 测试，再改 parser。

## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/libbook/LibBookHomeScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/libbook/LibBookReserveScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/libbook/LibBookBookingsScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/libbook/LibBookViewModel.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/menu/RegularFeaturesScreen.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/LibBookApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalLibBookApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/model/dto/LibBook.kt`
