# 发布与部署

仓库当前保留应用 release 构建。GitHub CI 只运行源码契约；所有五个平台的构建和验证都在本机完成，不由 CI 安装平台 SDK 或打包。

## CI 与本机五平台 gate

`.github/workflows/test.yml` 的 `Source contract` job 运行版本同步检查和 `.github/scripts` 下的 Python unittest 套件，不安装 JDK、Android SDK 或其他原生工具链。Android 与 Windows 候选云构建 workflow 已移除。

开发者使用本机现有工具链为同一个 commit SHA 构建六个发布资产：Android APK、iOS IPA、macOS x86_64 DMG、macOS arm64 DMG、Windows 安装包和 Harmony HAP。签名和 manifest 必须反映实际构建结果。将每个平台构建及测试结果写入测试证据 JSON：

```json
{
  "source_sha": "<完整 commit SHA>",
  "tests": {
    "shared": {"source_sha": "<同一 SHA>", "status": "success", "failures": 0},
    "compose": {"source_sha": "<同一 SHA>", "status": "success", "failures": 0},
    "python": {"source_sha": "<同一 SHA>", "status": "success", "failures": 0}
  }
}
```

在仓库根目录运行本机 gate；用本机已有的 Android/Harmony 公钥证书 SHA-256 指纹作参数：

```bash
python3 script/local_platform_gate.py \
  --directory /path/to/release-assets \
  --source-sha "$(git rev-parse HEAD)" \
  --tests-report /path/to/platform-tests.json \
  --expected-android-fingerprint "$ANDROID_CERT_SHA256" \
  --expected-harmony-fingerprint "$HARMONY_CERT_SHA256"
```

本机 gate 通过后，若要为该 SHA 在 GitHub 写入 `Local five platform gate` 成功状态，再加 `--report-status --repository owner/repository`。该命令使用本机现有 `gh` 登录，不在 GitHub Actions 中运行；脚本只会在六个资产、实际 Android/Harmony 签名、manifest 哈希与版本/SHA、shared/compose/python 三项测试全部通过后报告成功。缺失或失败的构建/测试证据均为失败状态，不得人工补写成功状态。

`.github/workflows/release.yml` 只供手动核对当前 SHA 是否已有此成功状态。它不构建、不签名、不创建或上传 GitHub Release，也不向 App Store 上传。分支保护应要求 `Source contract` 和 `Local five platform gate` 两个 context。

## 正式发布

本机平台 gate 仅确认构建资产与自动化测试证据，不代表真实业务验收。正式公开 Release 仍由 `.github/scripts/release_gate.py` 独立执行：必须提供文件名为 `acceptance-proof.json` 且绑定同一 source SHA 的真实验收证明。`--publish` 先确认远端是同 SHA 的未发布 draft，再仅上传经过本地验证的六个资产、manifest 和验收证明；随后从 draft 下载这些指定文件，重新验证哈希、版本/SHA、APK/HAP 实际签名和验收证明，全部通过后才取消 draft 状态。任何缺件、目标 SHA 错误、上传/下载失败或远端内容不符都会阻止公开。平台 gate 不要求或生成真实取消/预约等验收证明。

真实预约、签到、付款或其他写操作必须在隔离数据或取得明确授权后进行。缺少验收证据时不得公开发布。不要将 release 凭据、SDK、构建产物或签名材料提交到 Git。

### 本机检查与升级密钥

格式检查与共享、界面测试由 `python3 script/run_local_tests.py <新证据目录>` 串行执行（参数以 `--help` 为准）。检查只读，不自动改源码；GitHub 不安装 JDK/SDK 运行格式或平台构建。使用生成的证据文件运行本机平台 gate，不手填成功状态。

Android 2.0.1 经用户于 2026-10-27 明确授权，改用本机已有 `~/Library/Application Support/BUCC/Signing/BUCC-android-release.jks`，别名 `bucc-release`，证书 SHA-256 为 `d2a24530ad320f752bfab2b9235bc68e185003ea2d9908f404c106754c8db176`。密码仅从现有钥匙串服务 `app.bucc.android.release` 读取，不生成替代密钥。此证书与旧版 BD29 发布证书不同，旧版用户必须卸载后重新安装，可能丢失本地数据；不得声称支持覆盖升级。原密钥保留，不覆盖。材料缺失或证书不匹配时停止签名。
