# 纸飞机接口证据摘录（2026-10-14，状态更新至 2026-10-15）

本文依据智学北航 bhspoc 官方公开 UniApp 静态脚本、一次授权 app 会话下的只读查询和 BUCC 本地代码整理。只读查询没有触发课堂感受、投掷或查看标记写入；个人课程、教师、积分和次数值不保存。静态脚本展示前端调用方式，不代表写流程成功。

## 官方入口与脚本映射

官方公开入口为 [`https://spoc.buaa.edu.cn/bhspoc/#/pages/plane/student/index`](https://spoc.buaa.edu.cn/bhspoc/#/pages/plane/student/index)。只读 GET 返回 200，页面 HTML 引用 [`index.c8809ad6.js`](https://spoc.buaa.edu.cn/bhspoc/static/js/index.c8809ad6.js) 与 `chunk-vendors.255b2573.js`。入口 bundle 的异步 chunk 映射包含 `pages-plane-student-sendPlane` → `47092d7d`、`pages-plane-student-checkPlane` → `c227379e`、`pages-plane-student-index` → `ad82a2f3` 和 `pages-plane-student-integral-myIntegral` → `927274fa`。相应公开脚本均可读取：

- [`pages-plane-student-index.ad82a2f3.js`](https://spoc.buaa.edu.cn/bhspoc/static/js/pages-plane-student-index.ad82a2f3.js)
- [`pages-plane-student-sendPlane.47092d7d.js`](https://spoc.buaa.edu.cn/bhspoc/static/js/pages-plane-student-sendPlane.47092d7d.js)
- [`pages-plane-student-checkPlane.c227379e.js`](https://spoc.buaa.edu.cn/bhspoc/static/js/pages-plane-student-checkPlane.c227379e.js)
- [`pages-plane-student-integral-myIntegral.927274fa.js`](https://spoc.buaa.edu.cn/bhspoc/static/js/pages-plane-student-integral-myIntegral.927274fa.js)

## 投掷规则查询及响应处理

`pages-plane-student-index.ad82a2f3.js` 中的 `getSendPlaneConfig()` 调用 `queryOne({sqlid:"173491496021491356781b468c27af6db1cdddc6ab39c86d"})`，成功后将 Promise 返回值原样传给 `uni.setStorageSync("sendPlaneConfig", e)`。投掷页和查看页随后直接以 `a.yxskfskg`、`a.fzsrk`、`a.tssrk` 读取该对象字段。公开页面代码在此路径没有将配置结果改取 `e[0]`；数组首项处理未见于这段业务调用。

此结论限于页面级 JavaScript：它证明页面将 queryOne 的已解析结果按对象字段读取，并未证明服务端实际返回对象还是数组，也未验证 `httpRequest` 对 HTTP 响应封装的内部解包规则。课堂时间限制逻辑按课程开始/结束时间及上述配置值计算可投掷区间。

BUCC 本地 `queryOne` 调用同一 SQL ID，并将响应按 `PaperPlaneSendConfig` 对象解码；注释记录遇到数组响应时可解析失败，失败不会阻断课程读取。见 `shared/src/commonMain/kotlin/app/bucc/api/local/LocalSpocApi.kt#L441` 与 `shared/src/commonMain/kotlin/app/bucc/api/plane/PaperPlaneApi.kt#L199`。本地 mock 的数组用例验证的是 BUCC 对异常响应的降级行为，不是官方真实响应证据，见 `shared/src/commonTest/kotlin/app/bucc/api/LocalSpocApiBackendTest.kt#L213`。

## 课堂时间字段

官方 checkPlane 与学生 index 脚本直接对课程的 `skkssj`、`skjssj` 使用 `new Date(...)` 计算时间；index 的 `getFtime` 仅在输入含空格时返回空格后的部分。课堂感受提交代码将 `this.obj.skrq` 原样放入写请求字段 `skrq`。这些代码没有建立可确认的日期格式或时区契约，因此格式、时区及服务端字段来源仍未验证。

BUCC 将这三个字段表示为可空字符串；本地测试 fixture 使用 `yyyy-MM-dd HH:mm:ss` 作为 `skkssj`/`skjssj` 示例，并用 `yyyy-MM-dd` 作为 `skrq` 示例。这些只是 mock 样例，不能据此认定官方响应格式。参见 `shared/src/commonMain/kotlin/app/bucc/api/plane/PaperPlaneApi.kt#L182`、`shared/src/commonTest/kotlin/app/bucc/api/LocalSpocApiBackendTest.kt#L225` 及`shared/src/commonTest/kotlin/app/bucc/api/LocalSpocApiBackendTest.kt#L641`。

## 教师选择字段

`sendPlane.47092d7d.js` 的 `querySelectJsZjList()` 以 `hdktid` 调用授课教师 `queryList`，以 `kcid`、`zjyhdm` 调用助教 `queryList`，两者都将已解析结果直接赋给 `jsList` 或 `zjList`。教师选择组件用 `jsxm` 显示姓名、用 `zgh` 作为教师编号并映射为本地选择字段 `dm`；教师 `type` 是前端固定值字符串 `"1"`。助教对应使用 `zjxm`、`zjdm` 和固定值 `"2"`。组件代码的 `e.value[0]` 是选择器返回的第一个已选项，不是对 queryList 响应做 `[0]` 解包；公开页面未在这段加载路径对教师列表响应取首项。

本地模型将 `zgh` 映射到 `dm`、将 `jsxm` 映射到 `name`，且缺少 `type` 时默认 `"1"`，见 `shared/src/commonMain/kotlin/app/bucc/api/plane/PaperPlaneApi.kt#L206`。这些映射与静态前端相符，但本轮未请求教师/助教列表，未验证真实响应结构及 `type` 服务端取值。

## 授权会话下的只读任务查询

此前通过用户授权的 BUCC app 会话只读查询 CAS RoleCode 与纸飞机任务接口，两个查询均有成功响应。任务记录包含 `gzmc`、`num` 字段；其中标签字面值为 `投掷“课堂纸飞机”`、`浏览“课堂纸飞机”`，中文引号是服务端 `gzmc` 值的一部分。此前精确匹配不带引号的文本会漏项；当前规范化只剥除中文引号。去除引号后若仍有重复匹配项，继续报错，不猜测任务。个人积分或 `num` 实际值不记录。

后续用户反馈其自行投掷一批成功，但查看失败且耗时较长。这是用户操作反馈，不是 Agent 实网写验收；不记录本次数或积分值。Agent 未发送投掷、课堂感受或浏览标记写请求。

本地回归曾以 `sfyjl` 数字解析异常及“成功但 nullable 回执带空格却被判失败”两种输入复现红测，修复后的对应测试通过。减少重复 prepare 与积分轮询现已实现并经模拟验证；`PaperPlaneApiTest` 26 项通过，包含首 20 条已查看后翻到第二页的场景。这些测试不验证官方实网写操作。

## 可确认边界

- 官方页面 chunk 与静态代码确认了 send-config 的 SQL ID、页面读取配置字段的方式、教师/助教查询输入名和页面选择字段映射。
- send-config 的页面级读取方式不揭示服务端原始返回对象还是数组；课程日期的真实编码及时区也仍缺少实网字段证据。
- 本地源码和 mock 仅说明 BUCC 自身数据模型、解析和失败策略，不能代替官方接口响应验证。
- 只读任务查询成功不等于投掷、课堂感受或查看标记写请求成功；用户对投掷/查看的反馈与 Agent 的本地回归结果应分别理解。
- 针对性本地解析回归及 `PaperPlaneApiTest` 26 项已通过；减少重复 prepare 与积分轮询已实现并经模拟验证，仍无 Agent 实网写验收。
- 本文不保存个人课程、教师、账号、积分或次数值。
