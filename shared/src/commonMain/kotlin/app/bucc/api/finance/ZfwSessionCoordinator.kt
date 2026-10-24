package app.bucc.api.finance

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.api.plantform.PlatformRsaPkcs1Encrypt
import app.bucc.api.storage.CredentialStore
import app.bucc.diagnostics.*
import app.bucc.security.CampusCredential
import app.bucc.security.CampusSecureCredentials
import app.bucc.td.TdCampusCredentials
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

sealed interface ZfwSessionState {
  data object Checking : ZfwSessionState

  data object Ready : ZfwSessionState

  data class SmsChallenge(val error: String? = null) : ZfwSessionState

  class LoginCaptcha(val image: ByteArray, val error: String? = null) : ZfwSessionState {
    override fun toString() = "LoginCaptcha([redacted])"
  }

  data class Failed(val reason: String) : ZfwSessionState
}

internal class ZfwLoginForm(
    val fields: Map<String, String>,
    val action: String,
    val captcha: String,
    val publicKey: String,
) {
  override fun toString() = "ZfwLoginForm([redacted])"
}

private data class ZfwLogoutAction(
    val target: String?,
    val method: HttpMethod?,
    val csrfField: String? = null,
    val csrfToken: String? = null,
)

private const val OLD_ZFW_SESSION_MESSAGE = "校园网仍处于旧登录状态，无法开始本次验证码验证。"

private fun isZfwLoggedInPage(html: String): Boolean =
    listOf("已用流量", "免费流量剩余", "计费流量剩余").all(html::contains) &&
        !Regex("(?is)<input[^>]+type\\s*=\\s*['\"]?password").containsMatchIn(html)

private fun logoutCandidatePath(value: String): String? {
  val allowedPaths = setOf("/site/logout", "/logout")
  normalizeZfwUrl(value, allowedPaths)?.let {
    return Url(it).encodedPath
  }
  val trimmed = value.trim()
  val absolute = if (trimmed.startsWith('/')) "https://zfw.buaa.edu.cn$trimmed" else trimmed
  val source = runCatching { Url(absolute) }.getOrNull() ?: return null
  val resolved =
      if (source.host.equals("d.buaa.edu.cn", true)) {
        LocalWebVpnSupport.fromWebVpnUrl(absolute)
      } else absolute
  return runCatching { Url(resolved).encodedPath }.getOrNull()?.takeIf { it in allowedPaths }
}

private fun logoutCsrfFromMeta(html: String): Pair<String, String>? {
  val metas = Regex("(?is)<meta\\b[^>]*>").findAll(html).map { formAttributes(it.value) }.toList()
  val params = metas.filter { it["name"].equals("csrf-param", true) }
  val tokens = metas.filter { it["name"].equals("csrf-token", true) }
  val param = params.singleOrNull()?.get("content")?.takeIf(String::isNotBlank) ?: return null
  val token = tokens.singleOrNull()?.get("content")?.takeIf(String::isNotBlank) ?: return null
  return param to token
}

private fun logoutCsrfFromForm(body: String): Pair<String, String>? {
  val fields =
      Regex("(?is)<input\\b[^>]*>")
          .findAll(body)
          .map { formAttributes(it.value) }
          .mapNotNull { attrs ->
            val name = attrs["name"] ?: return@mapNotNull null
            val type = attrs["type"]?.lowercase() ?: "text"
            if (type == "hidden" && Regex("(?i)(?:_csrf|csrf)(?:[-_].*)?").matches(name))
                name to attrs["value"]
            else null
          }
          .toList()
  val (name, token) = fields.singleOrNull() ?: return null
  return token?.takeIf(String::isNotBlank)?.let { name to it }
}

private fun findZfwLogoutAction(html: String): ZfwLogoutAction? {
  val document = Regex("(?is)<script\\b[^>]*>.*?</script>|<!--.*?-->").replace(html, "")
  val candidates = mutableListOf<ZfwLogoutAction>()
  Regex("(?is)<form\\b([^>]*)>(.*?)</form>").findAll(document).forEach { match ->
    val attrs = formAttributes(match.groupValues[1])
    val reference = attrs["action"] ?: return@forEach
    if (logoutCandidatePath(reference) == null) return@forEach
    val target = normalizeZfwUrl(reference, setOf("/site/logout", "/logout"))
    val method =
        when (attrs["method"]?.lowercase()) {
          "get" -> HttpMethod.Get
          "post" -> HttpMethod.Post
          else -> null
        }
    val csrf = logoutCsrfFromMeta(document) ?: logoutCsrfFromForm(match.groupValues[2])
    candidates += ZfwLogoutAction(target, method, csrf?.first, csrf?.second)
  }
  Regex("(?is)<a\\b[^>]*>").findAll(document).forEach { match ->
    val attrs = formAttributes(match.value)
    val reference = attrs["href"] ?: return@forEach
    if (logoutCandidatePath(reference) == null) return@forEach
    val target = normalizeZfwUrl(reference, setOf("/site/logout", "/logout"))
    val method =
        when (attrs["data-method"]?.lowercase()) {
          null,
          "get" -> HttpMethod.Get
          "post" -> HttpMethod.Post
          else -> null
        }
    val csrf = logoutCsrfFromMeta(document)
    candidates += ZfwLogoutAction(target, method, csrf?.first, csrf?.second)
  }
  val candidate = candidates.singleOrNull() ?: return null
  if (candidate.target == null || candidate.method == null) return null
  if (
      candidate.method == HttpMethod.Post &&
          (candidate.csrfField.isNullOrBlank() || candidate.csrfToken.isNullOrBlank())
  )
      return null
  return candidate
}

internal fun parseLogin(html: String): ZfwLoginForm? {
  val form =
      Regex("(?is)<form\\b([^>]*)>(.*?)</form>").findAll(html).firstOrNull {
        formAttributes(it.groupValues[1])["method"].equals("post", true) &&
            it.groupValues[2].contains("LoginForm[username]") &&
            it.groupValues[2].contains("LoginForm[password]")
      } ?: return null
  val fields =
      Regex("(?is)<input\\b[^>]*>")
          .findAll(form.groupValues[2])
          .mapNotNull {
            val a = formAttributes(it.value)
            a["name"]?.let { n -> n to a["value"].orEmpty() }
          }
          .toMap()
  if (
      fields.keys.count { it.startsWith("_csrf-") } != 1 ||
          fields.filterKeys { it.startsWith("_csrf-") }.values.single().isBlank() ||
          listOf(
                  "LoginForm[username]",
                  "LoginForm[password]",
                  "LoginForm[smsCode]",
                  "LoginForm[verifyCode]",
              )
              .any { it !in fields }
  )
      return null
  val action = formAttributes(form.groupValues[1])["action"].orEmpty().ifEmpty { "/" }
  val normalizedAction = normalizeZfwUrl(action, setOf("/", "/site/login")) ?: return null
  val image =
      Regex("(?is)<img\\b[^>]*>")
          .findAll(form.groupValues[2])
          .mapNotNull { formAttributes(it.value)["src"] }
          .firstNotNullOfOrNull { normalizeZfwUrl(it, setOf("/site/captcha")) } ?: return null
  val publicKey =
      Regex("(?is)<input\\b[^>]*>")
          .findAll(html)
          .map { formAttributes(it.value) }
          .firstOrNull { it["id"] == "public" }
          ?.get("value")
          ?.takeIf(String::isNotBlank) ?: return null
  return ZfwLoginForm(
      fields.filterKeys { it.startsWith("_csrf-") || it.startsWith("LoginForm[") },
      normalizedAction,
      image,
      publicKey,
  )
}

/** Finance services now reject the WebVPN gateway; always use their official direct origin. */
internal fun financeDirectUrl(raw: String): String = raw

/** Resolves ZFW URLs, decoding only URLs that enter through BUAA's actual WebVPN gateway. */
internal fun normalizeZfwUrl(value: String, allowedPaths: Set<String>): String? {
  val candidate = value.trim()
  if (candidate.isEmpty()) return null
  val absolute =
      when {
        candidate.startsWith("/https/") -> "https://d.buaa.edu.cn$candidate"
        candidate.startsWith('/') -> "https://zfw.buaa.edu.cn$candidate"
        else -> candidate
      }
  val source = runCatching { Url(absolute) }.getOrNull() ?: return null
  val decoded =
      if (source.host.equals("d.buaa.edu.cn", ignoreCase = true)) {
        if (
            source.protocol != URLProtocol.HTTPS ||
                source.port != 443 ||
                !source.user.isNullOrEmpty() ||
                !source.password.isNullOrEmpty()
        )
            return null
        LocalWebVpnSupport.fromWebVpnUrl(absolute).takeIf { it != absolute } ?: return null
      } else {
        absolute
      }
  val target = runCatching { Url(decoded) }.getOrNull() ?: return null
  if (
      target.host != "zfw.buaa.edu.cn" ||
          target.protocol != URLProtocol.HTTPS ||
          target.port != 443 ||
          !target.user.isNullOrEmpty() ||
          !target.password.isNullOrEmpty() ||
          target.encodedPath !in allowedPaths ||
          target.fragment.isNotEmpty()
  )
      return null
  return buildString {
    append("https://zfw.buaa.edu.cn")
    append(target.encodedPath)
    if (target.encodedQuery.isNotEmpty()) append('?').append(target.encodedQuery)
  }
}

internal fun encryptZfwPassword(password: String, publicKey: String): String {
  val der =
      Base64.decode(
          publicKey
              .replace("-----BEGIN PUBLIC KEY-----", "")
              .replace("-----END PUBLIC KEY-----", "")
              .replace(Regex("\\s+"), "")
      )
  return Base64.encode(PlatformRsaPkcs1Encrypt.encrypt(password.encodeToByteArray(), der))
}

private class ZfwReadFailure(val reason: String) : Exception()

/** Follows only bounded, validated read redirects, using the current mode's cookie jar. */
internal suspend fun readZfwPage(
    client: HttpClient,
    initial: String,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse {
  val zfwPaths =
      setOf(
          "/",
          "/home",
          "/site/login",
          "/site/captcha",
          "/pay/card",
          "/pay/captcha",
          "/sso/cas/index",
      )
  var target = financeDirectUrl(initial)
  val visited = mutableSetOf<String>()
  repeat(9) { hop ->
    val upstream = LocalWebVpnSupport.fromWebVpnUrl(target)
    val url =
        runCatching { Url(upstream) }.getOrNull() ?: throw ZfwReadFailure("校园网验证跳转无法识别，请刷新后重试。")
    val secure =
        url.protocol == URLProtocol.HTTPS &&
            url.port == 443 &&
            url.user.isNullOrEmpty() &&
            url.password.isNullOrEmpty() &&
            url.fragment.isEmpty()
    val casService =
        url.parameters.getAll("service")?.singleOrNull()?.let {
          normalizeZfwUrl(LocalWebVpnSupport.fromWebVpnUrl(it), zfwPaths)
        }
    if (
        !secure ||
            target != financeDirectUrl(upstream) ||
            !(url.host == "zfw.buaa.edu.cn" && url.encodedPath in zfwPaths ||
                url.host == "sso.buaa.edu.cn" && url.encodedPath == "/login" && casService != null)
    ) {
      throw ZfwReadFailure("校园网验证跳转不受支持，请刷新或切换连接模式。")
    }
    if (!visited.add(target)) throw ZfwReadFailure("校园网验证出现循环跳转，请刷新或切换连接模式。")
    val response = client.financeGet(target, block)
    if (response.status.value !in 300..399) return response
    if (hop == 8) throw ZfwReadFailure("校园网验证跳转次数过多，请刷新后重试。")
    val location =
        response.headers[HttpHeaders.Location] ?: throw ZfwReadFailure("校园网验证跳转信息不完整，请刷新后重试。")
    val resolved = resolveFinanceRedirect(response.call.request.url, location)
    target = financeDirectUrl(LocalWebVpnSupport.fromWebVpnUrl(resolved))
  }
  error("Unreachable redirect state")
}

interface ZfwSessionBackend {
  val state: StateFlow<ZfwSessionState>

  suspend fun ensureSession(
      refreshCaptcha: Boolean = false,
      forceVerification: Boolean = false,
  ): ZfwSessionState

  /** Re-runs the portal CAS bridge so the ZFW form POST gets a fully authenticated session. */
  suspend fun ensurePaymentSession(): ZfwSessionState = ensureSession()

  suspend fun submitLoginCaptcha(code: String): ZfwSessionState

  suspend fun submitSmsCode(code: String): ZfwSessionState = ZfwSessionState.Failed("短信验证暂不可用。")
}

class ZfwSessionCoordinator
internal constructor(
    private val clientFactory: () -> HttpClient,
    private val credentials: suspend () -> CampusCredential?,
    private val encryptPassword: (String, String) -> String = ::encryptZfwPassword,
    private val beginVerification: suspend () -> Unit = {},
) : ZfwSessionBackend {
  private val mutex = Mutex()
  private var client: HttpClient? = null
  private var loginForm: ZfwLoginForm? = null
  private var pendingLogin: Map<String, String>? = null
  private val json = Json { ignoreUnknownKeys = true }
  private val mutableState = MutableStateFlow<ZfwSessionState>(ZfwSessionState.Checking)
  private var lastPaymentHandoff: kotlin.time.TimeMark? = null
  override val state = mutableState.asStateFlow()

  private fun client() = client ?: clientFactory().also { client = it }

  private suspend fun hasSession(): Boolean {
    val response = readZfwPage(client(), "https://zfw.buaa.edu.cn/home")
    if (!response.status.isSuccess()) return false
    return isZfwLoggedInPage(response.bodyAsText())
  }

  override suspend fun ensurePaymentSession(): ZfwSessionState =
      mutex.withLock {
        guarded {
          // Balance refresh and recharge preparation can run together; a second CAS handoff
          // would rotate PHPSESSID/_csrf while the first caller reads the form or captcha.
          val recent =
              lastPaymentHandoff?.let { it.elapsedNow() < kotlin.time.Duration.parse("20s") } ==
                  true
          if (mutableState.value is ZfwSessionState.Ready && recent)
              return@guarded ZfwSessionState.Ready
          if (mutableState.value !is ZfwSessionState.Ready)
              mutableState.value = ZfwSessionState.Checking
          if (tryCasHandoff()) {
            loginForm = null
            lastPaymentHandoff = kotlin.time.TimeSource.Monotonic.markNow()
            ZfwSessionState.Ready
          } else if (hasSession()) {
            loginForm = null
            ZfwSessionState.Ready
          } else challenge()
        }
      }

  override suspend fun ensureSession(
      refreshCaptcha: Boolean,
      forceVerification: Boolean,
  ): ZfwSessionState =
      mutex.withLock {
        if (
            !forceVerification &&
                !refreshCaptcha &&
                mutableState.value is ZfwSessionState.LoginCaptcha
        )
            return@withLock mutableState.value
        guarded {
          if (mutableState.value !is ZfwSessionState.Ready)
              mutableState.value = ZfwSessionState.Checking
          if (forceVerification) beginVerification()
          if (!forceVerification && hasSession()) {
            loginForm = null
            ZfwSessionState.Ready
          } else if (credentials() == null) ZfwSessionState.Failed("请在 BUCC 完成主账号登录以验证校园网身份。")
          else if (tryCasHandoff()) {
            loginForm = null
            ZfwSessionState.Ready
          } else challenge(forceVerification = forceVerification)
        }
      }

  /** The portal opens ZFW through this CAS bridge, so a valid CAS session needs no captcha. */
  private suspend fun tryCasHandoff(): Boolean =
      try {
        val response = readZfwPage(client(), "https://zfw.buaa.edu.cn/sso/cas/index")
        response.status.isSuccess() && hasSession()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        false
      }

  private suspend fun challenge(
      error: String? = null,
      forceVerification: Boolean = false,
  ): ZfwSessionState {
    var page = readZfwPage(client(), "https://zfw.buaa.edu.cn/")
    check(page.status.isSuccess())
    var html = page.bodyAsText()
    if (forceVerification && isZfwLoggedInPage(html)) {
      val logout =
          findZfwLogoutAction(html) ?: return ZfwSessionState.Failed(OLD_ZFW_SESSION_MESSAGE)
      val response =
          when (logout.method) {
            HttpMethod.Get -> client().financeGet(financeDirectUrl(logout.target!!))
            HttpMethod.Post ->
                client().financePost(financeDirectUrl(logout.target!!)) {
                  setBody(
                      FormDataContent(
                          Parameters.build { append(logout.csrfField!!, logout.csrfToken!!) }
                      )
                  )
                }
            else -> return ZfwSessionState.Failed(OLD_ZFW_SESSION_MESSAGE)
          }
      if (
          !response.status.isSuccess() &&
              response.status !in setOf(HttpStatusCode.Found, HttpStatusCode.SeeOther)
      ) {
        return ZfwSessionState.Failed(OLD_ZFW_SESSION_MESSAGE)
      }
      page = readZfwPage(client(), "https://zfw.buaa.edu.cn/")
      check(page.status.isSuccess())
      html = page.bodyAsText()
      if (isZfwLoggedInPage(html)) return ZfwSessionState.Failed(OLD_ZFW_SESSION_MESSAGE)
    }
    val form = parseLogin(html) ?: return ZfwSessionState.Failed("校园网登录表单暂时无法识别。")
    pendingLogin = null
    val captchaUrl = refreshZfwCaptcha(client(), form.captcha)
    val image = readZfwPage(client(), captchaUrl)
    check(image.status.isSuccess() && image.contentType().toString().startsWith("image/"))
    val bytes = image.bodyAsBytes()
    check(bytes.isNotEmpty())
    loginForm = form
    return ZfwSessionState.LoginCaptcha(bytes, error)
  }

  override suspend fun submitLoginCaptcha(code: String): ZfwSessionState =
      mutex.withLock {
        if (code.isBlank()) return@withLock mutableState.value
        val form = loginForm ?: return@withLock ZfwSessionState.Failed("请刷新校园网登录验证码。")
        guarded {
          val credential =
              credentials() ?: return@guarded ZfwSessionState.Failed("请在 BUCC 完成主账号登录。")
          mutableState.value = ZfwSessionState.Checking
          loginForm = null
          val fields =
              form.fields.toMutableMap().apply {
                put("LoginForm[username]", credential.username)
                put("LoginForm[password]", encryptPassword(credential.password, form.publicKey))
                put("LoginForm[verifyCode]", code.trim())
              }
          val validation =
              postValidation(
                  "/site/validate-user",
                  fields.filterKeys {
                    it.startsWith("_csrf-") ||
                        it in
                            setOf(
                                "LoginForm[username]",
                                "LoginForm[password]",
                                "LoginForm[verifyCode]",
                            )
                  },
              )
          if (validation["success"]?.jsonPrimitive?.booleanOrNull != true) {
            challenge("验证码或登录信息未通过，请重新输入验证码。")
          } else
              when (validation["inputSms"]?.jsonPrimitive?.booleanOrNull) {
                true -> {
                  loginForm = form
                  pendingLogin = fields
                  ZfwSessionState.SmsChallenge()
                }
                false -> finishLogin(form, fields)
                null -> ZfwSessionState.Failed("校园网验证响应无法识别，未继续登录。")
              }
        }
      }

  override suspend fun submitSmsCode(code: String): ZfwSessionState =
      mutex.withLock {
        val form = loginForm ?: return@withLock ZfwSessionState.Failed("请刷新校园网登录验证码。")
        val fields = pendingLogin ?: return@withLock ZfwSessionState.Failed("请重新验证校园网身份。")
        if (code.isBlank()) return@withLock mutableState.value
        guarded {
          val validation =
              postValidation(
                  "/site/validate-smscode",
                  buildMap {
                    form.fields
                        .filterKeys { it.startsWith("_csrf-") }
                        .forEach { (k, v) -> put(k, v) }
                    put("uname", fields.getValue("LoginForm[username]"))
                    put("code", code.trim())
                  },
              )
          if (validation["success"]?.jsonPrimitive?.booleanOrNull == true)
              finishLogin(form, fields + ("LoginForm[smsCode]" to code.trim()))
          else ZfwSessionState.SmsChallenge("短信验证码未通过，请重新输入。")
        }
      }

  private suspend fun postValidation(path: String, fields: Map<String, String>): JsonObject {
    val response =
        client().financePost(financeDirectUrl("https://zfw.buaa.edu.cn$path")) {
          header("X-Requested-With", "XMLHttpRequest")
          setBody(FormDataContent(Parameters.build { fields.forEach { (k, v) -> append(k, v) } }))
        }
    check(response.status.isSuccess())
    val parsed = json.parseToJsonElement(response.bodyAsText())
    return (if (parsed is JsonPrimitive && parsed.isString) json.parseToJsonElement(parsed.content)
    else parsed)
        as? JsonObject ?: error("Unrecognized validation response")
  }

  private suspend fun finishLogin(
      form: ZfwLoginForm,
      fields: Map<String, String>,
  ): ZfwSessionState {
    pendingLogin = null
    loginForm = null
    val response =
        client().financePost(financeDirectUrl(form.action)) {
          setBody(FormDataContent(Parameters.build { fields.forEach { (k, v) -> append(k, v) } }))
        }
    check(
        response.status.isSuccess() ||
            response.status in setOf(HttpStatusCode.Found, HttpStatusCode.SeeOther)
    )
    return if (hasSession()) ZfwSessionState.Ready else challenge("校园网登录尚未通过，请重新验证。")
  }

  private suspend fun guarded(action: suspend () -> ZfwSessionState): ZfwSessionState =
      withLocalDiagnostics(
          DiagnosticModule.RECHARGE,
          DiagnosticOperation.PREPARE_RECHARGE,
          outcome = {
            if (it is ZfwSessionState.Ready) DiagnosticOutcome.SUCCESS
            else DiagnosticOutcome.BLOCKED
          },
      ) {
        val result =
            try {
              action()
            } catch (e: CancellationException) {
              throw e
            } catch (error: ZfwReadFailure) {
              ZfwSessionState.Failed(error.reason)
            } catch (error: Exception) {
              recordDiagnostic(DiagnosticStage.IDENTITY, DiagnosticOutcome.FAILURE, error = error)
              ZfwSessionState.Failed("校园网暂时无法验证，请稍后刷新。")
            }
        mutableState.value = result
        result
      }

  fun close() {
    client?.close()
    client = null
    loginForm = null
    pendingLogin = null
  }
}

private suspend fun rememberedZfwCredential(account: String): CampusCredential? {
  if (!CredentialStore.isRememberUsername() || account.isBlank()) return null
  val principal = CredentialStore.getUsername()?.takeIf(String::isNotBlank) ?: return null
  val owner = CredentialStore.getAccountId() ?: principal
  if (owner != account) return null
  val stored =
      try {
        CampusSecureCredentials.load(principal)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      }
  return stored
      ?.takeIf { it.username == principal && it.password.isNotBlank() }
      ?.let { CampusCredential(account, it.password) }
}

object CampusFinanceSession {
  suspend fun ensurePaymentSession(): ZfwSessionState = current().ensurePaymentSession()

  private var key: String? = null
  private var session: ZfwSessionCoordinator? = null

  fun current(): ZfwSessionCoordinator {
    val account = LocalAuthSessionStore.get()?.user?.schoolid.orEmpty()
    val next = "$account:${ConnectionRuntime.currentMode()}"
    if (next != key || session == null) {
      reset()
      key = next
      session =
          ZfwSessionCoordinator(
              // ZFW now lives on its direct origin; keep its cookies in the direct scope so the
              // portal CAS bridge can reuse the same unified-auth session in both connection modes.
              {
                LocalUpstreamClientProvider.newFinanceClient(
                    LocalCookieStore.storage(ConnectionMode.DIRECT)
                )
              },
              {
                TdCampusCredentials.current()
                    ?.takeIf { it.username == account }
                    ?.let { CampusCredential(it.username, it.password) }
                    ?: rememberedZfwCredential(account)
              },
              beginVerification = {
                // Start a fresh ZFW identity session only; preserve the separate BUCC/CAS session.
                clearZfwIdentityCookies(
                    LocalCookieStore.storage(ConnectionMode.DIRECT),
                    ConnectionMode.DIRECT,
                )
              },
          )
    }
    return session!!
  }

  fun reset() {
    session?.close()
    session = null
    key = null
  }
}

internal suspend fun clearZfwIdentityCookies(storage: CookiesStorage, mode: ConnectionMode) {
  val zfwUrl = "https://zfw.buaa.edu.cn/"
  val target = Url(if (mode.usesWebVpnGateway) LocalWebVpnSupport.toWebVpnUrl(zfwUrl) else zfwUrl)
  val cookies =
      storage.get(target).filter { cookie ->
        if (!mode.usesWebVpnGateway) {
          cookie.domain?.trimStart('.')?.equals(target.host, ignoreCase = true) == true
        } else {
          val path = cookie.path ?: return@filter false
          val zfwPath = target.encodedPath.trimEnd('/')
          path == zfwPath || path.startsWith("$zfwPath/")
        }
      }
  cookies.forEach { storage.addCookie(target, it.copy(maxAge = 0)) }
}

/** Yii's image URL alone does not generate a new code: invoke its refresh action first. */
internal suspend fun refreshZfwCaptcha(
    client: HttpClient,
    imageUrl: String,
    refreshUrl: String? = null,
): String {
  val originalUrl =
      normalizeZfwUrl(imageUrl, setOf("/site/captcha", "/pay/captcha"))
          ?: throw IllegalArgumentException("Invalid ZFW captcha URL")
  val original = Url(originalUrl)
  val refreshTarget =
      refreshUrl?.let {
        normalizeZfwUrl(it, setOf(original.encodedPath))
            ?: throw IllegalArgumentException("Invalid ZFW captcha refresh URL")
      } ?: "https://zfw.buaa.edu.cn${original.encodedPath}?refresh=1"
  val refresh = Url(refreshTarget)
  require(refresh.parameters.getAll("refresh")?.singleOrNull() == "1")
  val response =
      readZfwPage(client, refreshTarget) {
        parameter("_", Clock.System.now().toEpochMilliseconds())
        header(HttpHeaders.CacheControl, "no-cache")
        header("X-Requested-With", "XMLHttpRequest")
      }
  check(response.status.isSuccess())
  val next =
      Json.parseToJsonElement(response.bodyAsText()).jsonObject["url"]?.jsonPrimitive?.contentOrNull
          ?: error("Captcha refresh URL missing")
  return normalizeZfwUrl(next, setOf(original.encodedPath))
      ?: throw IllegalArgumentException("Invalid ZFW captcha response URL")
}
