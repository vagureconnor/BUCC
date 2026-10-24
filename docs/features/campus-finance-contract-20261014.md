# 校园财务接口契约摘录（2026-10-14，状态更新至 2026-10-15）

本文整理公开脚本线索和现有记录中的字段名、端点与会话依赖，不含个人值。证据来源分为：公开页面只读访问、本轮从已登录 Chrome 官方页面获取的静态脚本、本轮只读 SSO 回调、历史官方页面 Network 记录、ZFW 官方脚本摘录，以及下文单独标注的 BUCC 运行状态。它们分别记录，不互相升级为 BUCC 端到端建单证据。

## 校园卡：初始化与商城跳转

官方入口为 [`https://m.icw.buaa.edu.cn/card/`](https://m.icw.buaa.edu.cn/card/)。无会话直连入口曾重定向到 `/card/errorPage?`；先前已从官方 Chrome 页面只读取得下列脚本。本轮 Agent 在 iOS 模拟器完成 BUCC 主登录后，用应用自己的 CAS Cookie 完成官方 SSO 回跳和充值准备阶段的只读查询。动态身份值不记录；未调用充值/商城建单写接口，未进入支付页面。

本轮读取到的官方脚本：[`index.e190d751.js`](https://m.icw.buaa.edu.cn/card/static/js/index.e190d751.js)、[`pages-bind-login-login.57e135c5.js`](https://m.icw.buaa.edu.cn/card/static/js/pages-bind-login-login.57e135c5.js)、[`pages-homepage-index-index.2315424e.js`](https://m.icw.buaa.edu.cn/card/static/js/pages-homepage-index-index.2315424e.js)、[`pages_other-recharge-cardrecharge-cardrecharge.0cc5cd1e.js`](https://m.icw.buaa.edu.cn/card/static/js/pages_other-recharge-cardrecharge-cardrecharge.0cc5cd1e.js)。

| 流程 | URL / 方法 | 参数名与字段名 | 会话来源 / 当前证据边界 |
| --- | --- | --- | --- |
| 官方 SSO 返回入口 | `/login?service=<编码后的 service>&noAutoRedirect=1`，页面导航 | 登录脚本 `goTarget` 的 service 形态为 `/card/home/sisUstbMiniReturnPage/{initial-openid-or-public-placeholder}/{usertype}/auth`；公开无 openid 时使用 fallback `112233`，普通浏览器 `usertype=1` | Chrome 只读回调曾进入主页。本轮 BUCC iOS 自有 CAS 会话回跳路由为 `/card#/pages/homepage/index/index?`（无尾斜杠），参数名为 `openid`、`usertype`；不记录动态值。 |
| 首页与菜单发现 | `GET /card/home/openHomePageApp` | `openid`、`usertype`、`ua`、`orgid`；响应 `data.funcList[]` 含 `linkurl`、`id`、`functionname`、`description` | 本轮 BUCC 自有会话只读查询成功。发现两个充值项共享同一 `linkurl`：`充值`（`description=首页`）和 `校园卡充值`（`description=卡务中心`）；不能只靠 route 假定唯一。代码优先选择“校园卡充值”，剩余歧义则停止。 |
| 支付方式查询 | `GET /card/queryPayInfoList` | `menuid`、`openid`、`orgid`；菜单按名称/说明识别；响应 `data.data[]` 读取 `payway` | 本轮只读响应 `success=true`、`data.data=[]`，且没有“不在缴费期间”提示。官方充值脚本 `0cc5cd1e.js` 的 `getPayInfoList` 规定：空列表且 message 不含该提示时使用默认方式列表，含 `payway=10` 航财通和 `payway=6` 银校转账。BUCC 当前目标仅选航财通。 |
| `openCardPay` 初始化 | `POST /card/cardpay/openCardPay` JSON | `{openid}`；通用 wrapper 将业务 JSON 加密包装到 `datajson`；读取响应 `data.cardPay`、`data.disableidserialstart` 和可选 `data.appid` | 本轮 BUCC 自有 CAS 会话只读初始化成功；未传金额，未发充值或商城建单请求。 |
| 航财通充值 | `POST /card/recharge/rechargeByHct` JSON | `idserial`、`txamt`、`payway`、`openid`、`payWay=10`、`tradetype=WAP`、`usertype`、`orgid`；`txamt` 先从元换算为分，再由 wrapper 加密为 `datajson` | 充值脚本读取 `data.success`，成功后跳转 `data.resultData.mweb_url`。仅静态确认字段/响应路径，本轮未提交充值请求、未建单。 |
| 官方商城建单 | `https://mall.cc-pay.cn/api/payment?t=<动态 t>`，`POST` JSON | `targetId`、`targetType`、`money`、`itemId`、`choice`、`feeInfo`、`fromType` | 历史未支付测试订单的官方页面 Network 记录显示请求依赖 `mall.cc-pay.cn` 会话 Cookie（含 `connect.sid`）。BUCC 如何合法建立或交换这段商城会话，现有材料没有证据。 |
| 官方商城返回与跳转 | 上述 `/api/payment` 响应，随后 Document 导航到 `cashier.cc-pay.cn` | 响应顶层 `success`、`type`、`message`、`data`；`data` 中有 `cashierUrl` | 历史 Network 记录确认未支付测试单的响应和导航字段；动态 `t`、Cookie、订单标识及 URL 参数值均不记录。本轮未调用建单接口。 |

校园卡官方脚本、Chrome SSO 与 BUCC 自有会话的只读证据分开记录。本轮已由 iOS 模拟器完成 BUCC 主登录，之后使用应用自己的 CAS Cookie 成功只读查询 SSO 回跳、首页菜单、支付方式和初始化。它只证明充值准备读取阶段成功；未建单或验证支付跳转。历史 `mall.cc-pay.cn/api/payment` Network 记录属于另一阶段，不能证明本地流程已建单。历史商城记录见[财务文档](finance.md)。

## ZFW：登录与验证码字段

以下是官方 ZFW 根页及其脚本的静态脚本摘录。它们描述页面请求构造；账号认证状态另见后文，不在这里记录账号或个人字段值。

| 阶段 | URL / 方法 | 参数名与字段名 | 证据与限制 |
| --- | --- | --- | --- |
| 加载登录页 | `https://zfw.buaa.edu.cn/`，`GET` | 页面脚本读取 `#public` 的公钥内容 | 脚本记录显示使用 JSEncrypt，并通过 `setPublicKey($('#public').val())` 设置 RSA 公钥。 |
| 验证用户 | `https://zfw.buaa.edu.cn/site/validate-user`，`POST` | `LoginForm[username]`、`LoginForm[password]`、`LoginForm[verifyCode]` | 脚本先用 RSA PKCS#1 加密密码，再提交这些表单字段。这里仅摘录字段和加密步骤，不含凭据值，也未提交请求。 |
| 登录分支 | `https://zfw.buaa.edu.cn/`，`POST`；或 `https://zfw.buaa.edu.cn/site/validate-smscode`，`POST` | 页面按验证响应中的 `success` 与 `inputSms` 分支；短信验证请求使用 `uname`、`code` | 这是脚本控制流记录，不代表任一分支在当前账号或会话中成功。 |
| 刷新验证码元数据 | `https://zfw.buaa.edu.cn/site/captcha?refresh=1`，`GET` | JSON 字段 `url`、`hash1`、`hash2` | 官方脚本只使用 `url` 获取图片。`hash1`、`hash2` 不能替代用户验证码，也不能绕过验证码流程。 |

ZFW 端点、参数与验证码限制依据是官方根页脚本的静态读取记录。认证或用户数据值不写入本文。校园卡与 ZFW 两组会话彼此独立，不能据此推断校园卡商城会话能够由 ZFW Cookie 派生。

## BUCC 本地流程与实网边界

`OfficialCampusCardRechargeBackend` 使用 BUCC 应用自己的 CAS Cookie 取得官方 SSO 回调后的动态身份；不读取或借用 Chrome Cookie，也不尝试独立 `pass` 登录。随后使用官方 wrapper 加密请求查询首页 `funcList` 并按菜单名识别充值入口，查询支付方式；若官方返回空列表且没有缴费关闭提示，按官方脚本采用默认列表中的 `Hct10` 航财通。它也会识别“不在缴费期间”状态，并只读调用 `openCardPay` 初始化。只有用户提交金额后才调用一次加密 `rechargeByHct`，金额先换算为分；响应的 `resultData.mweb_url` 决定后续官方页面路由。iOS 应用内只读准备界面已通过，但该支付跳转未执行，不能称成功。

本地合成 mock 已覆盖 CAS Cookie/SSO 身份、加密、字段与金额、单次提交及 CAS 失效场景并通过。本轮 BUCC 自有会话另已完成只读准备核对；iOS 应用内只读准备界面已通过，订单及支付页验收尚未进行。没有 Agent 校园卡订单写请求，也未验收支付页跳转、扫码或付款，不能称建单/支付成功。

## 最新 BUCC 运行状态

用户确认已在 BUCC 原生界面完成网费验证码登录并看到数据；本文仅记录该流程通过，不包含账号或数据值。用户之后自行进行了1次充值尝试，建单结果不明确。Agent 未发送订单写请求；结果不明时不应重试，也不能称建单成功或支付完成。

校园卡本地建单请求流程现已实现，且本轮 BUCC 自有会话只读准备成功；官方实网建单及支付页接续仍未验证。Chrome SSO 和本轮 BUCC 只读核对均没有创建订单；本地 mock 也只证明本地代码路径。旧商城建单 Network 记录不证明 BUCC 当前流程已建单。

## 来源与边界

- 官方校园卡入口：[m.icw.buaa.edu.cn/card/](https://m.icw.buaa.edu.cn/card/)。无会话直连曾重定向；四个官方脚本、Chrome SSO 和本轮 BUCC iOS 自有会话只读准备分别记录在上文。未创建订单，未验收支付跳转。
- 官方 ZFW 登录入口：[zfw.buaa.edu.cn/](https://zfw.buaa.edu.cn/)。登录表单、RSA、短信分支和验证码 JSON 字段依据主 agent 提供的官方脚本摘录。
- 本文只记录路径、方法、参数名和字段名；不保存真实 openid、Cookie、账号、验证码、响应个人字段、订单号、余额或支付数据。

## 2026-10-15 旧订单复用与统一支付 CAS 补证

用户授权继续测试，并要求优先复用已有测试数据。Agent 从已授权浏览器中找回两笔历史收银台入口；通过官网“统一认证登录”恢复浏览器会话后，两笔均显示已关闭。未为此次核对新建订单，也未扫码或付款。关闭状态只能作为旧订单状态证据，不能用于证明当前渠道、二维码或新建单成功。

`https://pass.cc-pay.cn/9.async.js` 中的登录组件构造 `thirdSearchUrl`，将已有 `backUrl` 与 `channel=BUAASSO` 拼接到 `/login` 的链接；“统一认证登录”标签对应该链接。浏览器实际流程为支付登录页 → `sso.buaa.edu.cn/login`（`service=https://pass.cc-pay.cn/login`）→ 支付回调 → 原收银台。脚本与浏览器观察分别构成证据，不包含任何真实回跳值。

客户端现在先访问官方支付登录入口并沿 BUAASSO 跳转复用 BUCC 自有 CAS Cookie；只允许 HTTPS 的官方支付登录路径和上述精确 CAS service，最终仍要求官方收银台或商城入口。既不输入独立支付账号密码，也不把登录页当作支付页。内置浏览器同步 BUCC 自有的商城、收银台及支付认证 Cookie。校园卡和网费共用该接续流程。

模拟测试覆盖首次认证、已有支付会话、CAS 目标校验和 Cookie 来源。原生主会话过期后，独立 JVM 探针使用 BUCC 正常主登录代码重新认证成功，支付 CAS 接续及支付身份查询成功。两笔旧订单均关闭，不能验收当前支付页，因此在用户已明确授权的限定测试范围内创建一笔新的未支付校园卡测试订单：官方充值准备、建单响应、返回官方入口、支付 CAS 回调及收银台 HTTP 200 均通过，随后支付身份查询确认已登录。没有扫码、确认付款或重试建单。探针仅输出状态，不保存订单、二维码、身份值或认证材料。

以上是 BUCC 独立 JVM 客户端实网验证，不是 iOS 内置浏览器完整验收。二维码来源、渠道选择和订单状态查询尚未完成本轮实时核对；HTTP 200 和已登录身份不能替代这些证据。网费此前未知建单结果不重试；本轮没有新增网费订单。
