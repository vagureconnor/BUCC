package app.bucc.api.auth

import app.bucc.model.dto.CaptchaInfo
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

/**
 * 客户端异常：需要输入验证码方可继续。
 *
 * @property captcha 验证码图片及标识信息。
 * @property execution SSO 执行标识。
 * @property message 提示消息。
 */
class CaptchaRequiredClientException(
    val captcha: CaptchaInfo,
    val execution: String,
    message: String,
) : Exception(message)

class ApiCallException(
    message: String,
    val status: HttpStatusCode? = null,
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Only approved categories cross the authentication UI boundary; upstream text may contain secrets.
 */
fun authenticationFailureMessage(error: Throwable, fallback: String): String =
    when {
      error is ApiCallException && error.code == "auth_upstream_unreachable" ->
          "无法连接统一认证服务，请检查校园 VPN 或切换连接模式后重试"
      error is ApiCallException && error.code == "auth_service_challenge_required" ->
          "网关已连接，统一认证仍需验证；请手动登录"
      error is ApiCallException && error.code == "auth_identity_mismatch" -> "认证返回的账号与当前账号不一致，请手动登录"
      error is ApiCallException &&
          error.code in
              setOf(
                  "invalid_credentials",
                  "auth_upstream_timeout",
                  "unauthenticated",
                  "captcha_not_found",
                  "captcha_error",
              ) -> userFacingMessageForCode(error.code, error.status ?: HttpStatusCode.BadGateway)
      error is HttpRequestTimeoutException ||
          error is ConnectTimeoutException ||
          error is SocketTimeoutException -> "认证服务响应超时，请稍后重试"
      else -> fallback
    }

internal fun userFacingMessageForCode(code: String?, status: HttpStatusCode): String {
  return when (code) {
    "invalid_request" -> "请求参数不正确，请检查后重试"
    "invalid_credentials" -> "账号或密码错误，请重试"
    "invalid_refresh_token",
    "invalid_token",
    "unauthenticated",
    "unauthorized" -> "登录状态已失效，请重新登录"
    "auth_upstream_timeout" -> "认证服务响应超时，请稍后重试"
    "captcha_not_found" -> "验证码已失效，请刷新后重试"
    "captcha_error" -> "验证码处理失败，请稍后重试"
    "missing_client_version" -> "缺少客户端版本信息"
    "unsupported_portal" -> "当前账号类型暂不支持该功能"
    "already_selected" -> "您已报名过该课程，请勿重复报名"
    "course_full" -> "该课程人数已满，请选择其他课程"
    "course_not_selectable" -> "该课程当前不可报名"
    "select_failed" -> "报名失败，请稍后重试"
    "deselect_failed" -> "退选失败，请稍后重试"
    "sign_failed",
    "signin_failed" -> "签到失败，请稍后重试"
    "signin_load_failed" -> "签到信息加载失败，请稍后重试"
    "bykc_error" -> "博雅课程服务暂时不可用，请稍后重试"
    "bykc_timeout" -> "博雅课程服务响应超时，请稍后重试"
    "cgyy_error" -> "研讨室服务暂时不可用，请稍后重试"
    "cgyy_timeout" -> "研讨室服务响应超时，请稍后重试"
    "reservation_invalid",
    "reservation_token_missing" -> "预约信息已失效，请刷新后重试"
    "day_info_failed" -> "获取研讨室可用信息失败，请稍后重试"
    "spoc_auth_failed" -> "SPOC 登录状态异常，请重新登录后重试"
    "spoc_error" -> "SPOC 服务暂时不可用，请稍后重试"
    "judge_auth_failed" -> "希冀登录状态异常，请重新登录后重试"
    "judge_not_found" -> "希冀作业不存在或无权限访问，请刷新后重试"
    "judge_error" -> "希冀服务暂时不可用，请稍后重试"
    "judge_timeout" -> "希冀服务响应超时，请稍后重试"
    "libbook_auth_failed" -> "图书馆登录状态异常，请重新登录后重试"
    "libbook_error" -> "图书馆预约服务暂时不可用，请稍后重试"
    "libbook_timeout" -> "图书馆预约服务响应超时，请稍后重试"
    "libbook_seat_unavailable" -> "该座位已不可预约，请刷新后重试"
    "libbook_not_found" -> "图书馆预约记录不存在或已失效，请刷新后重试"
    "ygdk_error" -> "阳光打卡服务暂时不可用，请稍后重试"
    "ygdk_timeout" -> "阳光打卡服务响应超时，请稍后重试"
    "schedule_error" -> "课表查询失败，请稍后重试"
    "exam_error" -> "考试信息查询失败，请稍后重试"
    "grade_error" -> "成绩查询失败，请稍后重试"
    "user_info_failed" -> "用户信息查询失败，请稍后重试"
    "classroom_query_failed" -> "空闲教室查询失败，请稍后重试"
    "evaluation_error" -> "评教服务暂时不可用，请稍后重试"
    "internal_server_error" -> "服务器开小差了，请稍后再试"
    else -> userFacingMessageForStatus(status)
  }
}

internal fun userFacingMessageForStatus(status: HttpStatusCode): String {
  return when (status) {
    HttpStatusCode.BadRequest -> "请求参数不正确，请检查后重试"
    HttpStatusCode.Unauthorized -> "登录状态已失效，请重新登录"
    HttpStatusCode.Forbidden -> "当前没有权限执行该操作"
    HttpStatusCode.NotFound -> "请求的资源不存在或已失效"
    HttpStatusCode.RequestTimeout,
    HttpStatusCode.GatewayTimeout -> "请求超时，请稍后重试"
    HttpStatusCode.BadGateway,
    HttpStatusCode.ServiceUnavailable -> "服务暂时不可用，请稍后重试"
    HttpStatusCode.InternalServerError -> "服务器开小差了，请稍后再试"
    else -> "请求失败，请稍后重试"
  }
}

@PublishedApi
internal fun Throwable.toUserFacingApiException(
    defaultMessage: String = "网络异常，请检查连接后重试"
): Exception {
  if (this is CancellationException) throw this
  return when (this) {
    is ApiCallException -> this
    is CaptchaRequiredClientException -> this
    is HttpRequestTimeoutException,
    is ConnectTimeoutException,
    is SocketTimeoutException -> ApiCallException("请求超时，请稍后重试")
    else -> ApiCallException(defaultMessage)
  }
}
