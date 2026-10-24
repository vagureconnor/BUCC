package app.bucc.td

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.ensureLocalWebVpnSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class DesktopTdService(
    private val httpClient: HttpClient =
        HttpClient(CIO) {
          expectSuccess = false
          install(HttpTimeout) {
            requestTimeoutMillis = 30 * 60 * 1_000L
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30 * 60 * 1_000L
          }
        },
    private val backend: DesktopTdBackend = DesktopTdBackend(),
) : TdService {
  private val mutableState = MutableStateFlow(TdState(available = false))
  override val state: StateFlow<TdState> = mutableState.asStateFlow()

  private val json = Json { ignoreUnknownKeys = true }
  private val executionLock = Any()
  private var executionJob: Job? = null
  private var executionRequestId: String? = null
  @Volatile private var endpoint: String? = null

  override suspend fun load() = runOperation {
    val baseUrl = endpoint()
    updateBackendConnectionMode(baseUrl)
    var root = getState(baseUrl)
    val campusUsername =
        TdCampusCredentials.activeUsername()?.takeIf { it.matches(Regex("[0-9]+")) }
    val configuredStudentId =
        (root.arrayOrNull("users")?.firstOrNull() as? JsonObject)?.string("student_id")
    if (campusUsername != null && configuredStudentId != campusUsername) {
      val campus =
          (root.arrayOrNull("users")?.firstOrNull() as? JsonObject)?.string("campus")
              ?: TdCampus.XUEYUANLU.displayName
      postJson(
          "$baseUrl/api/settings/account",
          buildJsonObject {
            put("student_id", campusUsername)
            put("campus", campus)
            put("healthcloud_password", "")
          },
      )
      root = getState(baseUrl)
    }

    val user = root.arrayOrNull("users")?.firstOrNull() as? JsonObject
    val healthCloudUsername = user?.string("healthcloud_username") ?: user?.string("student_id")
    val sessionCredential =
        TdCampusCredentials.current()?.takeIf { it.username == healthCloudUsername }
    if (
        user != null &&
            sessionCredential != null &&
            ConnectionRuntime.currentMode() != ConnectionMode.WEBVPN
    ) {
      postJson(
          "$baseUrl/api/settings/healthcloud",
          buildJsonObject {
            put("student_id", user.string("student_id").orEmpty())
            put(
                "username",
                if (healthCloudUsername == user.string("student_id")) ""
                else healthCloudUsername.orEmpty(),
            )
            put("password", sessionCredential.password)
          },
      )
      root = getState(baseUrl)
    }
    applyBackendState(root)
  }

  override suspend fun saveAccount(account: TdAccountDraft) = runOperation {
    require(account.studentId.matches(Regex("[0-9]+"))) { "学号必须为数字" }
    val baseUrl = endpoint()
    postJson(
        "$baseUrl/api/settings/account",
        buildJsonObject {
          put("student_id", account.studentId)
          put("campus", account.campus.displayName)
          put("healthcloud_password", account.healthCloudPassword)
        },
    )
    applyBackendState(getState(baseUrl), message = "TD 账号已保存")
  }

  override suspend fun saveHealthCloudCredentials(account: TdHealthCloudCredentialDraft) =
      runOperation {
        val baseUrl = endpoint()
        val configured = mutableState.value.account ?: error("TD 账号尚未加载，请返回设置后重试")
        val activeUsername = TdCampusCredentials.activeUsername()
        require(activeUsername == null || activeUsername == configured.studentId) {
          "统一认证账号已变化，请重新进入设置后修改"
        }
        postJson(
            "$baseUrl/api/settings/healthcloud",
            buildJsonObject {
              put("student_id", configured.studentId)
              put("username", account.username.orEmpty())
              put("password", account.password)
            },
        )
        applyBackendState(getState(baseUrl), message = "健康云账号设置已保存")
      }

  override suspend fun refreshCounts() =
      runOperation(refreshingCounts = true) {
        val baseUrl = endpoint()
        updateBackendConnectionMode(baseUrl)
        val configuredUsername = mutableState.value.account?.healthCloudUsername
        val campusCredential =
            TdCampusCredentials.current()?.takeIf { it.username == configuredUsername }
        val campusUsername = mutableState.value.account?.studentId
        val hasCurrentCampusSession =
            !campusUsername.isNullOrBlank() &&
                LocalAuthSessionStore.get()?.username == campusUsername
        val usesGateway = ConnectionRuntime.currentMode() == ConnectionMode.WEBVPN
        val gatewayCookies =
            if (usesGateway && hasCurrentCampusSession) {
              ensureLocalWebVpnSession()
              LocalCookieStore.storage()
                  .get(Url("https://d.buaa.edu.cn/"))
                  .filter {
                    it.domain?.trimStart('.')?.equals("d.buaa.edu.cn", ignoreCase = true) == true &&
                        it.path == "/"
                  }
                  .map { cookie ->
                    buildJsonObject {
                      put("name", cookie.name)
                      put("value", cookie.value)
                    }
                  }
            } else emptyList()
        val response =
            postJson(
                "$baseUrl/api/counts/refresh",
                buildJsonObject {
                  put("connection_mode", ConnectionRuntime.currentMode()?.storageKey.orEmpty())
                  campusCredential?.let {
                    put("student_id", it.username)
                    put("password", it.password)
                  }
                  if (usesGateway) {
                    put("gateway_cookies", JsonArray(gatewayCookies))
                  }
                },
            )
        val daily = response.objectOrNull("daily")
        val total = response.objectOrNull("total")
        val errors =
            response
                .arrayOrNull("errors")
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        val previous = mutableState.value.counts
        val refreshed =
            TdCounts(
                today = daily?.int("count") ?: previous.today,
                semester = total?.int("count") ?: previous.semester,
                term = total?.string("term") ?: previous.term,
                updatedAt =
                    listOfNotNull(daily?.string("updated_at"), total?.string("updated_at"))
                        .maxOrNull() ?: previous.updatedAt,
                cached = daily?.boolean("cached") == true || total?.boolean("cached") == true,
            )
        mutableState.value =
            mutableState.value.copy(
                loading = false,
                counts = refreshed,
                message = if (errors.isEmpty()) "健康云次数已刷新" else errors.joinToString("；"),
                error = null,
            )
      }

  override suspend fun addImage(name: String, bytes: ByteArray) = runOperation {
    require(bytes.isNotEmpty()) { "图片内容不能为空" }
    val safeName = safeImageName(name)
    val baseUrl = endpoint()
    requireSuccess(
        httpClient.post("$baseUrl/api/images") {
          setBody(
              MultiPartFormDataContent(
                  formData {
                    append(
                        "image",
                        bytes,
                        Headers.build {
                          append(
                              HttpHeaders.ContentDisposition,
                              ContentDisposition.File.withParameter(
                                      ContentDisposition.Parameters.Name,
                                      "image",
                                  )
                                  .withParameter(ContentDisposition.Parameters.FileName, safeName)
                                  .toString(),
                          )
                          append(HttpHeaders.ContentType, imageContentType(safeName))
                        },
                    )
                    append("overwrite", "true")
                  }
              )
          )
        }
    )
    applyBackendState(getState(baseUrl), message = "图片已添加")
  }

  override suspend fun deleteImage(name: String) = runOperation {
    val baseUrl = endpoint()
    requireSuccess(httpClient.delete("$baseUrl/api/images/${encodePathSegment(name)}"))
    applyBackendState(getState(baseUrl), message = "图片已删除")
  }

  override suspend fun startCheckin(rounds: Int) {
    check(ConnectionRuntime.currentMode() == ConnectionMode.DIRECT) {
      "当前连接模式仅支持查询 TD 健康云次数，打卡功能仅限直连模式"
    }
    require(rounds == 1 || rounds == 3) { "打卡次数只能为 1 或 3" }
    val ownerJob = currentCoroutineContext().job
    val requestId = UUID.randomUUID().toString()
    val claimed =
        synchronized(executionLock) {
          if (executionJob?.isActive == true) {
            false
          } else {
            executionJob = ownerJob
            executionRequestId = requestId
            true
          }
        }
    if (!claimed) {
      showError(IllegalStateException("已有 TD 打卡正在执行"))
      return
    }
    mutableState.value =
        mutableState.value.copy(
            loading = false,
            execution =
                TdExecutionState(
                    status = TdExecutionStatus.RUNNING,
                    requestedRounds = rounds,
                    phase = TdExecutionPhase.PREPARING,
                    message = "正在准备打卡",
                ),
            message = null,
            error = null,
        )

    var modeWatcher: Job? = null
    try {
      val baseUrl = endpoint()
      updateBackendConnectionMode(baseUrl)
      currentCoroutineContext().ensureActive()
      modeWatcher =
          CoroutineScope(currentCoroutineContext()).launch {
            ConnectionRuntime.currentModeFlow.collect { mode ->
              if (mode != ConnectionMode.DIRECT) {
                ownerJob.cancel(
                    CancellationException("TD check-in stopped after connection mode changed")
                )
              }
            }
          }
      val result = coroutineScope {
        val request = async {
          currentCoroutineContext().ensureActive()
          postJson(
              "$baseUrl/api/checkin",
              buildJsonObject {
                put("rounds", rounds)
                put("request_id", requestId)
                put("connection_mode", ConnectionRuntime.currentMode()?.storageKey.orEmpty())
              },
          )
        }
        while (!request.isCompleted && isActive) {
          delay(500)
          runCatching { getState(baseUrl) }.getOrNull()?.let { applyCheckinProgress(it, rounds) }
        }
        request.await()
      }
      val completed = result.int("completed_rounds") ?: 0
      val cancelled = result.boolean("cancelled") == true
      val success = result.boolean("success") == true
      val resultMessage = result.string("message")
      val terminalStatus =
          when {
            cancelled -> TdExecutionStatus.CANCELLED
            success -> TdExecutionStatus.COMPLETED
            else -> TdExecutionStatus.FAILED
          }
      val backendState = getState(baseUrl)
      applyBackendState(backendState)
      mutableState.value =
          mutableState.value.copy(
              execution =
                  TdExecutionState(
                      status = terminalStatus,
                      requestedRounds = rounds,
                      completedRounds = completed,
                      currentRound = completed,
                      message = resultMessage,
                  ),
              message = if (success) resultMessage else null,
              error = if (!success && !cancelled) resultMessage ?: "TD 打卡未完成" else null,
          )
    } catch (cancelled: CancellationException) {
      withContext(NonCancellable) { requestCancellation(requestId) }
      mutableState.value =
          mutableState.value.copy(
              execution =
                  mutableState.value.execution.copy(
                      status = TdExecutionStatus.CANCELLED,
                      remainingSeconds = null,
                      message = "已取消，未发送后续动作",
                  ),
          )
      throw cancelled
    } catch (cause: Throwable) {
      showError(cause)
      mutableState.value =
          mutableState.value.copy(
              execution =
                  mutableState.value.execution.copy(
                      status = TdExecutionStatus.FAILED,
                      remainingSeconds = null,
                      message = cause.message ?: "TD 打卡失败",
                  ),
          )
    } finally {
      modeWatcher?.cancel()
      synchronized(executionLock) {
        if (executionJob === ownerJob && executionRequestId == requestId) {
          executionJob = null
          executionRequestId = null
        }
      }
    }
  }

  override fun cancelCheckin() {
    val job = synchronized(executionLock) { executionJob }
    if (job?.isActive != true) return
    mutableState.value =
        mutableState.value.copy(
            execution =
                mutableState.value.execution.copy(
                    message = "正在取消，当前网络请求结束后不会发送后续动作",
                )
        )
    job.cancel(CancellationException("TD check-in cancelled"))
  }

  override fun onAppBackgrounded() = Unit

  override fun clearNotice() {
    mutableState.value = mutableState.value.copy(message = null, error = null)
  }

  override fun close() {
    synchronized(executionLock) { executionJob }?.cancel(CancellationException("TD service closed"))
    backend.close()
    httpClient.close()
  }

  private suspend fun endpoint(): String {
    endpoint?.let {
      return it
    }
    return backend.endpoint(httpClient).also { endpoint = it }
  }

  private suspend fun updateBackendConnectionMode(baseUrl: String) {
    postJson(
        "$baseUrl/api/connection-mode",
        buildJsonObject { put("mode", ConnectionRuntime.currentMode()?.storageKey.orEmpty()) },
    )
  }

  private suspend fun getState(baseUrl: String): JsonObject =
      requireSuccess(httpClient.get("$baseUrl/api/state"))

  private suspend fun postJson(url: String, payload: JsonObject? = null): JsonObject =
      requireSuccess(
          httpClient.post(url) {
            if (payload != null) {
              contentType(ContentType.Application.Json)
              setBody(payload.toString())
            }
          }
      )

  private suspend fun requireSuccess(response: HttpResponse): JsonObject {
    val body = response.bodyAsText()
    val payload =
        runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IllegalStateException("AutoTD 本地服务返回无法解析") }
    if (!response.status.isSuccess()) {
      throw IllegalStateException(payload.string("error") ?: "AutoTD 本地服务请求失败")
    }
    return payload
  }

  private suspend fun requestCancellation(requestId: String) {
    val baseUrl = endpoint ?: return
    runCatching {
      postJson(
          "$baseUrl/api/checkin/cancel",
          buildJsonObject { put("request_id", requestId) },
      )
    }
  }

  private fun applyBackendState(root: JsonObject, message: String? = null) {
    val user = root.arrayOrNull("users")?.firstOrNull() as? JsonObject
    val account =
        user?.let {
          TdAccount(
              studentId = it.string("student_id").orEmpty(),
              campus =
                  if (it.string("campus") == TdCampus.SHAHE.displayName) TdCampus.SHAHE
                  else TdCampus.XUEYUANLU,
              passwordConfigured =
                  it.boolean("healthcloud_password_configured") == true ||
                      TdCampusCredentials.current()?.let { credential ->
                        credential.username ==
                            (it.string("healthcloud_username") ?: it.string("student_id"))
                      } == true,
              healthCloudUsername =
                  it.string("healthcloud_username") ?: it.string("student_id").orEmpty(),
              healthCloudUsernameOverridden = it.boolean("healthcloud_username_overridden") == true,
          )
        }
    val images =
        root.arrayOrNull("images").orEmpty().mapNotNull { item ->
          val image = item as? JsonObject ?: return@mapNotNull null
          val name = image.string("name") ?: return@mapNotNull null
          TdImage(
              name = name,
              sizeBytes = image.long("size") ?: 0,
              updatedAt = image.string("updated_at"),
          )
        }
    val previousCounts = mutableState.value.counts
    val counts =
        TdCounts(
            today = user?.int("td_count") ?: previousCounts.today,
            semester = user?.int("total_count") ?: previousCounts.semester,
            term = previousCounts.term,
            updatedAt =
                listOfNotNull(
                        user?.string("td_count_updated_at"),
                        user?.string("total_count_updated_at"),
                    )
                    .maxOrNull() ?: previousCounts.updatedAt,
            cached = true,
        )
    mutableState.value =
        mutableState.value.copy(
            available = true,
            availabilityMessage = null,
            loading = false,
            account = account,
            counts = counts,
            images = images,
            message = message,
            error = null,
        )
  }

  private fun applyCheckinProgress(root: JsonObject, requestedRounds: Int) {
    val status = root.objectOrNull("checkin_status") ?: return
    val progress =
        desktopExecutionProgress(
            status = status,
            requestedRounds = requestedRounds,
            previous = mutableState.value.execution,
        ) ?: return
    mutableState.value = mutableState.value.copy(execution = progress)
  }

  private suspend fun runOperation(refreshingCounts: Boolean = false, block: suspend () -> Unit) {
    mutableState.value =
        mutableState.value.copy(
            loading = true,
            refreshingCounts = refreshingCounts,
            message = null,
            error = null,
        )
    try {
      block()
    } catch (cancelled: CancellationException) {
      mutableState.value = mutableState.value.copy(loading = false)
      throw cancelled
    } catch (cause: Throwable) {
      showError(cause)
    } finally {
      if (refreshingCounts) mutableState.value = mutableState.value.copy(refreshingCounts = false)
    }
  }

  private fun showError(cause: Throwable) {
    val ready = mutableState.value.available
    mutableState.value =
        mutableState.value.copy(
            loading = false,
            availabilityMessage =
                if (ready) mutableState.value.availabilityMessage
                else cause.message ?: "TD 本地服务启动失败",
            error = cause.message ?: "TD 操作失败",
        )
  }
}

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.arrayOrNull(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

private fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun desktopExecutionProgress(
    status: JsonObject,
    requestedRounds: Int,
    previous: TdExecutionState,
): TdExecutionState? {
  if (status.boolean("active") != true) return null
  return previous.copy(
      status = TdExecutionStatus.RUNNING,
      requestedRounds = requestedRounds,
      completedRounds = status.int("completed_rounds") ?: previous.completedRounds,
      currentRound = status.int("current_round") ?: previous.currentRound,
      phase = desktopPhase(status.string("phase")) ?: previous.phase,
      remainingSeconds = status.int("remaining_seconds"),
      message = status.string("message"),
  )
}

private fun desktopPhase(value: String?): TdExecutionPhase? =
    when (value) {
      "preparing" -> TdExecutionPhase.PREPARING
      "entrance" -> TdExecutionPhase.ENTRANCE
      "entrance_photo" -> TdExecutionPhase.ENTRANCE_PHOTO
      "entrance_delay" -> TdExecutionPhase.ENTRANCE_DELAY
      "exit" -> TdExecutionPhase.EXIT
      "exit_photo" -> TdExecutionPhase.EXIT_PHOTO
      "round_delay" -> TdExecutionPhase.ROUND_DELAY
      else -> null
    }

private fun safeImageName(name: String): String {
  val safe = name.substringAfterLast('/').substringAfterLast('\\').trim()
  require(safe.isNotBlank() && safe !in setOf(".", "..")) { "图片名称无效" }
  return safe
}

private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun imageContentType(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
      "jpg",
      "jpeg" -> "image/jpeg"
      "png" -> "image/png"
      "heic",
      "heif" -> "image/heic"
      else -> "application/octet-stream"
    }
