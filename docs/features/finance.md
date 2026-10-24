# 校园财务

BUCC 财务分校园卡与网费两个独立入口。网费通过原生界面完成动态验证码登录并读取余额、流量；需要短信验证时手动继续。充值表单在应用内输入金额和充值验证码，建单与支付页面接续仍需按各自证据单独验收。校园卡流程已有本地实现及 mock 覆盖，但官方实网建单和支付接续尚未验证。

## 当前验收状态

用户已确认网费原生验证码登录成功并展示了数据；这里仅记录流程通过，不保存账号或数值。用户随后自行进行过一次充值尝试，但建单结果不明确。Agent 没有发起订单写请求；由于结果未知，不应重试或据此声称已建单、已支付。校园卡本地 SSO/建单请求流程已有实现与 mock，但官方实网建单和支付页接续仍未验证。
## 本轮修正

校园卡、网费使用独立入口和只读加载。网费本次进入须先在原生界面完成登录验证码；既有 ZFW 会话不能跳过该门控。登录按公开官方脚本取得动态公钥，以 RSA PKCS#1 加密密码，先调用 `POST /site/validate-user`，仅 `success=true` 且 `inputSms=false` 时提交登录表单。需要短信验证时显示手动短信输入，再调用 `/site/validate-smscode`；不绕过二次验证。凭据由用户授权的系统安全存储或本次主登录提供，不要求另填网页账号密码。

刷新登录验证码调用官方 Yii `GET /site/captcha?refresh=1`，读取返回 `url` 后取图片。不能仅改变图片地址的缓存参数。官方页面提示“不在缴费时间”时，客户端明确停止准备，不提交订单。充值验证码仅从官方充值页脚本的 `refreshUrl` 动态读取刷新入口；不存在该字段时不声称已确认生成新验证码。网费三项流量从 `/home` 独立读取，余额门户失败不会阻断已验证的 ZFW 流量。

校园卡的 BUCC 本地流程已实现并由合成 mock 覆盖 CAS Cookie、官方 SSO 回调身份、加密请求、字段/金额处理、单次提交和失效 CAS 分支；这不证明官方实网建单或支付接续成功。网费登录和数据展示已有上述用户确认；订单结果仍不明确。详细证据见 [校园财务契约摘录](campus-finance-contract-20261014.md)。

## 官方页面与接口

| 功能 | URL / 方法 | 参数与认证 | 验证状态 |
| --- | --- | --- | --- |
| 网费流量 | `https://zfw.buaa.edu.cn/home`，`GET` | 依赖 ZFW 登录会话 Cookie；流量表格由首页 HTML 返回 | 用户确认 BUCC 原生验证码登录后已展示数据；不记录账号或数值 |
| 网费充值表单 | `https://zfw.buaa.edu.cn/pay/card`，`GET` 准备表单，随后 `POST` 同一路径 | BUCC 自己的 ZFW Cookie；解析唯一动态 `_csrf-<id>`，以及 `OneCardForm[cardNo]`、`OneCardForm[productId]`、`OneCardForm[amount]`、`OneCardForm[verifyCode]`；解析验证码图片 URL。建单使用 `application/x-www-form-urlencoded` | 表单字段来自官方 DOM；用户确认原生登录和数据展示成功，并自行尝试过一次充值，但建单结果不明确。Agent 未发起该 POST，不应重试；未确认实际订单响应。没有借用 Chrome Cookie |
| 网费验证码 | 表单给出的 `https://zfw.buaa.edu.cn/pay/captcha?v=<动态值>`，`GET` 图片 | 动态查询参数 `v`，由同一 BUCC ZFW 会话读取；验证码由用户输入 | 官方 DOM 确认路径形态；代码检查响应为图片后交给界面显示；没有自动识别验证码 |
| 网费收银台入口二维码 | `https://zfw.buaa.edu.cn/qrcode?url=<cashier URL>`，`GET` 图片 | `url` 参数承载官方网费流程返回的收银台地址；与 ZFW 页面会话/订单流程关联 | 已登录的官方测试页面实际显示此图片 URL 形态；只验证生成了收银台入口二维码，没有扫码，也未付款。不能将此入口码等同于支付渠道二维码 |
| 校园卡 SSO | `/login?service=<encoded service>&noAutoRedirect=1`，由浏览器导航 | 官方入口脚本从公开无 openid 入口使用公开 fallback `112233` 组装 service；普通浏览器 `usertype=1` | 2026-10-15 Agent 已在 iOS 模拟器完成 BUCC 主登录，并以应用自己的 CAS Cookie 只读查询；官网回跳路由为 `/card#/pages/homepage/index/index?`（无尾斜杠），参数名 `openid`、`usertype`。动态值不记录。 |
| 校园卡首页/充值入口 | `https://m.icw.buaa.edu.cn/card/home/openHomePageApp`，`GET` | `openid`、`usertype`、`ua`、`orgid` | 本轮应用会话只读响应中，`data.funcList` 有两个相同 `linkurl` 的充值菜单：`functionname=充值`（`description=首页`）和 `functionname=校园卡充值`（`description=卡务中心`）。不能假设 route 唯一；本地代码优先匹配校园卡充值标签，并对仍有歧义的菜单停止。 |
| 校园卡支付方式 | `https://m.icw.buaa.edu.cn/card/queryPayInfoList`，`GET` | `menuid`、`openid`、`orgid`；菜单 ID 从 `data.funcList` 识别 | 本轮应用会话只读返回 `success=true`、`data.data=[]`，未见“不在缴费期间”提示。官方 `0cc5cd1e.js` 的 `getPayInfoList` 规则是：空列表且 message 不含该提示时使用脚本默认方式列表，含 `payway=10` 航财通与 `payway=6` 银校转账。当前 BUCC 目标仍只用航财通；实时空列表本身不表示支付方式查询失败。 |
| 校园卡初始化 | `https://m.icw.buaa.edu.cn/card/cardpay/openCardPay`，`POST` JSON | `{openid}`；官方 wrapper 将业务 JSON 加密封装到 `datajson` | 脚本读取 `data.cardPay`、`data.disableidserialstart` 和可选 `data.appid`。本轮 BUCC 自有 CAS 会话只读初始化成功；没有传金额，也没有建单。 |
| 校园卡航财通充值 | `https://m.icw.buaa.edu.cn/card/recharge/rechargeByHct`，`POST` JSON | `idserial`、`txamt`、`payway`、`openid`、`payWay=10`、`tradetype=WAP`、`usertype`、`orgid`；`txamt` 先由元转为分，再由 wrapper 加密为 `datajson` | 页面代码读取 `data.success`，成功时从 `data.resultData.mweb_url` 跳转。字段与响应路径来自本轮公开静态脚本；没有提交充值请求或创建订单 |
| 校园卡建单 | `https://mall.cc-pay.cn/api/payment`，`POST` JSON，带动态 `t` 查询参数 | `targetId`、`targetType`、`money`、`itemId`、`choice`、`feeInfo`、`fromType`；依赖校园卡页面建立的 `mall.cc-pay.cn` 登录 Cookie（包括 `connect.sid`） | 先前授权的官方网页未支付测试订单曾由 DevTools 实际捕获，HTTP 200；请求 JSON 字段名与响应结构已核对。响应顶层含 `success`、`type`、`message`、`data`；`data` 含订单状态字段与 `cashierUrl`。所有个人字段值、Cookie、订单标识均不记录。仅验证校园卡商品，不能据此推断网费建单契约 |
| 官方页面其他支付分支（非目标流程） | `https://m.icw.buaa.edu.cn/card/wxpay/...`、`alipay/...`、`ccbpay/...`、`bankcard/...` | 脚本包含微信、支付宝、建行、银校转账分支 | 以下分支是官方脚本记录；BUCC 目标流程只采用航财通 `payway=10`，其他方式未实现或未调用 |
| 校园卡微信充值 | `https://m.icw.buaa.edu.cn/card/wxpay/transferFromWx2Card`，`POST` JSON | 表单数据至少包含 `txamt`、`idserial`，并添加 `openid`、`payWay=1`、`tradetype`（`WAP` 或 `JSAPI`）、`usertype` | 当前官方页面脚本确认路径和请求构造；WAP 分支使用 `resultData.mweb_url` 与 `partnerjourno`；JSAPI 分支会继续请求 `wxpay/getJssdk`。本轮未新建订单或调用支付接口 |
| 校园卡微信 JSAPI 参数 | `https://m.icw.buaa.edu.cn/card/wxpay/getJssdk`，`GET` | `openid`、`jssdkurl` | 当前官方脚本确认该请求在 JSAPI 支付分支中读取 `appid`、时间戳、nonce 和签名字段；未调用 |
| 校园卡支付宝充值 | `https://m.icw.buaa.edu.cn/card/alipay/transferFromAlipay2Card`，`POST` JSON | 与微信分支相同的表单数据，`payWay=4` | 当前官方页面脚本确认路径和响应处理：若响应为 HTML form 会自动提交，否则读取 `data.url` 与 `data.data` 导航；没有取得实时响应正文 |
| 校园卡建行/银校转账 | `https://m.icw.buaa.edu.cn/card/ccbpay/transferFromCcb2Card`、`/bankcard/rechargeCard`，`POST` JSON | 建行路径沿用充值表单；银校转账还要求校园卡密码 | 当前官方页面脚本确认路径；本客户端未实现，也未调用。银校转账会直接执行充值，不作为扫码支付方式 |
| 收银台支付方式 | `https://cashier.cc-pay.cn/api/pay_ways`，`GET` | `payScene`、`goodsId`；收银台会话及订单上下文 | 由已加载的官方收银台前端脚本确认请求构造；未独立调用 |
| 收银台订单查询 | `https://cashier.cc-pay.cn/transaction`，`GET` | `id` 为订单标识 | 由官方前端脚本确认请求构造；已打开的收银台页显示订单关闭状态，未读取或保存订单号 |
| 收银台发起支付 | `https://cashier.cc-pay.cn/transaction/pay`，`GET` | `id`、`payWayId`、`phoneNumber`、`ecCode` | 官方前端脚本使用该请求发起支付；先前用户在未支付测试单上选择微信后，收银台显示扫码页，页面仍显示订单未完成。因 DevTools 在此步骤未保留对应支付请求行，Method、动态支付参数及响应字段目前只有脚本证据，尚未完成实时请求验证 |

本轮 Chrome 只读 SSO 证据之外，Agent 已在 iOS 模拟器完成 BUCC 主登录，并以该应用自己的 CAS Cookie 成功只读完成官方 SSO 回跳、菜单查询、支付方式查询及 `openCardPay` 初始化；只记录路由与字段名，不记录动态身份值。两个菜单共享同一路由，BUCC 按菜单文本/说明识别并优先选择“校园卡充值”。支付方式接口返回空数组且无缴费关闭提示时，官方脚本 fallback 默认方式列表，BUCC 当前目标为航财通。此处只验证到充值准备阶段；iOS 只读准备界面已通过，未建单、未跳转收银台、未扫码或付款，不能称支付接续成功。历史 `mall.cc-pay.cn/api/payment` 未支付订单 Network 记录仍是独立证据。

先前网费测试到达“北航手机支付”页面，并呈现由 `/qrcode?url=` 返回的收银台入口二维码；未扫码或付款。该入口二维码指向 CC-pay 收银台，不能证明渠道支付二维码或支付状态接口。先前 DevTools 未捕获网费表单 POST，也未确认网费收银台的支付渠道请求、二维码载荷或状态回调。校园卡页面中的 `/WX_pay.png` 仍只是扫码操作说明图。

## BUCC 网费充值代码路径

财务页面现有输入金额、验证码和提交入口。网费 `OfficialCampusRechargeBackend` 使用 BUCC 自己当前连接模式下的 Cookie 存储请求 `GET /pay/card`，解析唯一动态 `_csrf-<id>`、`OneCardForm[cardNo]`、`OneCardForm[productId]` 和验证码图片地址，再使用同一会话加载验证码。校园卡 `OfficialCampusCardRechargeBackend` 使用应用自己的 CAS Cookie 准备官方 SSO 回调身份，并执行菜单发现、`Hct10` 支付方式确认、缴费期间判断和 `openCardPay` 只读初始化；用户提交金额后才发送一次加密 `rechargeByHct`，将金额换算为分，再导航到官方返回的 `mweb_url`。校园卡不走独立 `pass` 登录。两类流程均不借用 Chrome Cookie、不自动重试；校园卡本地 mock 已覆盖关键分支，但实网建单和支付接续仍未验证。

用户输入金额和验证码后，controller 校验正数金额并规范到分；每次准备状态最多尝试一次 `POST /pay/card`，请求保留动态 CSRF、卡号与商品 ID，并填入金额和验证码。提交前先锁定本次尝试；取消、超时或响应不明时不会自动重试，界面要求不要重复提交。代码只接受成功或 302/303 响应，并从响应中提取带订单 ID 的官方收银台地址。之后会对该地址执行只读 GET 检查；若检查到登录跳转、密码表单或非成功响应，流程停止，不会把用户送去支付网站登录。

收银台地址通过校验后，BUCC 在内置 WebView 打开官方页面。支付页 Cookie 仅从 BUCC 按连接模式隔离的本地 Cookie 存储中提取，并按支付 URL 的域、路径和 Secure 条件匹配；不会导入 Chrome Cookie。主框架导航只允许 HTTPS `cashier.cc-pay.cn` 或 `mall.cc-pay.cn`，拒绝 `pass.cc-pay.cn`、登录路径及其他外域。支付方式与二维码由官方页面展示，BUCC 不自行生成支付码，也不声称已验证支付状态。

网费 shared 模拟请求测试覆盖本地构造和解析；用户确认登录及数据展示成功，但其一次充值尝试结果不明确。Agent 未发起该建单请求，也没有支付完成证据，不应重试。校园卡合成 mock 已通过本地 SSO/加密/字段/金额/单次提交/失效 CAS 场景；未进行校园卡实网建单或支付接续验收。

## 最近构建与验证

本轮 shared 测试 318 项通过、12 项跳过；compose 测试 233 项通过，合计 551 项通过、12 项跳过、0 项失败。另有 Accoding 授权真实登录与只读查询测试 1 项通过。iOS Kotlin 与 Xcode Simulator 构建、安装和启动通过；Agent 已在 iOS 模拟器完成主登录与校园卡只读准备，且校园卡只读准备界面已通过。Mac 独立包 `verified-20261015-account-check` 构建成功，`app.bucc`、版本 `1.9.0` 与图标一致，TD/Python/native 库均为 arm64，签名验证与进程启动通过。以上结果不代表校园卡已建单或支付。

### 待补的四段 Network 证据

以下步骤仍需补齐对应的官方页面 Network 请求与响应证据；现有 DOM、脚本、历史页面画面、一次结果不明的用户充值尝试以及 mock 测试均不能替代这些实网证据：

1. 网费充值表单提交至 `/pay/card`：实际 POST 参数、响应和后续跳转。
2. 收银台支付方式查询（`/api/pay_ways`）：实际请求参数与返回的渠道列表。
3. 收银台发起支付（`/transaction/pay`）：实际 Method、动态参数及响应字段。
4. 收银台订单状态读取（`/transaction`）及后续状态变化：实际响应和状态判定依据。

补证时不保存个人余额、卡号、账号、Cookie、验证码、订单标识或二维码载荷，也不执行付款。

## 当前限制

- Patpat 已恢复独立原生登录、只读查询、缓存和设置入口。当前契约依据公开前端脚本整理并由 mock 覆盖；本轮按官方 JSON 字段进行的一次授权真实登录返回 HTTP 400，已停止重试；该账号的个人数据读取未验证。此前一次不含凭据的空 JSON 登录请求曾返回 HTTP 400，另有一次网络探测超时；两项仅作为历史现象记录，不能据此判定接口不可用或阻止当前入口。

### Patpat 官方静态脚本证据

2026-10-14 只读访问公开根 `http://211.71.15.49/` 返回 200，并在内存中检查页面引用的 `/assets/index-B8N6BN9_.js`。没有登录，也没有请求账户、课程、作业或提交数据。脚本证据如下：

| 流程 | 方法与路径 | 脚本读取/发送字段 | 证据边界 |
| --- | --- | --- | --- |
| 前端 API 客户端 | Axios `baseURL: "/api"`，`withCredentials: true` | 同源 Cookie 随请求发送 | 静态配置证据，不代表 BUCC 会话或跨站请求已验证 |
| 登录 | `POST /api/auth/login` | JSON `{buaaId, password}`；响应外层读取 `status` | 仅确认当前公开脚本的请求构造。历史 400 若使用旧字段 `username` 而非 `buaaId`，可能是原因之一，但尚未验证，不能当作已确认根因 |
| 用户信息 | `GET /api/account/self` | 前端读取响应 `data`，并访问其中的 `id`、`buaaId`、`name`、`teacher`、`ta` 等属性 | 仅静态字段读取证据；未获取任何个人账户数据 |
| 可见实验作业列表 | `GET /api/task/lab/query`；教师全量列表为 `GET /api/admin/task/lab/query` | 响应数组取自 `data.data`，列表使用 `id`、`title` | 学生列表分支由前端选用；未独立请求 |
| 可见迭代作业列表 | `GET /api/task/iter/query`；教师全量列表为 `GET /api/admin/task/iter/query` | 响应数组取自 `data.data`，列表使用 `id`、`title` | 学生列表分支由前端选用；未独立请求 |
| 实验作业详情 | `GET /api/task/lab/query/{id}` | 读取 `title`、`content`、`startTime`、`deadlineTime`、`endTime` | 静态字段映射证据 |
| 迭代作业详情 | `GET /api/task/iter/query/{id}` | 读取 `title`、`content`、`startTime`、`deadlineTime`、`endTime` | 静态字段映射证据 |
| 实验报告提交状态 | `GET /api/task/lab/score/{id}` | 前端检查 `data.data` 并读取 `filename`；报告提交为 `POST /api/task/lab/submit/{id}`，multipart 字段 `file` | 只读状态路由及上传构造来自脚本；未查询或提交 |
| 迭代提交状态 | `GET /api/task/iter/score/{id}` | 前端读取 `data.data.filename`；代码提交为 `POST /api/task/iter/submit/{id}`，multipart 字段 `file`、`language` | 只读状态路由及上传构造来自脚本；未查询或提交 |
| 课程 | `GET /api/course/all`；选课为 `POST /api/course/select/{id}` | 课程列表对象读取 `id`、`code`、`name`、`semester`、`active`；当前课程由 `course` Cookie 决定 | 静态字段及调用证据；未请求课程数据或选择课程 |

2026-10-18 修正响应层级：表中 `data.data` 为 Axios 回调里的 `response.data.data`，第一层是 HTTP 响应对象的正文属性；原始 JSON 只有一层 `data`。BUCC 已解析正文，应读取 `root["data"]`，不能再要求 `root["data"]["data"]`。列表为数组，详情为对象，未提交的状态允许 `data: null`。此前 mock 多包了一层，导致测试未暴露正常响应被拒绝的问题；本轮已按该契约修正 fixture，并覆盖同名不同 ID 及跨实验/迭代相同数字 ID 的保留。真实账号验证仍需以应用只读行为单独验收。Axios 响应对象定义见[官方说明](https://axios-http.com/docs/res_schema)。

BUCC 的 Patpat 原生登录和只读查询流程现依据脚本契约实现并由 mock 覆盖。脚本及 mock 不能证明当前账号认证或个人数据查询成功；真实登录、缓存结果与设置入口应以应用实际行为单独验收。

## 余额读取与限制

- 余额由 `CampusFinanceApi` 通过信息化服务平台只读接口读取；网费流量由 ZFW 首页 HTML 解析。读取余额不代表充值建单成功。
- `parseZfwHomeTraffic` 依据表格标题定位对应列；缺字段时返回错误，不把缺失数据当作零。
- ZFW 表单准备必须依赖 BUCC 自己可用的 ZFW Cookie；Chrome 会话独立，缺少 BUCC 会话时不可用。
- 校园卡官方 Chrome 与 BUCC 自有 CAS 会话的 SSO/菜单只读证据均已取得；`queryPayInfoList` 空列表 fallback 规则来自官方脚本。BUCC 本地建单流程有 mock 验证，本轮实网只读准备已成功；未建单，支付跳转仍未验收。先前商城 Network 证据不代表 BUCC 本轮已建单。
- 网费 `/pay/card` 的实际 POST 响应、收银台支付方式查询、支付发起和状态变化仍需 Network 证据。用户的一次充值尝试结果不明确，Agent 未提交订单且不应重试。扫码页、二维码图片或 mock 测试不能证明支付已完成。

## 来源文件

- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/finance/CampusFinanceScreen.kt`
- `composeApp/src/commonMain/kotlin/app/bucc/ui/screens/finance/OfficialPaymentBrowser.kt`
- `composeApp/src/jvmMain/kotlin/app/bucc/ui/screens/finance/PaymentBrowserRuntime.jvm.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/finance/CampusRecharge.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/finance/OfficialCampusRechargeBackend.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/finance/CampusFinanceApi.kt`
- `shared/src/commonMain/kotlin/app/bucc/api/local/LocalCampusFinanceApi.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/finance/CampusRechargeTest.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/finance/OfficialCampusRechargeBackendTest.kt`
- `shared/src/commonTest/kotlin/app/bucc/api/finance/LocalCampusFinanceApiTest.kt`

资料整理：2026-10-15。网费原生登录及数据展示已由用户确认；订单与支付结果不明确。未保存真实余额、凭据、Cookie、验证码、订单标识或二维码。

历史探测对 PatPat 登录端点发送过一次不含凭据的空 JSON 请求，返回 HTTP 400，响应顶层字段为 `status`、`message`；另一次网络探测超时。两者均不构成账号登录验证，也不作为当前入口暂停依据。

## 2026-10-15 应用内只读验收补充

修正官方 `/card#…` 回跳路径、同 route 菜单重复及官方空支付方式列表默认规则后，iOS BUCC 已用自己的主登录会话进入校园卡金额输入准备界面，无认证接续或准备错误。未提交金额、未创建订单；官方支付页跳转、二维码与订单状态仍未验证。网费仍按每次进入要求本人完成验证码，本轮停在该验证页等待本人操作。

## 后续复用测试状态

本轮优先找回旧订单作只读核对，两笔已关闭，未重新建单。官方统一支付登录组件新增确认的入口为 `GET https://pass.cc-pay.cn/login`，参数名 `backUrl`、`channel`（官方渠道 `BUAASSO`）；CAS `service` 为 `https://pass.cc-pay.cn/login`。客户端接续和 Cookie 导入已修复并通过 mock，网费也复用该流程。当前累计默认测试 553 项通过、12 项跳过，无失败；没有以此替代真实支付结果。

BUCC 主会话已过期，设备工具密码输入未可靠生效，已停止自动输入，模拟器停在本人主登录页。真实客户端接续、旧纸飞机记录的剩余查看，以及必要的新未支付订单验证尚未完成。测试已获用户授权；当前依赖主会话恢复，不是等待重复授权。


### 2026-10-15 后续实网核对

旧测试订单已关闭后，仅执行用户已授权的一笔未支付校园卡测试订单。BUCC 独立 JVM 客户端正常主登录、校园卡充值准备、建单、统一支付 CAS 接续、收银台响应和支付身份查询均通过。未扫码、未付款、未重试。iOS 内置浏览器、支付渠道/二维码及订单状态仍须分别验收；本轮未新增网费订单。原生模拟器主登录尚等待手动完成，不能把独立客户端结果称为 iOS 完整流程通过。
