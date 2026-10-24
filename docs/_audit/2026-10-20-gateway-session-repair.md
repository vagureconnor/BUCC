# 非直连校园服务与凭据接续修复（2026-10-20～21）

源码：`/Users/connor/Desktop/BUCC/source`；分支：`codex/workbench-ios`；起始 HEAD：`a4dd4159cefac1aa20a48128cf720a2a2efd95c8`。修改前工作树干净。仅本地源码、验证及测试包；不发布、上传、推送或覆盖历史安装包。

## 已确认根因

- CGYY：官方 WebVPN 将上游 Cookie 保存在服务器端，CAS 回调的常规响应没有向原生 Cookie jar 交付 `sso_buaa_zhjs_token`。官方页面脚本通过 `/wengine-vpn/cookie?method=get&host=cgyy.buaa.edu.cn&scheme=https&path=/venue-zhjs/` 读取纯文本 `document.cookie`。客户端现在只对受限的 CGYY HTTPS 来源使用这一桥接机制，然后完成原有 token 登录。
- 健康云：已有网关登录态时，`index.php` 跳到不带登录表单的 `main.php` 导航首页；原实现把它判作页面格式错误。现验证同源 `main.php` 及成绩页的有效学期标记，接续已有会话，避免再次发送密码；未知来源和异常页面仍拒绝。
- 健康云首次 CAS 接续：网关返回带 `/http/<网关主机编码>/...` 前缀的根相对回调，原生代码误拼到健康云源站下，造成 404。现先还原官方完整网关目标再校验并路由；已补冷会话的两种模式 fixture，避免已有会话掩盖问题。
- Mac 健康云：启动探测读取 `/api/state`，会在连接模式初始化前访问独立密码库，阻塞启动。现使用无凭据的 `/api/health`，随后设置模式；两种非直连模式不读取独立密码库、不重复持久化主账号密码。取消的读取不再变成界面服务失败。
- Accoding：真实网关请求已能到达；未认证的 `/api/users/me` 实际返回 HTTP 200、`application/json` 类型的空体，原解析误判为协议错误，自动认证恢复未触发。现把该精确场景与 JSON null / 明确未认证状态一起识别为独立登录过期；HTML、普通服务错误不触发凭据提交。用户在测试包完成独立登录后，已读取到实际作业数据。模式切换的凭据复用按本轮新增要求处理，Cookie 继续隔离。
- 工作台窄布局：具体错误被简化成“未更新”，现在沿用已有错误格式器显示可恢复原因。

## 凭据存储

主账号自动登录使用应用内部加密存储：Mac 为 JDK AES-256-GCM，随机密钥及 nonce，用户名作为 AAD，0700 目录和 0600 文件，原子写入及首次密钥发布；iOS 为 CommonCrypto AES-256-CBC/PKCS7 与独立 HMAC-SHA256 密钥，先验证 MAC 后解密，随机 IV，完整文件保护及备份排除。密码不写入普通 Settings、源码或日志。不自动读取旧 Apple Keychain，因此升级后首次保存需要正常登录。未记住主账号时不将密码持久化。

切换模式会复用当前会话中的主账号凭据；若当前进程没有密码，仅在已勾选记住账号时读取加密保存的凭据。使用新模式的验证码及 execution，仅尝试一次登录；异常时清除恢复态并提示手动处理。并发模式切换及手动登录均检查操作代次，旧结果不得覆盖新状态。

Accoding / Patpat 独立凭据按主账号及来源加密隔离，成功登录后才保存；Accoding 的网关会话明确过期时自动恢复一次。网络错误不触发密码登录，连续过期不重复恢复；WebVPN 下 Patpat 仍禁用且不发起认证。

## 真实只读证据

本轮通过手动登录的应用会话调用实际接口，而非历史报告：

- WebVPN 模式：网关接续、CGYY 预约记录、健康云共享 Kotlin 客户端、Mac Python 健康云后端、Accoding 分页作业读取均实际成功。21 日上午重新运行通过，没有使用 20 日旧结果代替。
- Mac 原生界面实际显示 CGYY 的预约记录、校区与楼层筛选、当日房间时段表；健康云手动只读刷新后显示新的更新时间及学期，未使用缓存充当刷新成功；Accoding 首次成功保存独立凭据后可刷新数据。
- 中断一段时间后曾出现网关身份过期及认证响应异常，未作为通过证据；通过正常自动认证恢复后才重新执行上面的两种模式验证。
- 手动探针 `GatewaySavedSessionProbe` 只在内存克隆本地会话；输出仅包含服务名、状态码及结果，不打印账号、Cookie、令牌、业务明细或完整查询参数。失败使进程失败；无凭据登录提交。CGYY token 登录仅用于接续会话，健康云 POST 只允许记录查询。
- 未创建、提交、修改或取消预约；未打卡、建单或付款。

## 验证状态

已运行 Java 21 的完整命令：

```sh
./gradlew :shared:jvmTest :composeApp:jvmTest :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosSimulatorArm64 :androidApp:assembleDebug --no-configuration-cache --console=plain
```

结果成功：shared 480 项（13 跳过），composeApp 376 项（5 跳过），0 失败；总计 838 项执行通过。跳过项为按需真实集成、外部学期样本、Windows 原生运行及需显式启用的 Mac 支付运行测试。此前 Android 报警调度编译阻断本轮未复现，Debug APK 构建成功；未声称 Android 设备界面已验证。

```sh
PYTHONPATH=composeApp/src/jvmMain/resources/td-backend/src python3 -m unittest discover -s composeApp/src/jvmMain/resources/td-backend/tests
./gradlew :shared:iosSimulatorArm64Test --tests '*IosCampusCredentialVaultTest*' --no-configuration-cache --console=plain
./gradlew :shared:gatewayReadProbe --init-script script/gateway-read-probe.init.gradle --no-configuration-cache --console=plain
```

Python 64 项通过；iOS 模拟器 vault 4 项通过，覆盖往返、更新删除、篡改、账户绑定及密钥丢失。手动只读探针在用户已登录的模式下运行，不作为普通无凭据单测。

iOS 完整模拟器应用的 Xcode 构建已成功（最新业务源码；`iosApp`、Debug、generic iOS Simulator、`CODE_SIGNING_ALLOWED=NO`）。Android Debug 及 iOS Arm64 Kotlin 编译均成功。iPhone 真机网络、Android 模拟器界面、Windows/Harmony 原生运行本轮未验证，不用编译结果代替。

另行观察到 Patpat 的独立直连返回 HTTP 503，Judge 在一次刷新中失败。本轮没有修改这两个来源的传输路径，不宣称它们当前可用；需继续单独定位。它们不影响上述三个服务的实际成功证据。

## 模式差异与边界

本轮只记录 WebVPN 下的只读验收结果；其他网络环境、Patpat 与 Judge 的可达性需分别复验。TD 打卡和图片上传均未执行。

## 本地交付

Mac 最终包：`/Users/connor/Desktop/BUCC-Mac-20261021-Gateway-Fix.dmg`，同名 `.sha256` 提供校验。SHA-256：`7dbeb8f06f51083da7ea705b952435a7edb7865e938db06431310585235d0446`。`:composeApp:packageDmg`、`codesign --verify --deep --strict` 及 `hdiutil verify` 均已通过；这是本地测试包，不表示已完成 Apple 公证。旧测试包全部保留。

不签企业证书、不发布、不上传、不推送；没有付款、充值建单或真实预约写操作。当前阶段保留源码、测试与本地安装包，等待用户确认。

## 对应源码与回归测试

- CGYY：`shared/src/commonMain/kotlin/app/bucc/api/local/LocalCgyyApi.kt`、`LocalWebVpnSession.kt`；`LocalCgyyApiBackendTest.kt` 覆盖服务器端 Cookie 桥及两种网关模式。
- 健康云：`shared/src/commonMain/kotlin/app/bucc/td/TdHealthCloudClient.kt`、`shared/src/jvmMain/kotlin/app/bucc/td/DesktopTdBackend.kt`、`DesktopTdService.kt`；桌面 `composeApp/src/jvmMain/resources/td-backend/src/auto_td/healthcloud.py` 与 `ui.py`。回归：`TdHealthCloudClientTest.kt`、`DesktopTdCancellationTest.kt`、`DesktopTdCredentialSyncTest.kt`、Python `test_healthcloud_vpn.py`、`test_ui_credentials.py`、`test_ui_health.py`。
- 模式重连：`composeApp/src/commonMain/kotlin/app/bucc/ui/screens/auth/AuthViewModel.kt`、`ui/screens/menu/SettingsScreen.kt`；`AuthViewModelAutoLoginTest.kt` 覆盖记住/不记住、验证码和 MFA、并发模式切换、旧请求及手动登录取消。
- 主账号加密存储：shared 的 `security/CampusSecureCredentials.jvm.kt`、`MacCampusCredentialVault.kt`、`CampusSecureCredentials.ios.kt`、`IosCampusCredentialVault.kt`；回归为 `MacCampusCredentialVaultTest.kt` 与 `IosCampusCredentialVaultTest.kt`。此次更换主账号及独立作业账号存储；TD 直连模式既有独立密码库没有重构，非直连查询不再访问它。
- 独立作业账号：`shared/src/commonMain/kotlin/app/bucc/homework/HomeworkCredentialStore.kt`、`HomeworkRepository.kt`、`SeparateHomeworkAdapters.kt`；`HomeworkTest.kt`、`HomeworkAdapterTest.kt` 覆盖加密存储隔离、准确识别空 JSON 未认证响应、登录成功才保存、并发及连续过期不循环、普通超时不认证、WebVPN 禁用 Patpat。
- 错误展示：`composeApp/src/commonMain/kotlin/app/bucc/ui/navigation/MainAppScreen.kt` 和 `ui/screens/menu/HomeScreen.kt` 接入来源错误；`HomeTodoViewportTest.kt` 验证窄布局的具体原因展示。
- 可重复手动验证：`script/gateway-read-probe.init.gradle`、`GatewaySavedSessionProbe.kt`、`gateway_health_read_probe.py`。最终源码清除了临时响应形状和凭据存在性诊断，仅保留受限只读验证。
