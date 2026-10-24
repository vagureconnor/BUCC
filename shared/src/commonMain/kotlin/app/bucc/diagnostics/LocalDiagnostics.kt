package app.bucc.diagnostics

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class DiagnosticModule {
  PAPER_PLANE,
  RECHARGE,
}

enum class DiagnosticOperation {
  ONE_CLICK,
  PREPARE_THROW,
  THROW,
  TASKS,
  FEED,
  VIEW,
  SPOC_IDENTITY,
  PREPARE_RECHARGE,
  CREATE_ORDER,
  PREPARE_SUBMISSION,
  PAYMENT_CONNECT,
  PAYMENT_BROWSER,
}

enum class DiagnosticStage {
  START,
  FINISH,
  HTTP,
  IDENTITY,
  COURSE,
  FEELING,
  KEYWORD,
  THROW,
  VIEW_STATUS,
  VIEW_WRITE,
  TASK_REFRESH,
  PREPARE,
  CREATE_ORDER,
  RESOLVE_PAYMENT_PAGE,
  CONNECT_PAYMENT_SESSION,
  COOKIE_SYNC,
  NAVIGATION,
  NEW_WINDOW,
  SAME_PAGE,
  REDIRECT,
}

enum class DiagnosticOutcome {
  STARTED,
  SUCCESS,
  FAILURE,
  UNCERTAIN,
  BLOCKED,
}

internal interface DiagnosticFiles {
  fun read(slot: Int): String?

  fun write(slot: Int, value: String)

  fun delete(slot: Int)

  fun modifiedAt(slot: Int): Long?

  var enabled: Boolean
}

internal expect object PlatformDiagnosticFiles : DiagnosticFiles

internal expect fun diagnosticStackFrames(error: Throwable): List<String>

/** URL data is discarded unless both host and a literal route are approved. */
internal fun diagnosticEndpoint(raw: String): String? {
  val parsed = runCatching { Url(raw) }.getOrNull() ?: return null
  if (
      parsed.protocol != URLProtocol.HTTPS ||
          parsed.port != 443 ||
          !parsed.user.isNullOrEmpty() ||
          !parsed.password.isNullOrEmpty()
  )
      return null
  val original =
      if (parsed.host == "d.buaa.edu.cn") {
        val decoded = app.bucc.api.local.LocalWebVpnSupport.fromWebVpnUrl(raw)
        if (decoded == raw || app.bucc.api.local.LocalWebVpnSupport.toWebVpnUrl(decoded) != raw)
            return null
        decoded
      } else raw
  val url = runCatching { Url(original) }.getOrNull() ?: return null
  if (
      url.protocol != URLProtocol.HTTPS ||
          url.port != 443 ||
          !url.user.isNullOrEmpty() ||
          !url.password.isNullOrEmpty()
  )
      return null
  val routes =
      when (url.host) {
        "spoc.buaa.edu.cn" ->
            setOf(
                "/spocnewht/inco/ht/queryOne",
                "/spocnewht/inco/ht/queryList",
                "/spocnewht/inco/ht/queryListByPage",
                "/spocnewht/ansj/splitPaperAirplanes",
                "/spocnewht/jxkj/queryKclb",
                "/spocnewht/sys/casLogin",
                "/spocnewht/cas",
                "/spocnewht/inco/ht/add",
                "/spocnewht/grzx/queryByInfo",
                "/spocnewht/inco/ht/queryPage",
                "/spocnewht/inco/ht/edit",
            )
        "m.icw.buaa.edu.cn" ->
            setOf(
                "/card/home/openHomePageApp",
                "/card/queryPayInfoList",
                "/card/cardpay/openCardPay",
                "/card/recharge/rechargeByHct",
            )
        "zfw.buaa.edu.cn" ->
            setOf(
                "/",
                "/home",
                "/login",
                "/pay/card",
                "/pay/captcha",
                "/site/captcha",
                "/site/validate-user",
                "/site/validate-smscode",
                "/site/login",
                "/login/send-sms-code",
                "/qrcode",
            )
        "pass.cc-pay.cn",
        "sso.buaa.edu.cn" -> setOf("/login")
        "mall.cc-pay.cn" -> setOf("/", "/cashier", "/cashier/", "/api/payment")
        "cashier.cc-pay.cn" -> setOf("/cashier", "/cashier/")
        else -> return null
      }
  val path = url.encodedPath
  if (url.host == "mall.cc-pay.cn" && Regex("^/entry/card/[A-Za-z0-9_-]{1,128}$").matches(path))
      return "${url.host}/entry/card/[redacted]"
  return if (path in routes) "${url.host}$path" else null
}

@Serializable
internal data class DiagnosticFailure(
    val type: String,
    val frames: List<String>,
    val cause: DiagnosticFailure? = null,
)

internal fun diagnosticFailure(
    error: Throwable,
    seen: List<Throwable> = emptyList(),
): DiagnosticFailure? {
  if (seen.size >= 8 || seen.any { it === error }) return null
  val allowed =
      setOf(
          "IllegalStateException",
          "IllegalArgumentException",
          "IOException",
          "SocketTimeoutException",
          "HttpRequestTimeoutException",
          "ConnectTimeoutException",
          "LocalSpocAuthenticationException",
          "LocalSpocException",
          "RechargeFlowException",
          "ApiCallException",
          "CancellationException",
          "SerializationException",
          "JsonDecodingException",
      )
  val type = error::class.simpleName?.takeIf { it in allowed } ?: "Throwable"
  // Platform APIs return actual frame symbols, never stackTraceToString() with error messages.
  val frames =
      diagnosticStackFrames(error)
          .mapNotNull { frame ->
            Regex("(?:app\\.bucc|io\\.ktor|kotlinx?)(?:\\.[A-Za-z_$][A-Za-z0-9_$<>]*)+")
                .find(frame)
                ?.value
                ?.take(180)
          }
          .take(24)
  return DiagnosticFailure(type, frames, error.cause?.let { diagnosticFailure(it, seen + error) })
}

@Serializable
internal data class DiagnosticEvent(
    val timestampMillis: Long,
    val localTime: String,
    val version: String,
    val module: DiagnosticModule,
    val operation: DiagnosticOperation,
    val correlationId: String,
    val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome,
    val elapsedMillis: Long,
    val endpoint: String? = null,
    val httpStatus: Int? = null,
    val businessCode: String? = null,
    val failure: DiagnosticFailure? = null,
)

internal class DiagnosticStore(
    private val files: DiagnosticFiles,
    private val nowMillis: () -> Long,
    private val maxBytes: Int = 1024 * 1024,
    private val retentionMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
  private val mutex = Mutex()

  suspend fun append(line: String) =
      mutex.withLock {
        if (!files.enabled) return@withLock
        val cutoff = nowMillis() - retentionMillis
        repeat(3) { slot ->
          val original = files.read(slot)
          if (original != null) {
            val retained =
                original
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .filter { record ->
                      val timestamp =
                          runCatching {
                                Json.parseToJsonElement(record)
                                    .jsonObject["timestampMillis"]
                                    ?.jsonPrimitive
                                    ?.longOrNull
                              }
                              .getOrNull()
                      (timestamp ?: files.modifiedAt(slot) ?: 0L) >= cutoff
                    }
                    .joinToString("\n")
                    .let { if (it.isEmpty()) it else it + "\n" }
            if (retained.isEmpty()) files.delete(slot)
            else if (retained != original) files.write(slot, retained)
          }
        }
        val record =
            if ((line + "\n").encodeToByteArray().size > maxBytes) "{\"event\":\"TRUNCATED\"}\n"
            else line + "\n"
        if (record.encodeToByteArray().size > maxBytes) return@withLock
        val current = files.read(0).orEmpty()
        if ((current + record).encodeToByteArray().size > maxBytes) {
          files.read(1)?.let { files.write(2, it) } ?: files.delete(2)
          files.read(0)?.let { files.write(1, it) } ?: files.delete(1)
          files.write(0, record)
        } else files.write(0, current + record)
      }
}

class DiagnosticSession
internal constructor(
    internal val module: DiagnosticModule,
    internal val operation: DiagnosticOperation,
    internal val id: String,
) {
  internal val started = TimeSource.Monotonic.markNow()

  suspend fun record(
      stage: DiagnosticStage,
      outcome: DiagnosticOutcome,
      url: String? = null,
      httpStatus: Int? = null,
      businessCode: String? = null,
      error: Throwable? = null,
  ) {
    LocalDiagnostics.emit(this, stage, outcome, url, httpStatus, businessCode, error)
  }

  suspend fun <T> run(block: suspend () -> T): T = withContext(DiagnosticContext(this)) { block() }
}

private class DiagnosticContext(val session: DiagnosticSession) :
    AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<DiagnosticContext>
}

object LocalDiagnostics {
  private val store by lazy {
    DiagnosticStore(PlatformDiagnosticFiles, { Clock.System.now().toEpochMilliseconds() })
  }

  @OptIn(ExperimentalUuidApi::class)
  fun session(module: DiagnosticModule, operation: DiagnosticOperation): DiagnosticSession =
      DiagnosticSession(module, operation, Uuid.random().toString())

  fun setEnabled(enabled: Boolean) {
    PlatformDiagnosticFiles.enabled = enabled
  }

  fun clear() {
    repeat(3) { PlatformDiagnosticFiles.delete(it) }
  }

  internal suspend fun emit(
      session: DiagnosticSession,
      stage: DiagnosticStage,
      outcome: DiagnosticOutcome,
      url: String?,
      httpStatus: Int?,
      businessCode: String?,
      error: Throwable?,
  ) {
    try {
      val instant = Clock.System.now()
      val zone = TimeZone.currentSystemDefault()
      val event =
          DiagnosticEvent(
              instant.toEpochMilliseconds(),
              instant.toLocalDateTime(zone).toString() + zone.offsetAt(instant).toString(),
              app.bucc.AppInfo.version,
              session.module,
              session.operation,
              session.id,
              stage,
              outcome,
              session.started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
              url?.let(::diagnosticEndpoint),
              httpStatus?.takeIf { it in 100..599 },
              businessCode?.let {
                if (
                    it in
                        setOf(
                            "0",
                            "1",
                            "200",
                            "401",
                            "403",
                            "spoc_auth_failed",
                            "spoc_error",
                            "webkit_timeout",
                            "webkit_http",
                            "webkit_process",
                            "webkit_dns",
                            "webkit_tls",
                            "webkit_connection",
                            "webkit_navigation",
                            "payment_redirect_insecure",
                            "payment_redirect_login_back",
                            "payment_redirect_pass_route",
                            "payment_redirect_cashier_route",
                            "payment_redirect_mall_route",
                            "payment_redirect_sso_route",
                            "payment_redirect_other_origin",
                            "payment_redirect_zfw_route",
                        )
                )
                    it
                else "other"
              },
              error?.let { diagnosticFailure(it) },
          )
      store.append(Json.encodeToString(event))
    } catch (_: Exception) {
      /* Diagnostics must not change or expose the business result. */
    }
  }
}

suspend fun recordDiagnostic(
    stage: DiagnosticStage,
    outcome: DiagnosticOutcome,
    url: String? = null,
    httpStatus: Int? = null,
    businessCode: String? = null,
    error: Throwable? = null,
) {
  currentCoroutineContext()[DiagnosticContext]
      ?.session
      ?.record(stage, outcome, url, httpStatus, businessCode, error)
}

suspend fun <T> withLocalDiagnostics(
    module: DiagnosticModule,
    operation: DiagnosticOperation,
    outcome: (T) -> DiagnosticOutcome = { DiagnosticOutcome.SUCCESS },
    block: suspend () -> T,
): T {
  val parent = currentCoroutineContext()[DiagnosticContext]?.session
  val session =
      parent?.let { DiagnosticSession(module, operation, it.id) }
          ?: LocalDiagnostics.session(module, operation)
  return session.run {
    session.record(DiagnosticStage.START, DiagnosticOutcome.STARTED)
    try {
      block().also { session.record(DiagnosticStage.FINISH, outcome(it)) }
    } catch (error: Throwable) {
      session.record(DiagnosticStage.FINISH, DiagnosticOutcome.FAILURE, error = error)
      throw error
    }
  }
}
