# 纸飞机查看记录契约与验证状态

本文记录查看记录相关的脱敏接口契约和证据等级，不包含个人身份值、认证材料、积分或次数。

## 官方字段来源

公开前端脚本显示：个人信息查询返回的 `yhdm` 用于纸飞机权限查询和查看记录；用户类型查询返回 `yhlx`，学生分支再根据当前学期偏好/角色结果确定学生身份，并将 `jsdm` 设置为学生值 `1`。查看页请求体中的 `jllx` 来自 `jsdm`。

HTTP 请求头 `RoleCode` 是另一条来源：首页通过纸飞机权限查询取得响应 `rolecode` 并存入 HTTP 请求角色。它与请求体 `jllx` 含义不同。单次会话中二者匹配，不代表所有会话都相同。

## 接口契约

| 操作 | URL / 方法 / 内容类型 | 参数与响应 | 认证和前置条件 | 证据 |
|---|---|---|---|---|
| 个人信息映射 | `https://spoc.buaa.edu.cn/spocnewht/grzx/queryByInfo` · `POST` · JSON | 空对象；响应的 `yhdm` 供后续请求使用 | BUCC 自有 SPOC CAS 登录 | 客户端实现及本轮真实只读调用 |
| 用户类型 | `https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne` · `POST` · 加密 JSON 参数 | `sqlid=17126473942303c5f6d76786468fdeb4af2c1192a3bd`；响应字段 `yhlx` | SPOC Token 与 HTTP `RoleCode` | 官方脚本；本轮真实只读对照 |
| 纸飞机入口权限 | `https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne` · `POST` · 加密 JSON 参数 | `sqlid=17413248662121bd8b41e169a9b88945d9ecd9a763c18ff`、`yhdm`；响应字段 `rolecode`、`sfcz` | SPOC Token 与 HTTP `RoleCode` | 官方脚本；本轮真实只读对照确认入口允许，且权限 `rolecode` 与当前 HTTP `RoleCode` 匹配 |
| 查看状态 | `https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne` · `POST` · 加密 JSON 参数 | `sqlid=1735118896932fd7e6c7cf6ca8bb78adf3341b1634e5a4322`、`zfjid`、`jllx`、`yhdm`；响应字段 `sfyjl` | SPOC Token 与 HTTP `RoleCode`；`jllx` 使用已校验的学生 `jsdm=1` | 官方脚本；本轮真实只读对照 |
| 新增查看记录 | `https://spoc.buaa.edu.cn/spocnewht/inco/ht/add` · `POST` · 加密 JSON 参数 | `sqlid=1735117159271ee372cf4898785aa6f4a519394f76b119c4`、`zfjid`、`jllx`、`yhdm` | 仅在查询明确表示尚未查看时调用；SPOC Token 与 HTTP `RoleCode` | 官方脚本；本轮有限真实操作复用已有纸飞机后，经官方只读任务查询确认完成 |

加密请求体通过外层 JSON 参数发送；SPOC 通用 HTTP 层使用认证 `Token` 与 `RoleCode` 请求头。动态身份和 HTTP 角色从当前登录及官方前置查询取得，不得硬编码。

## BUCC 实现与验证

- `LocalSpocClient` 现在在查看前读取个人信息和用户类型，确认当前学生映射后使用学生 `jllx=1`。身份与角色信息在 Token 生命周期内缓存；HTTP `RoleCode` 沿用认证会话中的值，不被请求体角色替换。
- Mock 覆盖了 body `jllx` 与 HTTP `RoleCode` 分离，以及身份未知时拒绝写入。
- 本轮 BUCC 自有 JVM CAS 登录成功。真实只读权限查询确认入口允许，且当前会话权限 `rolecode` 与 HTTP `RoleCode` 匹配；该结果仅适用于本次会话。
- 有限真实查看测试复用了既有纸飞机，成功补齐查看任务；没有新增投掷。最终通过官方只读任务查询确认查看已完成。早先的失败尝试属于本轮历史，不代表写操作未执行；以最终只读查询结果为准。
- 这项 JVM 客户端验证不等于 iOS 界面完整验收。此次没有验证 iOS 页面交互及不同会话中的 HTTP 角色对应关系。

## 其他并行读取行为

课表周请求现在按组最多并发四个；所有日期和课程仍保留。请求失败时不猜测或替代课程数据。

**结论：** 官方脚本字段、当前会话只读权限和本次查看任务完成状态均已确认；Mock 覆盖角色分离与身份未知保护。iOS 完整界面以及其他会话的动态权限角色关系仍未验证。
