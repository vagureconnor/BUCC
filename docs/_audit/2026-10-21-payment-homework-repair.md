# 支付接续、作业检测与页面调整（2026-10-21）

## 范围与基线

权威源码 `/Users/connor/Desktop/BUCC/source`，分支 `codex/workbench-ios`，修改前 HEAD `d4aff1d2bb0bd71a8be3273ecbfeaf9b5a8d2e45`，修改前工作树干净。只做本地实现、测试及 Mac 测试包；无推送、部署、上传、真实充值订单、付款、作业上传或预约。独立的有界实现及接口资料工作使用 luna-worker，最终集成和判断由主 Agent 完成。

## 根因与修改

1. 网费支付：Mac 缺少页面链接恢复，延迟二维码和跨域像素读取会中断接续；部分平台没有原生遮罩/截图路径，严格入口规则还会误拦官方支付后续菜单。共享层优先解析官方 `/qrcode?url=`，再解析页面图像；Mac/iOS/Android/JVM 增加原生遮罩和页面截图解码，验证来源与目标后在原内嵌区域导航。初始支付目标校验与已验证支付站点的后续浏览规则分开；返回不重进二维码/建单历史。识别重试只读取原页面，不重发充值请求。保留有效期、未知目标拒绝、多码歧义及单次提交限制。
   - `shared/src/commonMain/kotlin/app/bucc/api/finance/OfficialPaymentResponseRecovery.kt`
   - `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/finance/PaymentQrRecovery.kt`、`PaymentRequestGate.kt`、`PaymentWebNavigationPolicy.kt`
   - `composeApp/src/webViewMain/kotlin/app/bucc/ui/screens/finance/OfficialPaymentBrowser.kt`
   - `composeApp/src/jvmMain/kotlin/app/bucc/ui/screens/finance/MacPaymentBrowser.kt`、`PaymentPopup.jvm.kt`
   - `composeApp/src/jvmMain/resources/native/PaymentBrowserBridge.swift`
   - 对应 Android/iOS PaymentPopup 和 Harmony PaymentWeb/PaymentHost。
2. 图书馆文字：入口、页标题、确认和说明统一为“研讨间预约”；CGYY 研讨室命名不变。文件位于 `ui/screens/libbook`、`MainAppScreen.kt`、`WorkbenchFeatures.kt`。
3. 签到：课程筛选原先在日期模式也生效。`AttendanceScreen.kt` 仅在按课程模式显示并应用课程筛选；日期模式显示全部课程的日/周记录，切回课程模式保留选择。
4. Judge：日期解析仅支持“至”，网页使用横线时日期丢失，被学期过滤排除。`LocalJudgeApi.kt` 增加常见横线和空白格式、卡片标题回退；`CampusHomeworkAdapters.kt` 对缺失日期明确报告，不静默当作无作业。稳定 ID 去重保留。
5. 实验报告：旧判断要求所有补交机会都上传，导致首次成功后仍是部分提交。`ByxtHomeworkAdapter.kt`、`Homework.kt`、`HomeworkRepository.kt` 统一任一次成功上传即完成，并规范化缓存。`HomeworkScreen.kt` 只在实验报告来源按标题数字逐段自然升序；混合来源仍按截止时间。
6. Accoding：只取进行中条目、分页与题目级成绩没有完整应用。`SeparateHomeworkAdapters.kt` 遍历分页，稳定 ID 去重，用教学日历保留当前学期（含已结束）比赛/作业，排除教程；读取官方题目及个人提交。非空题目全部存在 AC 且归一化满分 1 才完成，后续失败提交不覆盖已成功提交。成绩失败保留条目与“状态未确认”提示，撤销本地手动完成覆盖。官方前端接口为 GET `/api/contests/{id}` 和 `/api/contests/{id}/submissions`；本轮先前只读样本确认 AC 满分值为 1，未声称已核对所有比赛。

## 本轮验证

Java 21：`/Users/connor/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home`。

- 原生桥重编译：`./gradlew --no-configuration-cache :composeApp:compileBuccNative --console=plain`，通过。
- 最终回归及构建：设置 `BUCC_WEBKIT_MOCK_UI=1`、`BUCC_TEST_EVIDENCE_DIR`、`ANDROID_HOME` 和上述 `JAVA_HOME` 后，运行 `./gradlew --no-configuration-cache :shared:jvmTest :composeApp:jvmTest :androidApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64 :composeApp:packageDmg --console=plain`。
- XML 结果：shared 490 项，0 失败/错误，13 跳过；Compose 383 项，0 失败/错误，2 跳过。跳过项不算通过。MacWebKitPaymentRuntimeTest 3 项实际运行、0 跳过，覆盖原窗口嵌套导航、会话、单次 POST、遮罩下跨域页面截图二维码识别、错误来源拒绝。
- `node --test composeApp/src/commonTest/js/payment-qr-decode.test.cjs`：2 项通过，0 跳过。
- `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/bucc-ios-recharge-homework-20261021 CODE_SIGNING_ALLOWED=NO build`：BUILD SUCCEEDED。
- 针对性回归：官方二维码链接优先级/过期/伪造目标/多码/重试与导航；Judge 多日期结构与日期缺失；实验报告首次上传、缓存、多个数字排序；Accoding 分页、已结束、教程排除、学期、满分/部分/未知/失败保留；签到隐藏筛选不影响日期模式。
- 一次中间回归发现新增截图测试在弹窗打开后匹配两个根节点，已调整截图时机后完整重跑通过；不是业务功能回归。构建使用关闭配置缓存参数，避免项目已有原生编译任务配置缓存序列化问题。

## 界面证据与限制

`evidence/2026-10-21-payment-homework/` 为本轮合成 fixture 渲染截图，包含签到日/周/课程、实验数字排序、Accoding 未知状态提示，不含真实个人数据。已查看日期/课程及排序截图。Mac 支付使用本轮本机 mock 和真实 WKWebView，不创建校园订单。

用户明确告知网络暂时不可用并要求先完成其他工作，因此 Judge 实际课程与客户端逐项比较、Accoding 当前学期全部条目及成绩对照尚未完成；不能据 fixture 通过宣称真实账号漏项已经全部核清。待网络恢复再只读复验。不会要求用户重新发送密码。

Android 为 Debug APK 构建验证，iOS 为模拟器构建，未进行本轮 Android/iPhone 实机支付操作。Windows/Linux 仅 JVM 编译和截图解码 helper 测试，未在这些系统运行 JCEF。Harmony 已接入链接恢复、遮罩和 provider 直达，但本机无 Harmony 构建工具链，未编译/实机验证；跨域像素失败时还没有原生截图解码后备，会显示明确失败与返回重试，不能宣称全平台能力等同。

最终提交号、Mac 包路径和 SHA-256 见同次本地交付说明；不公开发布。
