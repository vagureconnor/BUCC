# 2026-10-19 签到查询、Judge 作业与未签名 iOS 交付

权威源码：`/Users/connor/Desktop/BUCC/source`，分支 `codex/workbench-ios`。开始时 HEAD 为 `535aa52e51eeba7db569f01e4af6c31857a965fd`，本轮功能提交 `77724ca`、原生兼容修正 `70d313a`，安装包源码为 `70d313a21a711c8e4b7a7d8a9d5d424ea697d985`；官网发布提交 `8a824e18b5c1db4de81887fc5ef0c5a3bda5cd44`。只从权威源码导出跨平台构建输入，没有从历史包还原源码。

## 功能与原因

- 新增独立“签到查询”入口，学期与课程选择置于页面上方；按日期／按课程两种视图共用整学期历史。课程名相同也按稳定 ID 区分，空记录课程在课程视图保留。学期切换取消旧请求并以请求代次防止旧结果覆盖，账号／连接模式变化也拒绝旧结果。
- 共享层复用既有 iClass 安全会话，通过官网学生端实际使用的学期、课程和单课程整学期历史接口查询；没有新增签到写调用。官方历史空响应仅含 `STATUS=2`，曾使整学期查询失败；现在仅将这一已核实的精确空响应作为空记录，其他异常继续报错。未知出勤状态不猜测为出勤。接口路径、参数与边界见 `docs/attendance-query-api.md`。
- Judge 原本已注册为待办来源，但汇总后重复批量获取详情，后一请求失败会使整个来源消失。现在使用已包含提交数量等信息的摘要，详情仍按用户打开时获取；按课程 ID＋作业 ID 去重，显示“Judge（希冀）”。
- 官网新增上述功能介绍与真实未签名 IPA 下载信息。飞机素材和形状不变，哈希回归继续检查。下载函数仅接受受控的 IPA 名称，支持 HEAD 和单段 Range；校验清单、分块长度及 SHA 标识。安装包超过 Pages 单文件限制，使用 Cloudflare KV 分块存储并流式下载，没有开启收费 R2。

主要文件：

- `shared/src/commonMain/kotlin/app/bucc/attendance/{Attendance,LocalAttendanceApi}.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalSigninApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/homework/{CampusHomeworkAdapters,Homework}.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/attendance/{AttendanceViewModel,AttendanceScreen}.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/{navigation/MainAppScreen,screens/menu/WorkbenchFeatures}.kt`
- `website/src/{index.html,privacy.html,style.css}`、`website/functions/downloads/[file].js`
- `script/{package_unsigned_ipa,upload_ipa_kv}.py`

## 本轮实际验证

所有校园业务验证均只读；未签到、预约、创建订单、支付或提交作业。授权密码通过隐藏输入仅供隔离测试进程使用，临时脚本中的账号标识已移除；账号、密码和会话未写入源码、日志、报告或公开目录；真实响应载荷没有保存。使用 `luna-worker` 协助官方前端接口梳理、界面及测试、打包与下载脚本、Windows 构建，主 Agent 负责整合、代码审查与最终验证。

| 验证 | 本轮结果 |
| --- | --- |
| WebVPN 真实只读探针 | 统一认证、iClass 学期与整学期课程历史成功，课程引用完整性通过；Judge 作业读取成功 |
| Java 21 JVM 全量回归 | shared 438 项：425 通过、13 跳过；Compose 366 项：364 通过、2 跳过；0 失败 |
| 390px Compose 界面 | 日期／课程切换、空课程、筛选断言通过；截图为虚构课程，不含个人记录 |
| Android | `:androidApp:assembleDebug` 成功 |
| macOS | `:composeApp:packageDmg` 成功；DMG 校验、`codesign --verify --deep --strict` 均通过，原生库为 arm64，内含 TD 后端与新的 AttendanceScreen 类 |
| iOS | Xcode Release iphoneos arm64 构建成功；IPA 无签名、无描述文件、无签名元数据，包内已扫描确认不含本次测试账号及密码 |
| HarmonyOS | `compileKotlinOhosArm64`、`prepareHarmonyHost`、Hvigor `assembleHap` 成功；未签名 AArch64 HAP 的原生库 Build ID 与当前构建一致，包内字节匹配剥离后的库 |
| Windows | Windows 11 ARM64 虚拟机中使用 x64 Java 21 工具链构建成功；804 项测试中 786 通过、18 跳过、0 失败；ZIP 完整性、x64 PE 启动程序与后端、新增 UI/API 类均核验通过 |
| Python 打包脚本 | 3 项单元测试通过，包括拒绝模拟器、保留原包、拒绝覆盖旧输出 |
| 官网与下载函数 | 构建成功，12 项 Node 测试通过，含 Range、丢失分块、方法限制、路径／清单校验和真实发布元数据 |
| 线上实际下载 | 官网内容已更新；HEAD 200、Range 返回正确 16 字节；完整下载 27,531,967 字节，SHA-256 与本地 IPA 一致 |

本轮实际命令（Java 21 与现有 Android SDK）：

```text
./gradlew :shared:jvmTest :composeApp:jvmTest --console=plain
./gradlew :androidApp:assembleDebug :composeApp:packageDmg --no-configuration-cache --init-script <本轮独立 Mac 输出目录配置> --console=plain
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -sdk iphoneos -destination generic/platform=iOS -derivedDataPath <本轮目录> CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= build
./harmonyBuild/gradlew -p harmonyBuild -Pbucc.harmony.ui=true -Pkotlin.native.home=<本机2.2.21-1.0.0编译器> :composeApp:compileKotlinOhosArm64 --console=plain
./harmonyBuild/gradlew -p harmonyBuild -Pbucc.harmony.ui=true -Pkotlin.native.home=<本机2.2.21-1.0.0编译器> :composeApp:prepareHarmonyHost --console=plain
hvigorw assembleHap --mode module -p product=default -p module=entry@default -p buildMode=debug --no-daemon
gradle.bat :shared:jvmTest :composeApp:jvmTest :composeApp:createDistributable --console=plain --no-configuration-cache --no-daemon -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx3072M -Dfile.encoding=UTF-8 -XX:TieredStopAtLevel=1"
python3 -m unittest discover -s script/tests -v
npm --prefix website run build
npm --prefix website run check
```

JVM 运行启用 `BUCC_WEBKIT_MOCK_UI=1`，校园支付仅使用合成页面。自动跳过的项主要是未启用的真实校园集成测试、平台专用 Windows 原生测试及未提供的样本；不把跳过记为通过。真实读探针为仓库外的 opt-in 测试，只使用内存设置和隐藏输入的临时凭据。

首次渲染测试有 desktop 导入遗漏，首次 iOS 构建发现 JVM 专用 `toSortedMap`；均已修正并重新验证，最终成功日志不使用首次失败结果替代。鸿蒙首次命令停在重复下载编译器，改用本机已有工具链后编译成功。Windows 新目录冷构建在 x64 工具链模拟环境下出现耗时很长的 Kotlin FIR/C2 编译，保留日志并仅结束本轮构建进程，以 `-XX:TieredStopAtLevel=1` 进行一次重跑后成功；该设置未进入打包应用的 JVM 配置，ZIP 已核验。构建包含弃用、SDK 和 PyInstaller 警告，不将成功描述为无警告。Cloudflare 首次下载索引上传失败，重试成功后才部署新页；Python 客户端访问返回 403，浏览器和 curl 实际访问、完整下载均通过。

## 本地文件与线上发布

本轮文件目录：
`/Users/connor/Documents/Codex/2026-10-17/bucc-cloudflare-agent-users-connor-desktop/outputs/BUCC-attendance-70d313a/`

- `BUCC-1.9.0-macOS-arm64-70d313a.dmg`
- `BUCC-1.9.0-Android-debug-70d313a.apk`
- `BUCC-1.9.0-iOS-unsigned-70d313a.ipa`
- `BUCC-1.9.0-HarmonyOS-arm64-preview-70d313a-unsigned.hap`（100,534,002 字节；未签名开发预览）
- `BUCC-1.9.0-Windows-x64-70d313a.zip`（157,711,690 字节；解压完整目录后运行 `BUCC/BUCC.exe`）
- `checksums.json`、`ipa-validation.json`、`public-download-validation.json`

IPA：27,531,967 字节，SHA-256 `626f844e27c89d8df1810f851740fd03d4376386b6ab79fb66283b9c55f996b0`。真机 arm64（Mach-O platform 2），不是模拟器包。没有使用企业证书或个人设备描述文件；需要使用者自行签名后安装，不能直接安装。

官网：<https://bucc-6ga.pages.dev/>；本轮部署：<https://47ccddec.bucc-6ga.pages.dev/>。
下载：<https://bucc-6ga.pages.dev/downloads/BUCC-1.9.0-iOS-unsigned-70d313a.ipa>。
已依用户后续授权公开部署官网并上传上述未签名 IPA，没有推送 Git 远端，也未公开上传本轮其他平台安装包。

证据目录：
`/Users/connor/Documents/Codex/2026-10-17/bucc-cloudflare-agent-users-connor-desktop/outputs/attendance-evidence/`
包含 390px 日期／课程视图、官网真实下载卡片截图及脱敏只读结果。构建日志在同任务 `work/`，主要为 `attendance-full-regression-4.log`、`attendance-mac-android-build.log`、`ios-attendance-build-2.log`、`attendance-harmony-compile-2.log`、`attendance-harmony-prepare.log`、`attendance-harmony-hap-build.log`、`attendance-public-download-curl-check.log`、`windows-attendance-70d313a-build-2.log`。

## 验证边界

- iPhone 真机需要自行签名安装后复验；本轮没有设备安装成功证据。
- 真实校园数据验证仅覆盖 WebVPN 路径，不代表其他网络环境均已验收。用户所有历史学期仍需按实际账号复验。
- 本轮 Android、Mac 新包未使用真实用户配置启动，避免既有自动业务设置触发写操作；页面交互由合成数据的实际渲染测试覆盖。
- Windows 未启动真实配置的成品应用，未在物理 x64 电脑复验；本轮鸿蒙 HAP 没有新设备运行证据。Windows 与 HarmonyOS 的构建和运行证据分开记录，不能由一次编译承诺所有平台、所有组件、全部实机行为通过。
- 所有历史包、备份与归档保留，没有删除或覆盖历史交付文件。
