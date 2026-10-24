# 当天待办、健康云刷新与支付接续修复（2026-10-22）

## 基线和范围

唯一源码 `/Users/connor/Desktop/BUCC/source`；分支 `codex/workbench-ios`；开始 HEAD `ddcfda560e08f23cee29c213d9054ae1d2302a2e`，工作树干净。本轮本地修改、验证、提交和 Mac 测试包；未推送、上传或部署，未覆盖历史包。实际金融服务仅 GET 读取；没有创建订单、充值、付款，也没有调用真实校园卡 openCardPay 初始化。

用户确认失败平台为 Mac；原网费失败页已经关闭，因此本轮不能读取那一次完整响应，不重新建单。网费修复的验收以脱敏 fixture、真实二维码图片及原生浏览器 mock 为依据，不能声称真实付款完成。

## 修改与根因

### 当天待办优先

旧的构建列表、工作台和全部待办分别只按 sortTime 排序；纸飞机没有 sortTime，落到尾部，TD 当天截止也可能在长期作业之后。三处统一使用 `homeTodoComparator`：纸飞机、TD、课程签到在前，组内仍按已有时间规则排列，长期作业保持原排序。阳光打卡是学期任务，不按当天重置任务处理。

文件：`composeApp/src/commonMain/kotlin/app/bucc/ui/screens/menu/HomeTodo.kt`、`HomeScreen.kt`；`HomeTodoTest.kt` 新增当天任务与长期作业混合场景，旧实现先失败。

### 健康云查询与刷新状态

共享客户端首次查询学期记录按天串行；改为最多 4 个并发只读请求，继续复用旧日缓存，今日/昨日重新读取。任何请求失败或取消都不提交不完整的新统计。Mac Python 后端原本已有限并发与缓存，本轮不重复改造；修复其可信登录成绩页已带学期却又 GET 一次相同页面的问题，每次登录内只消费一次学期信息，不跨刷新盲目复用学期。

新增 `refreshingCounts`，查询开始立即置位、成功/失败/取消均清除；次数卡片保留旧数据，在刷新按钮旁显示旋转进度和“正在刷新”，防止重复点击。VPN 查询权限和不允许打卡的规则保持。

文件：`shared/src/commonMain/kotlin/app/bucc/td/{TdHealthCloudClient,TdDirectService,TdModels}.kt`、`shared/src/jvmMain/kotlin/app/bucc/td/DesktopTdService.kt`、`composeApp/src/commonMain/kotlin/app/bucc/ui/screens/td/TdScreen.kt`、Mac backend `src/auto_td/healthcloud.py`。测试覆盖最大并发、缓存只补近期日期、刷新状态生命周期、动画出现/消失与旧次数不清空，以及登录页学期复用。

收益有可重复请求数/并发测试，不将它换算成未经实测的真实网络加速倍数。

### 校园卡路由

本轮读取的脱敏诊断显示：校园卡 CAS 302 接续成功，接着 `/card/home/openHomePageApp` 的独立 NO_PROXY 直连连接约 10 秒超时（ConnectTimeoutException / SocketTimeoutException），失败在菜单读取阶段，未建单。旧实现对所有模式强制使用该直连传输。

现在 WebVPN 在准备前接续官方网关，校园卡回调与加密业务请求走 `d.buaa.edu.cn`，使用与网关认证一致的客户端；直连模式继续使用独立 NO_PROXY 传输。路径在请求前选择，不对失败订单自动切换或重放；Hct 加密、业务字段和一次准备仅提交一次不变。

文件：`shared/src/commonMain/kotlin/app/bucc/api/finance/OfficialCampusCardRechargeBackend.kt`；`CampusCardRechargeBackendTest.kt` 覆盖三种模式的主机选择、客户端工厂和网关准备调用，以及原有提交约束。

**真实只读证据**：克隆应用会话在内存，0 次凭据 POST 恢复；官方网关下 ZFW 充值表单 GET HTTP 200，校园卡 SPA GET HTTP 200；再经 CAS 取本应用身份、按原加密协议 GET 校园卡菜单，HTTP 200、success=true、17 个菜单项。未输出身份、Cookie、金额、二维码内容或完整响应，未进入校园卡初始化/付款。

### 网费二维码接续

发现并用回归测试复现两处识别缺陷：支付目标经过官方网关包装时，原识别器不还原其中的官方支付地址（包括嵌套 `/qrcode?url=`）；页面前面导航元素较多时，首 64 个候选的截断会丢掉二维码链接。

现在先验证选中模式下官方且可往返校验的网关包装，再还原并沿用官方 HTTPS 支付目标白名单；像素二维码载荷也按同一规则处理，不放宽伪造域名/不受支持目标。DOM 优先取官方二维码载体，再补导航候选，总量仍有界。多码、过期和一次导航约束保持；在原内嵌窗口接续，二维码仍在加载层后解析，不调用摄像头。

文件：`shared/src/commonMain/kotlin/app/bucc/api/finance/OfficialCampusRechargeBackend.kt`、`composeApp/src/commonMain/kotlin/app/bucc/ui/screens/finance/{PaymentQrRecovery,PaymentNavigationRules}.kt`；对应共享层/Compose 测试及 `composeApp/src/commonTest/js/payment-qr-decode.test.cjs`。

这些是确定的代码缺陷和测试结果；由于原失败响应未保存，不能确认它们覆盖那一次网费失败的全部原因。仍需用户用本次包在下一次本人需要充值时复测真实支付接续，不能用 fixture 通过替代真实付款成功。

## 本轮验证

先失败再修复的证据：当天排序断言失败；共享健康云最大并发为 1 的断言失败；Mac Python 重复读取成绩页测试失败；校园卡三模式路由断言失败；网关包装二维码目标测试失败；二维码载体被 70 个装饰链接挤出扫描范围的 Node 测试失败。一次新增 UI 测试 import 不兼容导致的编译错误已修正，不当作产品缺陷复现。

指定 Java 21：`/Users/connor/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home`；Android SDK `/Users/connor/Library/Android/sdk`。

```sh
BUCC_WEBKIT_MOCK_UI=1 ./gradlew --no-configuration-cache :shared:jvmTest :composeApp:jvmTest :androidApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64 :composeApp:packageDmg --console=plain
node --test composeApp/src/commonTest/js/payment-qr-decode.test.cjs
# 在 composeApp/src/jvmMain/resources/td-backend 下
PYTHONPATH=src python3 -m unittest tests.test_healthcloud_vpn
```

结果：Gradle BUILD SUCCESSFUL（1m24s）；shared 522 项、13 跳过，Compose 389 项、2 跳过，共 896 通过、15 跳过、0 失败/错误。包括本轮刷新动画 UI 测试、状态生命周期测试和 6 项 Mac 原生支付 runtime mock。Android Debug APK、iOS Simulator Arm64 Kotlin 编译、Mac DMG 通过。Node 3/3、Python 39/39 通过。历史 Android NotificationAlarmScheduler 阻断本轮不存在。

完整 iOS 模拟器构建也通过（本轮运行，非沿用历史结果）：

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/bucc-ios-daily-payment-20261022 CODE_SIGNING_ALLOWED=NO build
```

结果 `** BUILD SUCCEEDED **`。

显式真实只读命令：

```sh
./gradlew --no-configuration-cache --init-script script/homework-read-probe.init.gradle :shared:homeworkReadProbe -PhomeworkProbeProductionTransport=true -PhomeworkProbeFinanceOnly=true --console=plain
```

该工具仅允许已限定的 GET 菜单/静态/认证接续路径；会话克隆到内存。BUILD SUCCESSFUL 不等于业务成功，业务看 FINANCE_CARD_MENU status=200 success=true 等输出。

## 实测边界和交付

本轮没有真实金融写操作；没有 iOS/Android 真机支付或真实健康云耗时对照，不保证所有支付站点后续跳转均已实测。Mac 原生支付测试使用本地模拟服务；健康云刷新动画在 Compose UI 自动化实际渲染验证。最终源码提交、干净工作树和桌面唯一测试包路径见交付消息，旧包保留。构建资源会重新打包 Python 后端，重新启动测试版后加载新资源。

子任务使用 worker 处理待办/健康云实现、luna-worker 追踪校园卡事实并补测试；中断后主 Agent 完成校园卡生产路由、集成、测试修正与最终验证。没有把未完成子任务当作交付证据。
