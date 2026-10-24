# 签到查询：接口与验证边界

本模块查询学生历史出勤，独立于现有“课堂签到”写操作；不会执行扫码签到或自动签到。

## 官方接口

以官方 iClass 页面 `https://iclass.buaa.edu.cn:8346/?type=jumpMyCenter#/MyCenter` 的学生出勤页面为依据。请求使用既有 iClass 登录会话的 `sessionId` 请求头，WebVPN 模式复用现有网关路由。前端 bundle 中未被使用的 `VUE_APP_BASE_URL` 常量不是请求目标。

| 方法 | 路径 | 参数 | 返回用途 |
| --- | --- | --- | --- |
| GET | `/eschool/app/course/get_base_school_year.action` | `userId`, `type=2` | 学期 `code/name/beginDate/endDate` |
| GET | `/app/choosecourse/get_myall_course.action` | `user_type=1`, `id`, `xq_code` | 学期内课程 `course_id/course_name` |
| POST | `/eschool/app/my/get_my_course_sign_detail.action` | 查询参数 `id`, `courseId`，无业务写入载荷 | 单课程全部出勤历史 |

学生历史接口的官方调用没有分页；按课程读取并最多并发三个请求。历史字段包括 `teachTime`、`classBeginTime`、`classEndTime`、`signStatus`。状态 `0` 为缺勤，`1` 为出勤，未核实的其他值保留为未知状态，不推断出勤。记录只按课程 ID 与记录 ID（缺少时使用日期和时段）去重。

官网对非零状态显示暂无数据；本实现仅将实际核实的历史空响应 `{"STATUS":"2"}` 视为空记录，其他异常保持明确失败，避免伪造“无缺勤”。课程仍保留在课程视图中。学期边界可解析时过滤范围外记录。切换账号或连接模式后拒绝旧响应；切换学期用取消与请求代次避免旧请求覆盖。

## 本轮只读验证

授权密码通过隐藏输入仅供测试进程使用，测试使用隔离的内存设置，不写个人资料、凭据、Cookie 或出勤载荷到源码、报告或公开目录。已通过 WebVPN 验证统一认证、学期列表、整学期课程历史和课程引用完整性；另已验证 Judge 作业只读获取成功。未执行真实签到、提交作业或其他业务写操作。

UI 证据与回归采用虚构课程和记录。日期/课程两视图与课程筛选使用同一组查询结果。真实 iOS 设备安装、校内直连网络与账号全部历史学期尚须用户复验。
