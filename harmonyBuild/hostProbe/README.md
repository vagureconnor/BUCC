# HarmonyOS 原生宿主回归

在没有个人账号的专用 HarmonyOS 模拟器中运行。脚本创建新目录，不覆盖既有结果；测试包 `app.bucc.webfixture` 与产品分离。

```sh
python3 run.py /absolute/path/to/new-result-directory \
  --deveco-home /Applications/DevEco-Studio.app/Contents \
  --target 127.0.0.1:5555
```

复用当前产品的 `PaymentHost.ets`、`PaymentWeb.ets`，记录原文件 SHA-256。仅替换原生策略接口为明确的测试替身，并在拦截器末尾提供合成 HTML。所有请求（包括未知资源）均返回本地响应，不访问支付服务器、不生成订单。共享 Kotlin URL／一次性提交规则由 JVM 测试覆盖，本测试不把策略替身当作共享策略验证。

验证三种菜单（`target=_blank`、`window.open`、空白窗口后设地址）、嵌套窗口、同窗口详情及历史返回、逐级返回、合成 Cookie 接续与域隔离、禁止无关域名、根 POST 不重放、再次 POST 被拦截。测试只显示状态，不输出真实用户数据。仅构建时可省略 `--target`。

增加 `--suite host` 可构建独立系统组件测试页（`app.bucc.hostfixtureallow`）。它复制当前选择器、日历和通知实现，仅将日历账号名称与通知目标包改为合成测试标识。需在专用模拟器操作页面按钮和系统权限对话框；脚本启动页面不代表测试通过。

日历用例涵盖取消、拒绝权限、两项合成课程首次写入和重复更新，并清理自己创建的合成日历。通知用例查询／取消自己的提醒；如系统拒绝容量，明确显示 `LIMIT`，单独核对清理和即时通知，不把限制计作定时发布成功。选择器／扫码页只展示返回状态，不显示识别载荷或所选文件内容。相机与二维码的实际识别仍需可用设备验证。
