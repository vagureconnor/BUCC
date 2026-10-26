# BUCC 2.0.1 五端 CI 与发布准备

版本源为根 `gradle.properties`：`2.0.1` / code `201`。Harmony `versionCode` 与 iOS build version 同步为 201。`version_sync.py --check` 只读校验所有平台版本声明。

## CI 设计

PR/main 触发 Android 显式 unsigned Release 编译、iOS 无签名设备 archive、macOS x86_64 与 arm64 DMG、Windows x64 installer、Harmony Release HAP 编译及版本/发布契约测试。共享业务和 Compose 业务测试通过 macOS arm64 runner 上的 `:shared:jvmTest :composeApp:jvmTest` 执行；CommonTest 会随 JVM test task 执行，只有依赖真实设备的运行时验证仍留给设备验收。`platform-gate` 用 `if: always()` 汇总每项 job result，只有每项 `success` 才通过；无 skip 或 `ios_only` 绕行。Linux 保留为可选能力，不进入五端门禁。CI build job 仅有 `contents: read`，不使用自托管 runner。

Android Release signing 配置在无完整凭据时不创建；显式 `-Pbucc.android.unsignedRelease=true` 仅允许无签名编译，且不得同时传入签名值。这个验证通过 Android Release packaging / signing-validation task 执行，不会在配置 Android project 时触发，因此 iOS archive 和 JVM tests 不需要 Android secrets。正式 draft workflow 不传 unsigned 开关，先验证现有四个签名 secrets 并运行 `apksigner verify`；没有密钥或证书校验失败就停止，不回退到 debug APK。

## Harmony 工具链可行性检查

Huawei 官方 [DevEco Studio 26.0.0 版本说明](https://developer.huawei.com/consumer/en/doc/harmonyos-releases/deveco-studio-new-features-2600)列出 Command Line 26.0.0.821、Hvigor 6.26.4、OHPM 26.0.0.630、Node.js 24.14.1 和 HarmonyOS SDK 26.0.0 Release。官方 [CLI 获取指引](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-commandline-get)说 CLI 已嵌入 SDK，无需另行下载。

检查下载中心页面 JavaScript 后，发现 CLI 文件地址通过 `getToolVersionDownloadUrl` 后端接口获取。接口匿名请求返回 `cookie authInfo is null`；补发 CSRF header 后仍需要登录认证信息。当前可公开读取的官方 archive 页面只列出旧 Command Line Tools 2.0.0.2 及 SHA-256，不能代表 CLI 26.0.0.821。官方 DevEco 26 页面提供版本与配套信息，没有匿名可固定的安装包 URL/hash。因此没有把旧版工具链伪装成当前工具，也没有写一个声称可成功的安装工作流；Harmony job 以明确错误退出，门禁据此保持失败，直到官方固定下载源与 SHA-256 可用于托管 CI。

本机 DevEco 26 安装实际报告：IDE 26.0.0.851、SDK 26.0.0.105、Hvigor 6.26.8、OHPM 26.0.0.630；当前 shell Node.js 为 24.13.1。官方 CLI 26.0.0.821 页面记载对应 Hvigor 6.26.4、Node 24.14.1，这是另一组工具 profile，不与本机 IDE profile 混报。`script/build_harmony_release.sh` 从本机 DevEco Home 发现 SDK/OHPM/Hvigor，要求 Node/Hvigor/OHPM 在 PATH 可用，硬校验 SDK 26.0.0.105，并把检测到的 IDE/Hvigor/OHPM/Node/SDK 版本原样写入 unsigned HAP manifest。若使用 CLI profile，应由操作者提供对应固定分发包和校验值后再另行接入。

Harmony Release 任务已从 debug 改为 `linkReleaseSharedOhosArm64` 并单独暂存 Release `.so`。脚本将运行 Release native link、Compose 资源 staging、Hvigor release assembleHap，并生成 unsigned HAP SHA/version/source/实际工具链版本 manifest。签名只在本机完成。

## Release 安全边界

手动从 main SHA 构建；iOS 只生成无 Apple 签名的设备 archive/unsigned IPA，不读取 Apple 证书、不上传 App Store Connect。Android 用既有四个签名 secrets 并由 `apksigner` 验证，macOS x86_64/arm64 保持 ad-hoc 打包，Windows 初始化 Git Bash、Go、`GOOS=windows` 与 `GOARCH=amd64`。只有独立 draft job 可获得 `contents: write`；草稿构建流程不将其转为公开发布。

草稿汇总脚本只接受六个必需资产：Android APK、iOS unsigned IPA、macOS x86_64/arm64 两个 DMG、Windows x64 installer、Harmony HAP。它生成 draft `manifest.json`，记录版本、同一源码 SHA、文件 SHA-256 和架构。Harmony 尚未签名时 draft manifest 明确标记 unsigned 且指纹为空；在本机签名并用正式文件名 `BUCC-Harmony-v2.0.1.hap` 替换草稿资产后，下载完整草稿资产目录，设置 `HAP_SIGN_TOOL_JAR` 并再次运行 `create_release_manifest.py` 重建指纹及 SHA，再运行最终校验。最终校验脚本要求完整平台资产、相同版本和 SHA、逐文件 SHA-256、正确架构、预期 Android/Harmony 公共签名指纹，以及同一源码 SHA 的真实验收证明。脚本通过 `apksigner` 与官方 `hap-sign-tool.jar verify-app` 从真实 APK/HAP 证书提取指纹，并与预设指纹和 manifest 交叉比对。调用时必须设置 `HAP_SIGN_TOOL_JAR` 并提供 Android/Harmony 预期指纹与 acceptance proof。默认仅核验并保持草稿；只有显式传入 `--publish` 且草稿 target SHA 与证明相同，脚本才公开 Release。此轮真实验收尚未完成，缺失 proof 时脚本明确拒绝发布。未运行任何 push、dispatch、创建 PR 或公开发布操作。

## 本地检查

- `python3 .github/scripts/version_sync.py --check`
- `python3 -m unittest discover -s .github/scripts -p 'test_*.py'`
- `bash -n script/build_harmony_release.sh`
