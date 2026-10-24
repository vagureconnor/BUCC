# 作业漏项、Mac 支付与登录接续修复（2026-10-22）

## 范围与基线

唯一源码 `/Users/connor/Desktop/BUCC/source`，分支 `codex/workbench-ios`，开始 HEAD `a07b3f9d44da8c9c455f3ad2f7018ebcaefdc70d`，初始工作树干净。实施期间保留全部已有文件与历史包。只做本地修复、验证和打包，不推送、上传、部署。实际业务网站只读取，没有建单、支付、上传作业或预约。

## 根因与修改

### Accoding

官网已结束赛事的标题是普通文本，旧解析只取带 `/contest-ng/index.html#/ID` 链接的行，导致仅显示仍带链接的一项。现在优先保留官网稳定赛事 ID，兼容普通标题及 `/contest/view?id=`；解码分页参数中的 `&amp;` 并遍历全部分页。本学期口径仍来自教学日历，包含已结束赛事、排除教程，不按相似标题合并。

单项 401/403 会重新检查独立账号身份：账号仍有效则保留该赛事并标为状态未知，账号确实失效才要求登录。非教程数据行无法识别时明确失败，避免部分列表覆盖缓存。全部题目确认满分才完成的规则保持不变。

文件：`shared/src/commonMain/kotlin/app/bucc/homework/SeparateHomeworkAdapters.kt`；新增 `AccodingDiscoveryRegressionTest.kt`。

### Judge

官网真实 SSO 链接的 service 是 `https://judge.buaa.edu.cn/`，旧常量使用 HTTP。实测 HTTP 回调进入公共首页，后续课程入口为空或报错；改为官网 HTTPS 回调后能读取课程与作业。课程菜单兼容链接与下拉 onclick；作业按课程串行完成切换、分页和详情，使用课程 ID＋作业 ID。网关可能共享服务端课程选择状态，单进程内全局串行事务避免交错。

未知课程页/作业页不再当作正常空列表。个别课程失败保留该课程旧缓存，其他课程仍更新。没有把已有历史截止逻辑当成本次原因；作业中心原本就使用 includeExpired=true。

文件：`LocalJudgeApi.kt`、`Judge.kt`、`CampusHomeworkAdapters.kt`、`Homework.kt`、`HomeworkRepository.kt`，以及对应 Judge/学期缓存测试。

### Mac 支付

在模拟支付回调中复现了 AppKit 主线程与 Swing 事件线程的同步等待：Swing 调用 native close 等待主线程，而主线程尚在原生回调，导致应用不响应。旧代码下测试超时，线程栈包含 JNA Native.invokeVoid / bucc_payment_close。

返回、显示、关闭改为原生异步调度；关闭可重复调用，清理计时器与待决脚本，隔离关闭后的回调。JNA 回调对象保留到原生 released 确认；JavaScript alert/confirm/prompt 在离开时恰好完成一次。只保留一套原生工具栏，按钮与单行截断状态文字分两行，预留 64 像素；移除宿主残留文字层。

文件：`MacPaymentBrowser.kt`、`MacPaymentHost.kt`、`PaymentBrowserBridge.swift`、`MacWebKitPaymentRuntimeTest.kt`；Gradle 测试显式依赖本轮原生库编译，避免误测旧 dylib。原窗口接续、后台二维码识别、Cookie 和一次准备仅提交一次规则保持。

## 本轮证据

- Accoding 实际只读：遍历官网 10 页，教学日历 2026-09-07 至 2027-01-17；本学期非教程赛事 3 项，适配器 3 项，稳定 ID 集合一致；三项详情/成绩读取 HTTP 200。报告不记录成绩或标题。
- 登录实际验证：生产默认引擎下，激活直连失败后网关恢复，一次凭据提交；后续 GET-only 会话恢复为有效身份、凭据提交为 0。新 Mac 应用实际自动进入工作台。未保存账号/课程截图。
- Judge 实际只读：应用停止同步、独立探针串行执行时读取 3 门课程、31 条作业，失败课程 0；31 项均有起止时间，本学期 4 项，与 Safari 当前学期页面一致。应用与独立探针同时同步曾出现课程选择交错，因此本轮真实验收采用单进程串行查询；单进程全局互斥与 mock 测试防止应用内部并发串课，但不能锁住另一个进程或浏览器。最终按官网作业 ID 独立计数：三门分别 14、13、4 项，共 31；适配器也是 31，课程 ID＋作业 ID 集合完全相同，failedCourses=0，学期筛选后 4 项。一轮后续复查出现官网课程切换/详情请求超时，成功课程正常返回、失败课程通过 failedCourseIds 显式报告；没有将该次部分结果当作全量成功。探针时限已与生产请求的 30 秒保持一致。
- Mac 支付实际运行原生 WKWebView mock：6 项通过，包括锁等待复现、三种 JavaScript 弹窗关闭、关闭后新页面可用、原窗口嵌入、POST/弹窗/Cookie/嵌套返回且不重放。320 与 540 像素宽度的工具栏几何断言通过（按钮/状态均在界内、互不重叠、正文位于工具栏下）。未把不可靠桌面截图作为证据。

## 自动化与构建

指定 Java 21：`/Users/connor/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home`；Android SDK `/Users/connor/Library/Android/sdk`。

```sh
BUCC_WEBKIT_MOCK_UI=1 ./gradlew --no-configuration-cache :shared:jvmTest :composeApp:jvmTest :androidApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64 :composeApp:createDistributable --console=plain
```

本轮命令 BUILD SUCCESSFUL（51 秒）。shared 518 项，13 跳过；Compose 386 项，2 跳过；合计 889 通过、15 跳过、0 失败/错误。6 项 Mac 原生 runtime 测试均实际运行。Android assembleDebug、iOS Simulator Arm64 Kotlin 编译和 Mac distributable 通过。完整 Xcode 模拟器构建也已通过：

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/bucc-ios-homework-payment-20261022 CODE_SIGNING_ALLOWED=NO build
```

结果 `** BUILD SUCCEEDED **`。随后执行 `BUCC_WEBKIT_MOCK_UI=1 ./gradlew --no-configuration-cache :shared:jvmTest :composeApp:jvmTest :composeApp:packageDmg --console=plain`，BUILD SUCCESSFUL（33 秒）；生成 `composeApp/build/compose/binaries/main/dmg/BUCC-1.9.0.dmg`，另存桌面唯一带提交号文件。针对性测试覆盖 Accoding 普通标题/多页/教程/单项权限失败/失效账号/部分坏行，Judge 多课程同 ID/分页/课程状态交错/未知页面/部分失败缓存恢复，UC challenge/验证码/0 或 1 次凭据提交。独立复核未发现新的重要问题。

真实读取工具 `HomeworkSavedSessionProbe.kt` 与 init script 显式运行；默认 GET-only，会话克隆到内存，不记录 Cookie、密码、完整响应或成绩。可选恢复参数仅允许指定官方认证入口各一次 POST，业务写请求始终阻止。

本轮 Mac 界面额外核对：最终应用自动恢复到工作台，未显示自动登录失败；工作台能显示希冀待办。作业全量集合由相同生产适配器的只读探针核对，未宣称逐个课程详情页面均已人工点击。界面工具的滚动曾返回 noWindowsAvailable，因此没有补作业中心逐屏截图，也没有将该工具错误当作应用崩溃。

可重复真实只读核对（先停止 BUCC 后台同步，避免第二个进程切换共享网关课程）：

```sh
./gradlew --no-configuration-cache --init-script script/homework-read-probe.init.gradle :shared:homeworkReadProbe -PhomeworkProbeProductionTransport=true -PhomeworkProbeJudgeOnly=true --console=plain
```

探针 JavaExec 的 BUILD SUCCESSFUL 只表示进程退出，业务是否成功必须检查 `AUTH`、`JUDGE_RESULT`、`JUDGE_COMPARE` 或 `PROBE_FAILURE`，不能仅凭构建状态宣称真实查询通过。脱敏证据汇总在 `docs/_audit/evidence/2026-10-22-homework-payment/verification.json`。

## 限制与发布前复验

本轮没有真实付款，因此确认的是已复现锁等待缺陷的修复及模拟支付生命周期，不能保证所有第三方支付完成路径均已实测。iOS/Android 真机支付和相机等设备行为未在本轮实测；跨端构建通过不等同于设备运行通过。Windows/鸿蒙不属于本轮修改与构建承诺。官方服务网络状态与会话可能变化。

本轮使用 luna-worker 完成有界 Accoding 解析/测试与官方入口追踪；支付、认证由专门 worker 实现，主 Agent 负责集成、真实只读验证与最终判断，另有独立只读复核。最终本地提交及 Mac 包路径见交付消息；历史包不覆盖。
