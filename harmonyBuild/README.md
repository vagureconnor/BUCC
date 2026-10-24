# HarmonyOS 本地构建

采用 CPF-KMP-CMP 的 OHOS ARM64 工具链，独立于主工程的 Kotlin / Compose 版本；共享业务和 Compose 页面继续来自 `shared/src/commonMain`、`composeApp/src/commonMain`。

依赖：Java 21、DevEco Studio 的 HarmonyOS SDK、Node、OHPM、Hvigor。将 `OHOS_SDK_HOME` 指向 SDK 内的 `openharmony` 目录。`harmonyApp` 使用 API 20 兼容级别、API 26 目标级别；尚未验证更低版本。

在本目录执行：

```sh
./gradlew :composeApp:prepareHarmonyHost -Pbucc.harmony.ui=true
```

随后在 `harmonyApp` 执行 `ohpm install` 和 DevEco 的 `hvigorw assembleHap --mode module -p product=default -p module=entry@default -p buildMode=debug`。生成本地未签名 HAP；模拟器安装不代表具备真机分发签名。不要提交 SDK 路径、签名材料、账号或构建目录。

使用 Skia 渲染后端。通用浏览器契约由 ArkWeb 实现，系统选择器／扫码／日历／提醒由 ArkTS 宿主实现；网页不获得原生桥接权限。凭据由 HUKS 非导出密钥加密，网络保持证书与主机名校验。

组件探针与产品分开编译：

```sh
./gradlew :shared:linkDebugSharedOhosArm64 -Pbucc.harmony.nativeProbe=true
```

探针只使用合成图片、随机测试身份和本机 TCP 对端。返回 `1` 表示全部通过；`2` 表示组件通过但系统沙箱禁止创建测试符号链接，此项未实际验证；负值表示失败阶段。匿名 HTTPS 对照会读取公开端点，不保存返回内容。RSA 仅输出合成 fixture 的密文，用独立 OpenSSL 解密核对。探针不进入产品二进制。

本轮已在 API 26 ARM64 模拟器显示首次模式页和登录页；原生图片方向、HUKS、AES/MD5、RSA、TCP 等有单独设备探针。校园认证、真实扫码、预约、支付和系统写入功能不能仅凭编译或首屏展示视为验收通过；详见本轮交付验证记录。

上游：[CPF-KMP-CMP](https://gitcode.com/CPF-KMP-CMP)，Kotlin 2.2.21-1.0.0 / Compose 1.9.2-1.0.0。
