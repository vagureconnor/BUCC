# 五平台功能契约与验收清单

检查日期：2026-10-26。目标平台为 iOS、Android、HarmonyOS、macOS 和 Windows。本文列出工作台当前的 15 项业务功能、共享层入口、五端共同验收点和已知平台差异。代码路径用于定位实现；它们本身不代表该平台已编译、运行或完成端到端验收。

## 工作台 15 项业务功能

工作台的功能 ID、标题和分组来自 `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/menu/WorkbenchFeatures.kt`。表格按当前功能定义顺序排列。

| # | 工作台功能 | 关键子流程 | 共享层入口 |
| --- | --- | --- | --- |
| 1 | 课表查询 | 选择学期/周次、查看周课表和课程详情、刷新当前课程；可导入系统日历（仅 iOS/macOS）。 | `ScheduleApi` / `ScheduleApiBackend`，`shared/.../api/feature/ScheduleApi.kt` |
| 2 | 考试查询 | 加载学期、切换学期、查看已安排和未安排的考试。 | 同课表的 `ScheduleApi.getExamArrangement` |
| 3 | 成绩 | 查看成绩、按学期切换、检查成绩更新提示；GPA 工具根据界面输入计算。 | `GradeApi`，`shared/.../api/feature/GradeApi.kt` |
| 4 | 博雅课程 | 浏览筛选课程、课程详情、已选课程和统计；选课/退课、课程签到签退。 | `BykcApi`，`shared/.../api/feature/BykcApi.kt` |
| 5 | 签到查询 | 按学期、日期或课程筛选出勤记录并刷新。 | `AttendanceApi` / `LocalAttendanceApi`，`shared/.../attendance/LocalAttendanceApi.kt` |
| 6 | 作业中心 | 聚合 SPOC、希冀、Accoding、PatPat、BYXT 五个来源；查看详情/报告/截止时间，单独登录来源账号，标记完成。 | `HomeworkRepository` 与 `HomeworkAdapter`；SPOC/Judge 另用 `SpocApi`、`JudgeApi`。适配器位于 `shared/.../homework/` |
| 7 | 自动评教 | 加载待评课程、调整选项并提交评教结果。 | `EvaluationService`，`shared/.../api/feature/EvaluationService.kt` |
| 8 | 空教室查询 | 选择校区、日期和节次，按楼栋/楼层/空闲时长筛选与排序。 | `ClassroomApi`，`shared/.../api/feature/ClassroomApi.kt` |
| 9 | 图书馆空间预约 | 查楼馆/分区/日期/时段和座位；地图与列表选择；确认提交、查看记录、取消预约。没有地图坐标的座位需能从列表选择。 | `LibBookApi`、`LibBookSeminarApi`，`shared/.../api/feature/` |
| 10 | TD 线 | 管理图片和健康云凭据、查询次数、在前台启动或取消操作；后台/离开前台时停止执行。 | `TdService` / `createTdService()`，`shared/.../td/TdService.kt` |
| 11 | 研讨室预约 | 查询空间和可预约时段，完成人机校验并提交，查看订单与门锁码。 | `CgyyApi`，`shared/.../api/feature/CgyyApi.kt` |
| 12 | 阳光打卡 | 查看记录和目标、填写地点/时间并添加照片、提交打卡。 | `YgdkApi`，`shared/.../api/feature/YgdkApi.kt` |
| 13 | iHome | 查看诉求、公告及处理进度，跟进诉求。 | `IhomeApi` 与上游协议/客户端，`shared/.../api/feature/IhomeApi.kt` |
| 14 | 课堂纸飞机 | 查看任务、积分和互动列表，投掷并与同学互动。 | `PaperPlaneApi` / `PaperPlaneBackend`，`shared/.../api/plane/PaperPlaneApi.kt` |
| 15 | 校园财务 | 查询校园卡/网费余额和流量，查看官方充值流程并识别支付二维码入口。 | `CampusFinanceApi`、官方充值 backend，`shared/.../api/finance/` |

上述入口指当前 Kotlin 共享契约或业务服务。考勤和 TD 是独立服务抽象；纸飞机和财务采用各自的 API/backend 结构，并非都实现 `api/feature` 下的同一种接口。

## 五端共同验收点

在五个平台分别按相同测试数据和账号状态检查以下契约。不能把一次 JVM 测试或共同代码编译当作五端验收。

- 登录与连接：账号切换、直连/WebVPN 适用范围、会话过期、重新登录和网络错误有可见反馈；请求失败不能伪装成空列表或成功。
- 首页与导航：15 项功能入口和子流程可达；平台不支持的系统能力应隐藏或明确说明，不能显示无效操作。首页待办按排序最多显示前三项，待办区域不单独滚动，完整列表仍可查看。
- 只读流程：加载、刷新、筛选、空态、错误态和返回路径在五端表现一致；日期和时区以业务契约为准。
- 写入流程：预约、签到、选课、评教、打卡、互动和充值等操作只使用隔离测试数据或经明确授权的数据；验证成功/失败反馈、重复点击保护及状态刷新，不进行真实提交作为文档验收。
- 系统集成：通知权限、后台调度、相册/相机、日历、系统浏览器/支付页面等按平台能力显示；权限拒绝和系统限额需可恢复且不宣称未发生的结果。
- 视觉与输入：窄屏、宽屏、窗口缩放、键盘输入、滚动、返回/取消和安全区域分别检查。手机平台宽屏仍按手机首页待办规则呈现。
- 支付二维码识别继续服务于财务支付流程；图书馆相机扫码入口和实现已经移除。

## 平台实现与当前证据

以下是源码证据和已知限制。证据日期为 2026-10-26；本次文档整理没有运行构建、系统权限写操作或真实业务提交。

| 平台 | 代码证据与已知平台差异 | 本次运行证据 |
| --- | --- | --- |
| iOS | Compose 共享界面通过 iOS 实际实现接入系统能力。日历导入由原生 EventKit bridge 提供。新作业检查通过 `BGAppRefreshTask`，任务最早时间设为当前时间后 1 小时；实际执行时间由 iOS 决定，不能承诺每小时触发。提醒处理器由 Compose 作业 ViewModel 安装，系统调度和通知权限仍可能使提醒延迟或不显示。 | 仅检查源码、`Info.plist` 和现有测试文件；本次未在设备运行或触发 BGTask。 |
| Android | Compose 共用业务界面，系统提醒由 Android 实现。作业提醒轮询间隔为 0，`installHomeworkBackgroundTaskHandler` 是空实现；ViewModel 现在提供 `onAppForegrounded()`，由应用前台恢复时检查新作业，通知仍受 Android 通知权限控制。没有 Android 后台作业轮询保证。课程/每日待办 AlarmManager 提醒另受精确闹钟权限和系统调度限制。 | 本次没有 Android SDK，Android 编译和设备运行未执行；这不能作为运行通过。 |
| HarmonyOS | Compose 业务界面通过 `HarmonyHost` 接入 ArkTS Host。作业轮询间隔为 0；EntryAbility 在应用回到前台且控制器就绪后调用 `refreshHomeworkOnForeground`，没有后台轮询保证。新作业通知通过系统通知服务请求，权限/系统策略可能拒绝；异常在通知调用处被吞掉。日历导入当前声明不可用。 | 仅检查 Kotlin/ArkTS 源码；本次未运行设备、通知权限或前台恢复流程。 |
| macOS | JVM 桌面端共享页面；通知轮询间隔为 30 分钟，仅在客户端进程和对应 ViewModel 存活时检查。通知依赖系统托盘可用；无托盘或 headless 环境会跳过通知。系统日历导入由 macOS 原生 bridge 提供。 | 仅检查源码；未启动 macOS GUI、托盘、日历权限或原生 bridge。 |
| Windows | JVM 桌面端共享页面；作业轮询间隔为 30 分钟，仅在客户端进程和对应 ViewModel 存活时检查。系统通知仍受 Windows 通知设置影响；离开应用后没有作业后台轮询。日历导入不可用。 | 仅检查源码；未启动 Windows GUI 或验证通知设置/运行时桥接。 |

### 作业提醒行为核对

设置页当前文案为“新作业提醒 / 发现新作业时通知”，见 `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/menu/SettingsScreen.kt`。这句话没有说明提醒检查时机和权限条件，容易被理解成各平台都会持续监控。

- macOS/Windows：`HomeworkReminderPlatform.jvm.kt` 对两种桌面系统返回 30 分钟间隔；仅应用进程存活时循环检查。重启前新增作业不会在客户端关闭期间被轮询发现。
- iOS：使用系统 `BGAppRefreshTask`；最早开始时间是 1 小时后，不等于一小时周期。iOS 可延迟或不安排后台刷新；处理器不在内存中时也无法完成应用层刷新。
- HarmonyOS：仅在 `EntryAbility` 前台回调中执行检查；没有后台周期调度保证，通知权限或系统策略可阻止展示。
- Android：代码将作业轮询间隔设为 0，Android 背景任务处理器为空；应用前台恢复时由 `onAppForegrounded()` 执行一次检查，但应用后台期间没有检查保证。新作业通知仍受系统通知权限影响。
- 因此，“发现新作业时通知”不能作为五端持续或及时提醒的承诺；Android 的前台恢复检查不提供后台轮询。

### 日历、扫码与财务二维码

- 课表系统日历导入当前仅支持 iOS 和 macOS；Android、HarmonyOS、Windows 应显示不可用或隐藏该操作。
- 图书馆相机扫码入口和平台 scanner 已删除，不列为五端验收能力。
- 校园财务的支付二维码恢复/识别仍保留，不能与已删除的图书馆座位二维码扫码混为一谈。

## 当前可追溯的检查记录

- 工作台功能清单：`composeApp/src/commonMain/kotlin/app/bucc/ui/screens/menu/WorkbenchFeatures.kt`。
- 五端平台源码：`composeApp/src/{iosMain,androidMain,ohosMain,jvmMain}`、`iosApp/`、`harmonyApp/`。
- JVM UI 测试和共享层 backend 测试可从 `composeApp/src/jvmTest/`、`composeApp/src/commonTest/`、`shared/src/commonTest/` 定位；它们只对实际运行过的目标和环境构成证据。
- 图书馆只读数据检查（主 agent 提供，2026-10-26）：检查了 30 个分区。分区 `area_id=16`（一层东报刊阅览室）有 195 个座位，其中 194 个带有效地图坐标；其余 29 个分区坐标完整。旧逻辑会因这 1 个缺少几何的座位而让整图不可用。预约记录 10 笔均为已结束状态（上游状态 4/8），没有 active 预约；账号没有有效入馆记录，因此实时取消受上游门禁限制。没有尝试预约或取消，这些只读检查不证明写接口成功。
- 当前文档任务没有执行 Gradle、iOS/Android/Harmony/macOS/Windows 安装运行、系统通知/日历写入或真实业务提交流程。编译成功（若后续由主 agent 执行）也只证明构建目标，不等于上述平台运行验收通过。

## 本机验证与发布 gate

GitHub `Source contract` workflow 只运行版本检查和 Python unittest，不安装原生 SDK/JDK，也不构建平台包。五个平台均使用开发者现有本机工具链构建；不额外下载 Harmony 工具、不设置 self-hosted runner。六个签名/完整性检查通过的资产以及 shared、Compose、Python 测试证据必须绑定同一个完整 commit SHA。只有这些检查都成功，本机 `script/local_platform_gate.py --report-status` 才会写入 GitHub 状态 `Local five platform gate`。测试 JSON 格式和运行命令见 `docs/tech/release-deployment.md`。

手动运行 `.github/workflows/release.yml` 仅核对触发 SHA 是否已有成功的本机 gate 状态；该 workflow 不构建、签名、上传或公开 release。此构建 gate 不代替真实验收。正式公开发布仍要求 `.github/scripts/release_gate.py` 接收到该 SHA 对应的真实验收证明；没有实际验收证据时不得发布。
