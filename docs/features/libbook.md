# 图书馆座位

图书馆座位模块覆盖楼馆查询、分区查询、座位查询、座位预约、预约列表和取消预约。预约和取消预约属于写操作，审查或排障时不要用真实账号执行提交，除非用户明确授权。

## 用户能力

- 按日期查看可用楼馆和分区。
- 查看分区详情、座位列表和可预约时间段。
- 地图和列表模式均可选座；缺少地图坐标的座位会列在地图下方，坐标全部缺失时自动切换到列表模式。
- 选择座位、日期、开始时间和结束时间后提交预约。
- 查看个人预约记录。
- 取消符合上游门禁条件的座位预约；已结束/已取消预约无需取消，缺少入馆记录等资格不足时应显示上游原因。
- 图书馆相机扫码入口已移除；支付二维码识别不属于图书馆预约流程。

## 技术路径

- `RegularFeaturesScreen` 提供“图书馆座位”普通功能入口。
- `MainAppScreen` 注册 `LIBBOOK_HOME`、`LIBBOOK_RESERVE`、`LIBBOOK_BOOKINGS` 三个页面。
- `LibBookViewModel` 管理楼馆、分区、座位、预约和预约记录状态。
- `LibBookApiBackend` 是 shared 层契约；本地实现通过直连或 WebVPN 访问图书馆上游。


## 维护重点

- 本地实现需要保留 `libbook_auth_failed`、`libbook_not_found`、`libbook_seat_unavailable` 等稳定错误语义。
- 图书馆上游返回结构变化时，优先补充本地 backend 测试，再改 parser。
- 座位地图不能因为单个座位缺少地图区域数据就丢弃整张地图；无坐标座位应保留为可选列表项。参考 `LibBookSeatMap.kt` 和 `LibBookReserveScreen.kt`。
- 取消预约仅在上游明确返回成功时刷新预约列表；不能把“请求已发送”表述为“取消成功”。上游资格门禁和预约状态以原始响应为准。

## 只读现场证据（2026-10-26）

主 agent 对 30 个图书馆分区做了只读检查：`area_id=16`（一层东报刊阅览室）有 195 个座位，其中 194 个有有效地图坐标；其余 29 个分区的坐标完整。旧实现会因这一个座位缺少地图几何而让整张地图失效。10 笔预约均已结束（上游状态 4/8），没有 active 预约；当前账号没有有效入馆记录，因此实时取消受上游门禁限制。

该证据只覆盖只读查询，不证明真实预约或取消写入成功。文档更新没有执行预约或取消操作。

## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/libbook/LibBookHomeScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/libbook/LibBookReserveScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/libbook/LibBookBookingsScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/libbook/LibBookViewModel.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/menu/RegularFeaturesScreen.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/feature/LibBookApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalLibBookApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/model/dto/LibBook.kt`
