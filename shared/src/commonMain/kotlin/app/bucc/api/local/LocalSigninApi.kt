package app.bucc.api.local

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.SIGNIN_LOGIN_REDIRECT_LIMIT
import app.bucc.api.SIGNIN_MY_CENTER_URL
import app.bucc.api.extractSigninLoginNameFromUrl
import app.bucc.api.feature.SigninApiBackend
import app.bucc.api.resolveSigninRedirectUrl
import app.bucc.model.dto.SigninActionResponse
import app.bucc.model.dto.SigninClassDto
import app.bucc.model.dto.SigninStatusResponse
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class LocalSigninApiBackend : SigninApiBackend {
  private val json = Json { ignoreUnknownKeys = true }
  private val sessionMutex = Mutex()
  private val sessionCache = mutableMapOf<String, LocalSigninSession>()
  private var loginLastError: String? = null

  internal suspend fun sessionForAttendance(): LocalSigninSession {
    val auth = LocalAuthSessionStore.get() ?: throw localUnauthenticatedApiException()
    return currentSession(auth.studentId()) ?: error("iClass 登录失败，请重新登录后查询出勤")
  }

  internal fun clearCache() {
    sessionCache.clear()
  }

  /** 兼容 iclass 返回的 STATUS/stuSignStatus 等字段可能为字符串或整数的情况。 */
  private fun jsonStringValue(element: JsonElement?, key: String): String? {
    val obj = element?.jsonObject ?: return null
    val prim = obj[key]?.jsonPrimitive ?: return null
    prim.contentOrNull?.let {
      return it
    }
    prim.intOrNull?.let {
      return it.toString()
    }
    prim.longOrNull?.let {
      return it.toString()
    }
    return null
  }

  private fun isStatusSuccess(jsonResponse: JsonObject): Boolean {
    val status = jsonStringValue(jsonResponse, "STATUS")
    return status == "0" || status == "200" || status == "success"
  }

  private fun isSigninSessionExpired(payload: JsonObject): Boolean {
    val message = jsonStringValue(payload, "ERRMSG").orEmpty().lowercase()
    return listOf(
            "登录失效",
            "登录已过期",
            "请重新登录",
            "请先登录",
            "未登录",
            "session过期",
            "session失效",
            "会话过期",
            "会话失效",
        )
        .any { it in message }
  }

  override suspend fun getTodayClasses(): Result<SigninStatusResponse> {
    return getTodayClasses(allowRetry = true)
  }

  private suspend fun getTodayClasses(allowRetry: Boolean): Result<SigninStatusResponse> {
    val authSession =
        LocalAuthSessionStore.get() ?: return Result.failure(localUnauthenticatedApiException())
    return try {
      val signinSession =
          currentSession(authSession.studentId())
              ?: throw IllegalStateException(loginLastError ?: "iClass 登录失败，请重新登录后查询")
      loginLastError = null
      val response =
          LocalUpstreamClientProvider.shared().get(
              localUpstreamUrl(
                  "https://iclass.buaa.edu.cn:8347/app/course/get_stu_course_sched.action"
              )
          ) {
            header("sessionId", signinSession.sessionId)
            parameter("id", signinSession.userId)
            parameter("dateStr", currentSigninDate())
          }
      if (response.status != HttpStatusCode.OK) {
        throw IllegalStateException("获取今日签到课程失败（HTTP ${response.status.value}）")
      }
      val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
      if (payload.containsKey("STATUS") && !isStatusSuccess(payload)) {
        val message = jsonStringValue(payload, "ERRMSG")?.takeIf { it.isNotBlank() }
        if (allowRetry && isSigninSessionExpired(payload)) {
          invalidateSession(authSession.studentId())
          if (currentSession(authSession.studentId()) != null) {
            return getTodayClasses(allowRetry = false)
          }
        }
        throw IllegalStateException(message ?: "获取今日签到课程失败")
      }
      val classes =
          payload["result"]?.jsonArray?.map(::mapSigninClass)
              ?: throw IllegalStateException("签到课程响应格式无效")
      Result.success(SigninStatusResponse(code = 200, message = "获取成功", data = classes))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Result.failure(error)
    }
  }

  override suspend fun performSignin(courseId: String): Result<SigninActionResponse> {
    return performSignin(courseId, allowRetry = true)
  }

  private suspend fun performSignin(
      courseId: String,
      allowRetry: Boolean,
  ): Result<SigninActionResponse> {
    val authSession =
        LocalAuthSessionStore.get() ?: return Result.failure(localUnauthenticatedApiException())
    val signinSession =
        try {
          currentSession(authSession.studentId())
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          null
        }
    if (signinSession == null) {
      val error = loginLastError ?: "iclass 登录失败"
      loginLastError = null
      return Result.success(SigninActionResponse(code = 400, success = false, message = error))
    }

    return try {
      val timestamp =
          LocalUpstreamClientProvider.shared()
              .get(localSigninCheckinUrl("app/common/get_timestamp.action"))
              .bodyAsText()
              .let(json::parseToJsonElement)
              .jsonObject["timestamp"]
              ?.jsonPrimitive
              ?.content
      if (timestamp.isNullOrBlank()) {
        return Result.success(
            SigninActionResponse(code = 400, success = false, message = "获取服务器时间失败")
        )
      }

      val response =
          LocalUpstreamClientProvider.shared().submitForm(
              url =
                  localSigninCheckinUrl(
                      directPath = "eschool/app/course/stu_scan_sign.action",
                      webVpnPath = "app/course/stu_scan_sign.action",
                  ),
              formParameters = Parameters.build { append("id", signinSession.userId) },
          ) {
            header("sessionId", signinSession.sessionId)
            parameter("courseSchedId", courseId)
            parameter("timestamp", timestamp)
          }
      val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
      val success =
          isStatusSuccess(payload) &&
              jsonStringValue(payload["result"]?.jsonObject, "stuSignStatus") == "1"
      val rawMessage = jsonStringValue(payload, "ERRMSG")
      if (!success) {
        if (allowRetry && rawMessage?.contains("登录") == true) {
          invalidateSession(authSession.studentId())
          val refreshed = runCatching { currentSession(authSession.studentId()) }.getOrNull()
          if (refreshed != null) return performSignin(courseId, allowRetry = false)
        }
      }
      Result.success(
          SigninActionResponse(
              code = if (success) 200 else 400,
              success = success,
              message = sanitizeLocalSigninMessage(success, rawMessage),
          )
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      Result.success(SigninActionResponse(code = 400, success = false, message = "签到失败，请稍后重试"))
    }
  }

  private suspend fun currentSession(studentId: String): LocalSigninSession? {
    val cacheKey = "${ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT}:$studentId"
    sessionMutex
        .withLock { sessionCache[cacheKey] }
        ?.let {
          return it
        }
    val created = login(studentId) ?: return null
    return sessionMutex.withLock { sessionCache.getOrPut(cacheKey) { created } }
  }

  private fun invalidateSession(studentId: String) {
    val cacheKey = "${ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT}:$studentId"
    sessionCache.remove(cacheKey)
  }

  private suspend fun login(studentId: String): LocalSigninSession? {
    val loginName = resolveLoginName() ?: return null
    val response =
        LocalUpstreamClientProvider.shared().get(
            localUpstreamUrl("https://iclass.buaa.edu.cn:8347/app/user/login.action")
        ) {
          parameter("password", "")
          parameter("phone", loginName)
          parameter("userLevel", "1")
          parameter("verificationType", "2")
          parameter("verificationUrl", "")
        }
    if (response.status != HttpStatusCode.OK) {
      return null
    }

    val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
    if (!isStatusSuccess(payload)) {
      loginLastError = jsonStringValue(payload, "ERRMSG")?.takeIf { it.isNotBlank() } ?: "登录失败"
      return null
    }

    val result = payload["result"]?.jsonObject ?: return null
    val userId = result["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
    val sessionId =
        result["sessionId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
    return LocalSigninSession(userId = userId, sessionId = sessionId)
  }

  private suspend fun resolveLoginName(): String? {
    val client = LocalUpstreamClientProvider.newNoRedirectClient()
    return try {
      // 参考 BUCCNext：保持原始 URL 不变，仅在发请求时做 VPN 包装
      var rawUrl = SIGNIN_MY_CENTER_URL
      extractSigninLoginNameFromUrl(rawUrl)?.let {
        return it
      }
      repeat(SIGNIN_LOGIN_REDIRECT_LIMIT) {
        val response = client.get(localUpstreamUrl(rawUrl))
        val finalUrl = LocalWebVpnSupport.fromWebVpnUrl(response.call.request.url.toString())
        extractSigninLoginNameFromUrl(finalUrl)?.let {
          return it
        }

        val location =
            response.headers[HttpHeaders.Location]?.let(LocalWebVpnSupport::fromWebVpnUrl)
        location?.let {
          extractSigninLoginNameFromUrl(it)?.let { loginName ->
            return loginName
          }
        }
        if (response.status.value !in 300..399 || location.isNullOrBlank()) {
          return null
        }
        rawUrl = resolveSigninRedirectUrl(finalUrl, location) ?: return null
        extractSigninLoginNameFromUrl(rawUrl)?.let { loginName ->
          return loginName
        }
      }
      null
    } finally {
      client.close()
    }
  }
}

internal data class LocalSigninSession(
    val userId: String,
    val sessionId: String,
)

// 8347 的签到提交入口没有 /eschool 前缀，端口和路径需要一起选择。
private fun localSigninCheckinUrl(directPath: String, webVpnPath: String = directPath): String {
  val upstreamUrl =
      if (localBusinessUsesWebVpnGateway()) "https://iclass.buaa.edu.cn:8347/$webVpnPath"
      else "http://iclass.buaa.edu.cn:8081/$directPath"
  return localUpstreamUrl(upstreamUrl)
}

private fun mapSigninClass(element: JsonElement): SigninClassDto {
  val payload = element.jsonObject
  return SigninClassDto(
      courseId = payload["id"]?.jsonPrimitive?.content ?: "",
      courseName = payload["courseName"]?.jsonPrimitive?.content ?: "",
      classBeginTime = payload["classBeginTime"]?.jsonPrimitive?.content ?: "",
      classEndTime = payload["classEndTime"]?.jsonPrimitive?.content ?: "",
      signStatus = flexibleIntValue(payload, "signStatus"),
  )
}

/** 兼容字段可能为字符串或整数的情况，统一返回 Int。 */
private fun flexibleIntValue(obj: JsonObject, key: String): Int {
  val prim = obj[key]?.jsonPrimitive ?: return 0
  prim.intOrNull?.let {
    return it
  }
  prim.contentOrNull?.toIntOrNull()?.let {
    return it
  }
  return 0
}

private fun LocalAuthSession.studentId(): String = user.schoolid.ifBlank { username }

private fun currentSigninDate(): String {
  val date = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date
  return buildString {
    append(date.year)
    append(date.month.ordinal.plus(1).toString().padStart(2, '0'))
    append(date.day.toString().padStart(2, '0'))
  }
}

internal fun sanitizeLocalSigninMessage(success: Boolean, rawMessage: String?): String {
  if (success) return rawMessage?.takeIf { it.isNotBlank() } ?: "签到成功"
  val message = rawMessage.orEmpty()
  return when {
    "已签到" in message -> "您今天已经签到过了"
    "未开始" in message -> "当前还未到签到时间"
    "不是上课时间" in message -> "当前不是上课时间，无法签到"
    "已结束" in message -> "本次签到已结束"
    "范围" in message -> "当前不在可签到范围内"
    "用户不存在" in message -> "签到账号不存在，请联系管理员"
    "课程" in message && "不存在" in message -> "未找到对应课程，请刷新后重试"
    else -> "签到失败，请稍后重试"
  }
}
