# 本地诊断日志

BUCC 在 paper plane 和财务流程中可选记录阶段事件，供开发与验收读取。诊断只写入本机应用数据目录，不发往远端，也不会开启网络监听。日志不替代客户端界面状态；没有相应事件可能表示操作尚未执行、日志已关闭或系统已清理缓存。

## 读取文件

macOS/JVM 客户端将日志保存在：

```text
~/Library/Logs/BUCC/diagnostics/events-0.jsonl
~/Library/Logs/BUCC/diagnostics/events-1.jsonl
~/Library/Logs/BUCC/diagnostics/events-2.jsonl
```

可在终端读取：

```sh
ls -l "$HOME/Library/Logs/BUCC/diagnostics"
cat "$HOME/Library/Logs/BUCC/diagnostics"/events-*.jsonl
```

iOS Simulator 将文件保存在应用数据容器的 `Library/Caches/BUCC/diagnostics/`。不需要复制或导出 app 数据：

```sh
SIMULATOR_APP_DATA="$(xcrun simctl get_app_container booted app.bucc data)"
DIAGNOSTIC_DIR="$SIMULATOR_APP_DATA/Library/Caches/BUCC/diagnostics"
ls -l "$DIAGNOSTIC_DIR"
cat "$DIAGNOSTIC_DIR"/events-*.jsonl
```

如果同时启动了多个模拟器，将 `booted` 换成目标模拟器 UDID。日志是逐行 JSON；按 `correlationId` 聚合可查看一次流程及其嵌套请求。iOS 的 `Caches` 目录可能由系统回收，重要诊断应及时读取。

## 开关与清理

JVM 与 iOS 默认启用本地记录。关闭后，平台在诊断目录中创建名为 `disabled` 的标志文件；清理该文件会恢复记录。macOS/JVM 命令：

```sh
DIAGNOSTIC_DIR="$HOME/Library/Logs/BUCC/diagnostics"
mkdir -p "$DIAGNOSTIC_DIR"
touch "$DIAGNOSTIC_DIR/disabled"   # 关闭
rm -f "$DIAGNOSTIC_DIR/disabled"   # 重新启用
rm -f "$DIAGNOSTIC_DIR"/events-*.jsonl  # 删除全部日志
```

iOS Simulator 使用上一节取得的 `DIAGNOSTIC_DIR` 执行相同命令。也可由应用代码调用 `LocalDiagnostics.setEnabled(false)` 关闭、`LocalDiagnostics.setEnabled(true)` 启用；`LocalDiagnostics.clear()` 删除三个事件文件，但保留当前开关状态。Android 环境与运行验收按用户要求暂缓；Android 平台实现目前关闭持久化，读取为空。

JVM 诊断目录权限为仅当前用户可读写（0700），日志文件为仅当前用户读写（0600）。iOS 目录和文件分别使用 0700、0600 权限。日志位于本机，不要把原始文件发到公开渠道；分享前应再次检查内容。

## 记录格式和隐私边界

每条记录包含本地时间、BUCC 版本、模块、操作、随机本地关联号、阶段、结果、耗时，以及在白名单内的 HTTP 状态、业务代码和端点。异常只记录类型、经过包名前缀筛选的栈帧符号和有限 cause 链，不记录异常原文或服务器响应正文。cause 链最多 8 层，每层最多 24 个栈帧；超出长度的事件会替换为截断标记。

端点只在 HTTPS、标准端口、主机与路径均匹配白名单时保留；URL 查询参数、fragment、用户名和密码不进入日志。纸飞机相关主机路径来自 SPOC 允许列表；财务相关主机仅保留已登记的 ZFW、支付登录、商城和收银台路径。未批准的地址记为缺省，不记录完整 URL。业务代码只保留固定白名单值，其余统一记为 `other`。

日志不记录账号、密码、Cookie、Token、CSRF 值、openid、验证码、成绩、余额、金额、订单标识、二维码内容、请求体或响应正文。HTTP 层只记录状态码与已清除查询串的允许端点。错误消息不写日志；界面可显示简短错误，但日志只保留类型与过滤后的调用位置。

每个文件最多 1 MiB，共三个轮转文件，合计约 3 MiB；达到上限时按 0 → 1 → 2 轮转。保留期为 7 天，写入时清理过期事件。日志没有远端上传、Ktor 全量网络日志或公开监听器。

## 操作与阶段

纸飞机模块记录一键流程、准备、投掷、任务读取、feed 与查看；SPOC 请求将请求事件归入学生身份、任务、feed、查看状态/写入或投掷阶段。财务模块记录网费/校园卡准备、建单或网费表单 POST 交接、支付会话接续，以及内置浏览器的 Cookie 同步和页面导航。阶段包括开始、结束、HTTP、身份、课堂、课堂感受、关键词、投掷、查看状态、查看写入、任务刷新、入口解析、支付接续、Cookie 同步、导航、新窗口、同页跳转和重定向。写请求的传输或响应结果不明确时记录为 `UNCERTAIN`，不会因此自动重放。

网费的一次性表单 POST 由内置 WebView 发出。记录中的 POST `STARTED`/`UNCERTAIN` 描述交接或异常状态；`NAVIGATION SUCCESS` 仅表示 WebView 页面加载完成。页面导航、新窗口被接收、HTTP 状态码或 prepare/create 交接都不能单独证明官网已建立订单或完成付款。日志不含表单体、响应正文、订单状态或付款凭证；需要结合官方页面进行人工确认。

嵌套操作继承同一关联号，便于把一键流程与其内部请求串起来。诊断写入失败不会改变业务操作结果。
