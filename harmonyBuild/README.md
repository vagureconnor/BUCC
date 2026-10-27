# HarmonyOS 本地构建

采用 CPF-KMP-CMP 的 OHOS ARM64 工具链，独立于主工程的 Kotlin / Compose 版本；共享业务和 Compose 页面继续来自 `shared/src/commonMain`、`composeApp/src/commonMain`。

本机 DevEco profile：DevEco Studio 26.0.0.851、HarmonyOS SDK 26.0.0.105、Hvigor 6.26.8、OHPM 26.0.0.630。CI 目标 profile 是官方 Command Line Tools 26.0.0.821（配套 Hvigor 6.26.4、Node 24.14.1）；下载中心需登录，目前尚不能固定公开下载 URL 和 SHA-256。两组 profile 不混报。`harmonyApp` 使用 API 20 兼容级别、API 26 目标级别。

在仓库根目录构建 Release HAP：

```sh
OHOS_SDK_HOME=/path/to/openharmony ./script/build_harmony_release.sh
```

脚本默认从 `/Applications/DevEco-Studio.app/Contents` 发现本机 SDK、OHPM 和 Hvigor，也可通过 `DEVECO_HOME`、`OHOS_SDK_HOME` 覆盖；Node、OHPM、Hvigor 必须在 `PATH` 可用。脚本校验 SDK 版本及 `gradle.properties` 中的统一版本，然后构建 `linkReleaseSharedOhosArm64`，将 Release `.so` 和 Compose 资源暂存到 `harmonyApp`，最后执行 `hvigorw assembleHap --mode module -p product=default -p module=entry@default -p buildMode=release`。产物是未签名 HAP，并带有 SHA-256、版本、源码 SHA、DevEco/Hvigor/OHPM/Node/SDK 实际版本的 manifest。签名只在本机对该 HAP 完成；不要提交 SDK、原生 `.so`、生成资源、HAP 或签名材料。

**托管 CI 当前受官方安装包分发阻塞。** Huawei 下载中心官方 CLI 下载 API 要求开发者登录 cookie 与 CSRF 信息；本仓库未配置华为账号凭据，且不将个人凭据写入 CI。不可用其他版本替代 26.0.0.821。确认固定官方 URL 和校验值后才可启用 CI 下载步骤。

组件探针与产品分开编译：

```sh
./gradlew -p harmonyBuild :shared:linkDebugSharedOhosArm64 -Pbucc.harmony.nativeProbe=true
```

探针只使用合成图片、随机测试身份和本机 TCP 对端。返回 `1` 表示全部通过；`2` 表示组件通过但系统沙箱禁止创建测试符号链接，此项未实际验证；负值表示失败阶段。匿名 HTTPS 对照会读取公开端点，不保存返回内容。RSA 仅输出合成 fixture 的密文，用独立 OpenSSL 解密核对。探针不进入产品二进制。

本轮已在 API 26 ARM64 模拟器显示首次模式页和登录页；原生图片方向、HUKS、AES/MD5、RSA、TCP 等有单独设备探针。校园认证、真实扫码、预约、支付和系统写入功能不能仅凭编译或首屏展示视为验收通过。

上游：[CPF-KMP-CMP](https://gitcode.com/CPF-KMP-CMP)，Kotlin 2.2.21-1.0.0 / Compose 1.9.2-1.0.0。
