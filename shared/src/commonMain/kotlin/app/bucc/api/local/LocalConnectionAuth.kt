package app.bucc.api.local

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.ModeScopedSessionStore
import app.bucc.api.ResettableSharedInstance
import app.bucc.api.auth.ApiCallException
import app.bucc.api.auth.AuthServiceBackend
import app.bucc.api.auth.CaptchaRequiredClientException
import app.bucc.api.auth.SessionStatusResponse
import app.bucc.api.auth.UserServiceBackend
import app.bucc.api.auth.toUserFacingApiException
import app.bucc.api.auth.userFacingMessageForCode
import app.bucc.api.core.getCampusCardEngine
import app.bucc.api.core.getDefaultEngine
import app.bucc.api.core.getFinanceCardEngine
import app.bucc.api.core.getFinanceDirectEngine
import app.bucc.connection.AuthenticationChallenge
import app.bucc.connection.AuthenticationDiagnostic
import app.bucc.connection.AuthenticationDiagnostics
import app.bucc.connection.AuthenticationPath
import app.bucc.connection.AuthenticationStage
import app.bucc.model.dto.CaptchaInfo
import app.bucc.model.dto.LoginPreloadResponse
import app.bucc.model.dto.LoginRequest
import app.bucc.model.dto.LoginResponse
import app.bucc.model.dto.UserData
import app.bucc.model.dto.UserInfo
import app.bucc.model.dto.UserInfoResponse
import app.bucc.repository.ScheduleSnapshotStorage
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocalAuthSession(
    val username: String,
    val user: UserData,
    val authenticatedAt: String,
    val lastActivity: String,
)

object LocalAuthSessionStore {
  private const val KEY_LOCAL_AUTH_SESSION = "local_auth_session"
  private var _settings: Settings? = null
  var settings: Settings
    get() = _settings ?: Settings().also { _settings = it }
    set(value) {
      _settings = value
    }

  private val json = Json { ignoreUnknownKeys = true }

  fun save(session: LocalAuthSession) {
    settings.putString(
        ModeScopedSessionStore.scopedKey(KEY_LOCAL_AUTH_SESSION),
        json.encodeToString(session),
    )
  }

  fun get(): LocalAuthSession? {
    val stored = settings.getStringOrNull(ModeScopedSessionStore.scopedKey(KEY_LOCAL_AUTH_SESSION))
    return stored?.let { json.decodeFromString<LocalAuthSession>(it) }
  }

  fun clear() {
    settings.remove(ModeScopedSessionStore.scopedKey(KEY_LOCAL_AUTH_SESSION))
  }

  fun clearAllScopes() {
    ConnectionMode.entries.forEach { mode ->
      settings.remove(ModeScopedSessionStore.scopedKey(KEY_LOCAL_AUTH_SESSION, mode))
    }
  }
}

internal object LocalCookieStore {
  private const val KEY_LOCAL_COOKIES = "local_connection_cookies"
  private val storageMutexes = ConnectionMode.entries.associateWith { Mutex() }

  private data class ClearEpoch(val generation: Long = 0L, val activeClears: Int = 0)

  private val clearEpochs = ConnectionMode.entries.associateWith { MutableStateFlow(ClearEpoch()) }
  private var _settings: Settings? = null
  var settings: Settings
    get() = _settings ?: Settings().also { _settings = it }
    set(value) {
      _settings = value
    }

  private val json = Json { ignoreUnknownKeys = true }

  internal fun mutex(mode: ConnectionMode): Mutex = storageMutexes.getValue(mode)

  internal fun beginMutation(mode: ConnectionMode): Long? =
      clearEpochs.getValue(mode).value.let { epoch ->
        epoch.generation.takeIf { epoch.activeClears == 0 }
      }

  private fun isCurrent(mode: ConnectionMode, expectedEpoch: Long): Boolean =
      clearEpochs.getValue(mode).value.let {
        it.activeClears == 0 && it.generation == expectedEpoch
      }

  private fun snapshots(): ScheduleSnapshotStorage =
      ScheduleSnapshotStorage(settings, namespace = "local_cookie_snapshots")

  fun storage(
      mode: ConnectionMode = ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT
  ): PersistentLocalCookieStorage = PersistentLocalCookieStorage(mode)

  fun clear(mode: ConnectionMode = ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT) {
    val epoch = clearEpochs.getValue(mode)
    epoch.getAndUpdate {
      it.copy(generation = it.generation + 1L, activeClears = it.activeClears + 1)
    }
    try {
      clearStorage(mode)
    } finally {
      epoch.getAndUpdate { it.copy(activeClears = it.activeClears - 1) }
    }
  }

  private fun clearStorage(mode: ConnectionMode) {
    snapshots().clear(mode.storageKey)
    settings.remove(ModeScopedSessionStore.scopedKey(KEY_LOCAL_COOKIES, mode))
  }

  fun clearAllScopes() {
    ConnectionMode.entries.forEach { mode -> clear(mode) }
  }

  internal fun load(mode: ConnectionMode): MutableList<StoredCookieRecord> {
    val storage = snapshots()
    val snapshot =
        try {
          storage.read(mode.storageKey)
        } catch (_: Exception) {
          runCatching { storage.clear(mode.storageKey) }
          return mutableListOf()
        }
    val stored =
        snapshot
            ?: runCatching {
                  settings.getStringOrNull(
                      ModeScopedSessionStore.scopedKey(KEY_LOCAL_COOKIES, mode)
                  )
                }
                .getOrNull()
    if (stored.isNullOrBlank()) return mutableListOf()
    return try {
      json.decodeFromString<List<StoredCookieRecord>>(stored).toMutableList()
    } catch (_: Exception) {
      if (snapshot != null) runCatching { storage.clear(mode.storageKey) }
      mutableListOf()
    }
  }

  internal fun save(
      mode: ConnectionMode,
      records: List<StoredCookieRecord>,
      expectedEpoch: Long,
  ): Boolean {
    if (!isCurrent(mode, expectedEpoch)) return false
    if (records.isEmpty()) {
      clearStorage(mode)
      return isCurrent(mode, expectedEpoch)
    }
    try {
      snapshots().write(mode.storageKey, json.encodeToString(records))
      settings.remove(ModeScopedSessionStore.scopedKey(KEY_LOCAL_COOKIES, mode))
    } finally {
      if (!isCurrent(mode, expectedEpoch)) runCatching { clearStorage(mode) }
    }
    return isCurrent(mode, expectedEpoch)
  }
}

@Serializable
internal data class StoredCookieRecord(
    val cookie: Cookie,
    val createdAtEpochMillis: Long,
)

internal class PersistentLocalCookieStorage(private val mode: ConnectionMode) : CookiesStorage {
  private val mutex = LocalCookieStore.mutex(mode)

  override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
    mutex.withLock {
      val epoch = LocalCookieStore.beginMutation(mode) ?: return@withLock
      val records = LocalCookieStore.load(mode)
      val normalized =
          cookie.copy(
              domain = (cookie.domain ?: requestUrl.host).lowercase(),
              path = cookie.path ?: defaultCookiePath(requestUrl.encodedPath),
          )
      val key = cookieKey(normalized)
      records.removeAll { cookieKey(it.cookie) == key }
      if ((normalized.maxAge ?: -1L) != 0L) {
        records += StoredCookieRecord(normalized, nowMillis())
      }
      LocalCookieStore.save(mode, records, epoch)
    }
  }

  override suspend fun get(requestUrl: Url): List<Cookie> =
      mutex.withLock {
        val epoch = LocalCookieStore.beginMutation(mode) ?: return@withLock emptyList()
        val now = nowMillis()
        val records = LocalCookieStore.load(mode)
        val active = mutableListOf<StoredCookieRecord>()
        val result = mutableListOf<Cookie>()
        records.forEach { record ->
          val cookie = record.cookie
          if (isExpired(cookie, record.createdAtEpochMillis, now)) {
            return@forEach
          }
          active += record
          if (!domainMatches(requestUrl.host, cookie.domain ?: requestUrl.host)) return@forEach
          if (!pathMatches(requestUrl.encodedPath, cookie.path ?: "/")) return@forEach
          if (cookie.secure && !requestUrl.protocol.name.equals("https", ignoreCase = true))
              return@forEach
          result += cookie.copy(expires = cookie.expires?.timestamp?.let(::GMTDate))
        }
        if (LocalCookieStore.save(mode, active, epoch)) result else emptyList()
      }

  suspend fun clear() {
    mutex.withLock { LocalCookieStore.clear(mode) }
  }

  /** Removes only one host's cookies so a stale campus-card session can be re-established. */
  suspend fun clearHost(host: String) {
    mutex.withLock {
      val epoch = LocalCookieStore.beginMutation(mode) ?: return@withLock
      val records = LocalCookieStore.load(mode)
      val kept = records.filterNot { domainMatches(host, it.cookie.domain ?: "") }
      LocalCookieStore.save(mode, kept, epoch)
    }
  }

  override fun close() = Unit

  private fun isExpired(cookie: Cookie, createdAt: Long, now: Long): Boolean {
    val expiresAt = cookie.expires?.timestamp
    if (expiresAt != null && expiresAt <= now) {
      return true
    }
    val maxAge = cookie.maxAge
    return maxAge != null && maxAge >= 0 && now >= createdAt + maxAge * 1000L
  }

  private fun cookieKey(cookie: Cookie): String =
      "${cookie.domain.orEmpty()}|${cookie.path.orEmpty()}|${cookie.name}"

  private fun defaultCookiePath(requestPath: String): String {
    val normalizedRequestPath = requestPath.ifBlank { "/" }
    if (!normalizedRequestPath.startsWith("/")) return "/"
    if (normalizedRequestPath == "/") return "/"
    val lastSlash = normalizedRequestPath.lastIndexOf('/')
    return if (lastSlash <= 0) "/" else normalizedRequestPath.substring(0, lastSlash)
  }

  private fun domainMatches(host: String, domain: String): Boolean {
    val normalizedHost = host.lowercase()
    val normalizedDomain = domain.trimStart('.').lowercase()
    return normalizedHost == normalizedDomain || normalizedHost.endsWith(".$normalizedDomain")
  }

  private fun pathMatches(requestPath: String, cookiePath: String): Boolean {
    val normalizedRequestPath = requestPath.ifBlank { "/" }
    val normalizedCookiePath = if (cookiePath.endsWith("/")) cookiePath else "$cookiePath/"
    return normalizedRequestPath.startsWith(normalizedCookiePath.removeSuffix("/"))
  }
}

internal object LocalUpstreamClientProvider {
  private fun currentCookieMode(): ConnectionMode =
      ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT

  internal var clientFactory: (Boolean) -> HttpClient = { followRedirects ->
    buildLocalUpstreamClient(
        followRedirects = followRedirects,
        cookieStorage = LocalCookieStore.storage(currentCookieMode()),
    )
  }
  internal var libBookClientFactory: (Boolean) -> HttpClient = { followRedirects ->
    buildLocalUpstreamClient(
        followRedirects = followRedirects,
        cookieStorage = LocalCookieStore.storage(currentCookieMode()),
        engine = getLibBookHttpClientEngine(),
    )
  }
  internal var isolatedClientFactory: (Boolean, CookiesStorage) -> HttpClient =
      { followRedirects, cookieStorage ->
        buildLocalUpstreamClient(followRedirects = followRedirects, cookieStorage = cookieStorage)
      }

  private val sharedClient =
      ResettableSharedInstance(factory = { clientFactory(true) }, disposer = HttpClient::close)
  private val libBookSharedClient =
      ResettableSharedInstance(
          factory = { libBookClientFactory(true) },
          disposer = HttpClient::close,
      )

  fun shared(): HttpClient = sharedClient.getOrCreate()

  fun libBookShared(): HttpClient = libBookSharedClient.getOrCreate()

  fun newNoRedirectClient(): HttpClient = clientFactory(false)

  fun newCampusCardClient(
      cookieStorage: CookiesStorage = LocalCookieStore.storage(currentCookieMode()),
  ): HttpClient =
      buildLocalUpstreamClient(
          followRedirects = false,
          cookieStorage = cookieStorage,
          engine = getCampusCardEngine(),
      )

  fun newLibBookNoRedirectClient(): HttpClient = libBookClientFactory(false)

  /** Finance pages stay off WebVPN and the physical campus proxy. */
  fun newCardClient(
      cookieStorage: CookiesStorage = LocalCookieStore.storage(currentCookieMode()),
  ): HttpClient =
      buildLocalUpstreamClient(
          followRedirects = false,
          cookieStorage = cookieStorage,
          engine = getFinanceCardEngine(),
      )

  fun newFinanceClient(
      cookieStorage: CookiesStorage = LocalCookieStore.storage(currentCookieMode()),
      followRedirects: Boolean = false,
  ): HttpClient =
      buildLocalUpstreamClient(
          followRedirects = followRedirects,
          cookieStorage = cookieStorage,
          engine = getFinanceDirectEngine(),
      )

  fun newClient(
      cookieStorage: CookiesStorage,
      followRedirects: Boolean = true,
  ): HttpClient = isolatedClientFactory(followRedirects, cookieStorage)

  fun reset() {
    sharedClient.reset()
    libBookSharedClient.reset()
    clientFactory = { followRedirects ->
      buildLocalUpstreamClient(
          followRedirects = followRedirects,
          cookieStorage = LocalCookieStore.storage(currentCookieMode()),
      )
    }
    libBookClientFactory = { followRedirects ->
      buildLocalUpstreamClient(
          followRedirects = followRedirects,
          cookieStorage = LocalCookieStore.storage(currentCookieMode()),
          engine = getLibBookHttpClientEngine(),
      )
    }
    isolatedClientFactory = { followRedirects, cookieStorage ->
      buildLocalUpstreamClient(followRedirects = followRedirects, cookieStorage = cookieStorage)
    }
  }
}

private fun buildLocalUpstreamClient(
    followRedirects: Boolean,
    cookieStorage: CookiesStorage,
    engine: HttpClientEngine = getDefaultEngine(),
): HttpClient {
  return HttpClient(engine) {
    this.followRedirects = followRedirects
    install(ContentNegotiation) {
      json(
          Json {
            ignoreUnknownKeys = true
            isLenient = true
          }
      )
    }
    install(Logging) { level = LogLevel.NONE }
    install(HttpCookies) { storage = cookieStorage }
    install(HttpTimeout) {
      requestTimeoutMillis = 30_000
      connectTimeoutMillis = 10_000
      socketTimeoutMillis = 30_000
    }
  }
}

private val localConnectionAuthJson = Json { ignoreUnknownKeys = true }

internal sealed interface LocalConnectionSessionValidationState {
  data class Valid(val session: LocalAuthSession) : LocalConnectionSessionValidationState

  data object Invalid : LocalConnectionSessionValidationState

  /** 服务端暂时不可用（5xx），不应清除会话。 */
  data object TransientError : LocalConnectionSessionValidationState
}

private suspend fun <T> withUcConnectivityError(request: suspend () -> T): T =
    try {
      request()
    } catch (error: CancellationException) {
      throw error
    } catch (error: ApiCallException) {
      throw error
    } catch (error: Exception) {
      throw ApiCallException(
          message = "无法连接统一认证服务（uc.buaa.edu.cn）。请检查 VPN 连接和访问权限，或切换连接模式后重试。",
          status = HttpStatusCode.ServiceUnavailable,
          code = "auth_upstream_unreachable",
          cause = error,
      )
    }

internal suspend fun validateLocalConnectionSession(
    expectedUsername: String? = LocalAuthSessionStore.get()?.username,
): LocalConnectionSessionValidationState {
  val response = withUcConnectivityError {
    LocalUpstreamClientProvider.shared().get(localUcStatusUrl()) {
      header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
      header("X-Requested-With", "XMLHttpRequest")
    }
  }
  if (response.status != HttpStatusCode.OK) {
    return if (response.status.value in 500..599) {
      LocalConnectionSessionValidationState.TransientError
    } else {
      LocalConnectionSessionValidationState.Invalid
    }
  }

  val body = response.bodyAsText()
  if (!body.trimStart().startsWith("{")) {
    return LocalConnectionSessionValidationState.Invalid
  }

  val payload = localConnectionAuthJson.decodeFromString<UserInfoResponse>(body)
  val user = payload.data ?: return LocalConnectionSessionValidationState.Invalid
  val identity =
      user.schoolid?.trim()?.takeIf(String::isNotBlank)
          ?: user.username?.trim()?.takeIf(String::isNotBlank)
  if (payload.code != 0 || identity == null) {
    return LocalConnectionSessionValidationState.Invalid
  }

  if (
      expectedUsername != null && expectedUsername != identity && expectedUsername != user.username
  ) {
    throw ApiCallException("认证返回的账号与当前账号不一致，请手动登录", code = "auth_identity_mismatch")
  }
  val session =
      LocalAuthSession(
          username = identity,
          user = UserData(name = user.name.orEmpty(), schoolid = identity),
          authenticatedAt = LocalAuthSessionStore.get()?.authenticatedAt ?: nowIsoString(),
          lastActivity = nowIsoString(),
      )
  LocalAuthSessionStore.save(session)
  return LocalConnectionSessionValidationState.Valid(session)
}

internal suspend fun resolveLocalBusinessAuthenticationFailure(
    fallbackCode: String,
    fallbackStatus: HttpStatusCode = HttpStatusCode.BadGateway,
): ApiCallException {
  val validation = runCatching { validateLocalConnectionSession() }.getOrNull()
  return if (validation == LocalConnectionSessionValidationState.Invalid) {
    clearLocalConnectionSession(notifyExpiry = true)
    localUnauthenticatedApiException()
  } else {
    ApiCallException(
        message = userFacingMessageForCode(fallbackCode, fallbackStatus),
        status = fallbackStatus,
        code = fallbackCode,
    )
  }
}

internal class LocalAuthServiceBackend : AuthServiceBackend {
  private class PendingCaptchaChallenge(
      val mode: ConnectionMode?,
      val postUrl: String,
      val html: String,
      val execution: String,
      val captcha: CaptchaInfo,
  )

  private val pendingCaptchaChallenge = MutableStateFlow<PendingCaptchaChallenge?>(null)

  internal fun resetCaptchaChallenge() {
    pendingCaptchaChallenge.value = null
  }

  private fun authUrl(url: String): String = localUpstreamUrl(url)

  override fun hasPersistedSession(): Boolean = LocalAuthSessionStore.get() != null

  override fun applyStoredSession() = Unit

  override fun clearStoredSession() {
    resetCaptchaChallenge()
    clearLocalConnectionSession()
  }

  override suspend fun preloadLoginState(): Result<LoginPreloadResponse> {
    pendingCaptchaChallenge.value = null
    val noRedirectClient = LocalUpstreamClientProvider.newNoRedirectClient()
    return try {
      val initialPage = noRedirectClient.get(loginUrl())
      val response =
          if (initialPage.status.value in 300..399) {
            activateUcLoginBeforeCredentials()
          } else preferUcServiceChallenge(initialPage)
      traceAuthentication(AuthenticationStage.PRELOAD, response.status.value)
      if (!isSsoLoginPage(response)) {
        return when (val validation = validateLocalConnectionSession()) {
          is LocalConnectionSessionValidationState.Valid ->
              Result.success(
                  LoginPreloadResponse(captchaRequired = false, userData = validation.session.user)
              )
          LocalConnectionSessionValidationState.Invalid ->
              Result.success(LoginPreloadResponse(captchaRequired = false))
          LocalConnectionSessionValidationState.TransientError ->
              Result.failure(
                  ApiCallException(
                      "认证服务响应超时，请稍后重试",
                      HttpStatusCode.ServiceUnavailable,
                      "auth_upstream_timeout",
                  )
              )
        }
      }

      val loginPageHtml = response.bodyAsText()
      val execution = LocalCasParser.extractExecution(loginPageHtml).takeIf { it.isNotBlank() }
      val captchaInfo = LocalCasParser.detectCaptcha(loginPageHtml, captchaUrl())
      val hydratedCaptcha =
          captchaInfo?.let { info -> info.withBase64Image(fetchCaptchaImage(info.id)) }
      if (execution != null && hydratedCaptcha != null && isSsoLoginPage(response)) {
        pendingCaptchaChallenge.value =
            PendingCaptchaChallenge(
                ConnectionRuntime.currentMode(),
                response.call.request.url.toString(),
                loginPageHtml,
                execution,
                hydratedCaptcha,
            )
      }

      Result.success(
          LoginPreloadResponse(
              captchaRequired = hydratedCaptcha != null,
              captcha = hydratedCaptcha,
              execution = execution,
          )
      )
    } catch (e: Exception) {
      resetCaptchaChallenge()
      Result.failure(e.toUserFacingApiException("登录状态加载失败，请稍后重试"))
    } finally {
      noRedirectClient.close()
    }
  }

  override suspend fun login(
      username: String,
      password: String,
      captcha: String?,
      execution: String?,
  ): Result<LoginResponse> {
    val noRedirectClient = LocalUpstreamClientProvider.newNoRedirectClient()
    var preserveCaptchaRoute = false
    var serviceChallengeAfterCredentials = false
    return try {
      val cachedChallenge =
          pendingCaptchaChallenge
              .getAndUpdate { null }
              ?.takeIf {
                !captcha.isNullOrBlank() &&
                    execution == it.execution &&
                    ConnectionRuntime.currentMode() == it.mode
              }
      val loginPageResponse =
          if (cachedChallenge != null) {
            null
          } else {
            val initialLoginPage = noRedirectClient.get(loginUrl())
            // Resolve the service challenge before the single credential POST.
            if (initialLoginPage.status.value in 300..399) activateUcLoginBeforeCredentials()
            else preferUcServiceChallenge(initialLoginPage)
          }
      if (
          loginPageResponse != null &&
              loginPageResponse.status != HttpStatusCode.OK &&
              loginPageResponse.status.value !in 300..399
      ) {
        return Result.failure(ApiCallException("登录失败，请稍后重试"))
      }

      if (
          cachedChallenge != null ||
              (loginPageResponse != null && isSsoLoginPage(loginPageResponse))
      ) {
        val loginPageHtml = cachedChallenge?.html ?: requireNotNull(loginPageResponse).bodyAsText()
        val postUrl =
            cachedChallenge?.postUrl
                ?: LocalCasParser.loginFormUrl(
                    loginPageHtml,
                    requireNotNull(loginPageResponse).call.request.url.toString(),
                )
        LocalCasParser.extractTipText(loginPageHtml)?.let { tip ->
          return Result.failure(ApiCallException(tip))
        }

        val actualExecution =
            LocalCasParser.extractExecution(loginPageHtml).takeIf { it.isNotBlank() }
                ?: return Result.failure(ApiCallException("登录页面已更新，请刷新登录页后重试"))
        val request =
            LoginRequest(
                username = username,
                password = password,
                captcha = captcha,
                execution = actualExecution,
            )
        val captchaInfo =
            cachedChallenge?.captcha ?: LocalCasParser.detectCaptcha(loginPageHtml, captchaUrl())
        if (captchaInfo != null && cachedChallenge == null) {
          val hydratedCaptcha = captchaInfo.withBase64Image(fetchCaptchaImage(captchaInfo.id))
          pendingCaptchaChallenge.value =
              PendingCaptchaChallenge(
                  ConnectionRuntime.currentMode(),
                  postUrl,
                  loginPageHtml,
                  actualExecution,
                  hydratedCaptcha,
              )
          preserveCaptchaRoute = true
          return Result.failure(
              CaptchaRequiredClientException(hydratedCaptcha, actualExecution, "需要验证码验证")
          )
        }
        if (cachedChallenge == null && !captcha.isNullOrBlank()) {
          return Result.failure(ApiCallException("验证码已失效，请刷新登录页后重试"))
        }

        val parameters =
            if (captchaInfo != null || !captcha.isNullOrBlank()) {
              LocalCasParser.buildCaptchaLoginParameters(request, actualExecution)
            } else {
              LocalCasParser.buildCasLoginParameters(loginPageHtml, request)
            }
        traceAuthentication(AuthenticationStage.CREDENTIAL_SUBMIT, submissions = 1)
        val submitResponse = noRedirectClient.post(postUrl) { setBody(FormDataContent(parameters)) }
        traceAuthentication(
            AuthenticationStage.CREDENTIAL_SUBMIT,
            submitResponse.status.value,
            submissions = 1,
        )
        followRedirectsAndCheckError(submitResponse, noRedirectClient)
        val activation = activateUcLogin()
        traceAuthentication(
            AuthenticationStage.UC_ACTIVATION,
            activation.status.value,
            submissions = 1,
        )
        serviceChallengeAfterCredentials =
            isSsoLoginPage(activation) &&
                LocalCasParser.extractExecution(activation.bodyAsText()).isNotBlank()
      }

      traceAuthentication(
          AuthenticationStage.IDENTITY_CHECK,
          submissions =
              if (
                  loginPageResponse != null && isSsoLoginPage(loginPageResponse) ||
                      cachedChallenge != null
              )
                  1
              else 0,
      )
      if (serviceChallengeAfterCredentials)
          traceAuthentication(
              AuthenticationStage.CHALLENGE,
              challenge = AuthenticationChallenge.SERVICE,
              submissions = 1,
          )
      when (val validation = validateLocalConnectionSession(expectedUsername = username)) {
        is LocalConnectionSessionValidationState.Valid -> {
          Result.success(
              LoginResponse(
                  user = validation.session.user,
                  accessToken = "",
                  refreshToken = "",
                  accessTokenExpiresAt = "",
                  refreshTokenExpiresAt = "",
              )
          )
        }
        LocalConnectionSessionValidationState.Invalid ->
            Result.failure(
                ApiCallException(
                    message =
                        if (serviceChallengeAfterCredentials) "网关已连接，统一认证仍需验证；请手动登录"
                        else
                            userFacingMessageForCode(
                                "unauthenticated",
                                HttpStatusCode.Unauthorized,
                            ),
                    status = HttpStatusCode.Unauthorized,
                    code =
                        if (serviceChallengeAfterCredentials) "auth_service_challenge_required"
                        else "unauthenticated",
                )
            )
        LocalConnectionSessionValidationState.TransientError ->
            Result.failure(
                ApiCallException(
                    message = "认证服务响应超时，请稍后重试",
                    status = HttpStatusCode.ServiceUnavailable,
                    code = "auth_upstream_timeout",
                )
            )
      }
    } catch (e: Exception) {
      Result.failure(e.toUserFacingApiException("登录失败，请稍后重试"))
    } finally {
      if (!preserveCaptchaRoute) {
        pendingCaptchaChallenge.value = null
      }
      noRedirectClient.close()
    }
  }

  override suspend fun getAuthStatus(): Result<SessionStatusResponse> {
    val storedSession =
        LocalAuthSessionStore.get()
            ?: return Result.failure(
                ApiCallException(
                    message =
                        userFacingMessageForCode("unauthenticated", HttpStatusCode.Unauthorized),
                    status = HttpStatusCode.Unauthorized,
                    code = "unauthenticated",
                )
            )

    return try {
      when (val validation = validateLocalConnectionSession()) {
        is LocalConnectionSessionValidationState.Valid -> {
          val refreshed = validation.session.copy(authenticatedAt = storedSession.authenticatedAt)
          LocalAuthSessionStore.save(refreshed)
          Result.success(
              SessionStatusResponse(
                  user = refreshed.user,
                  lastActivity = refreshed.lastActivity,
                  authenticatedAt = refreshed.authenticatedAt,
              )
          )
        }
        LocalConnectionSessionValidationState.Invalid -> {
          clearLocalConnectionSession(notifyExpiry = true)
          Result.failure(
              ApiCallException(
                  message =
                      userFacingMessageForCode("unauthenticated", HttpStatusCode.Unauthorized),
                  status = HttpStatusCode.Unauthorized,
                  code = "unauthenticated",
              )
          )
        }
        LocalConnectionSessionValidationState.TransientError ->
            Result.failure(
                ApiCallException(
                    message = "认证服务响应超时，请稍后重试",
                    status = HttpStatusCode.ServiceUnavailable,
                    code = "auth_upstream_timeout",
                )
            )
      }
    } catch (e: Exception) {
      val wrapped = e.toUserFacingApiException("认证服务响应超时，请稍后重试")
      if (wrapped is ApiCallException && wrapped.code == null) {
        Result.failure(
            ApiCallException(
                message = wrapped.message ?: "认证服务响应超时，请稍后重试",
                status = wrapped.status,
                code = "auth_upstream_timeout",
            )
        )
      } else {
        Result.failure(wrapped)
      }
    }
  }

  override suspend fun logout(): Result<Unit> {
    return try {
      runCatching { LocalUpstreamClientProvider.shared().get(logoutUrl()) }
      clearStoredSession()
      Result.success(Unit)
    } catch (e: Exception) {
      clearStoredSession()
      Result.failure(e.toUserFacingApiException("注销时出现异常，本地登录状态已清除"))
    }
  }

  private fun isSsoLoginPage(response: HttpResponse): Boolean {
    val upstream = Url(LocalWebVpnSupport.fromWebVpnUrl(response.call.request.url.toString()))
    return response.status == HttpStatusCode.OK &&
        upstream.host == "sso.buaa.edu.cn" &&
        upstream.encodedPath == "/login"
  }

  private suspend fun preferUcServiceChallenge(initialPage: HttpResponse): HttpResponse {
    if (!isSsoLoginPage(initialPage)) return initialPage
    // A bare CAS form may lead to an unrelated default landing page. Resolve the UC service
    // before the single credential POST, so its execution and target belong together.
    return activateUcLoginBeforeCredentials()
  }

  private suspend fun activateUcLoginBeforeCredentials(): HttpResponse = activateUcLogin()

  private suspend fun activateUcLogin(): HttpResponse = withUcConnectivityError {
    val client = LocalUpstreamClientProvider.newNoRedirectClient()
    try {
      var response = client.get(localUcActivateUrl())
      var redirects = 0
      while (response.status.value in 300..399) {
        val location = response.headers[HttpHeaders.Location] ?: break
        if (++redirects > 12) throw ApiCallException("认证跳转未完成，请手动登录", code = "auth_redirect_limit")
        response = client.get(resolveRedirectUrl(response.call.request.url, location))
      }
      response
    } finally {
      client.close()
    }
  }

  @OptIn(ExperimentalEncodingApi::class)
  private suspend fun fetchCaptchaImage(captchaId: String): String? {
    val response = LocalUpstreamClientProvider.shared().get("${captchaUrl()}?captchaId=$captchaId")
    if (response.status != HttpStatusCode.OK) {
      return null
    }
    val bytes = response.body<ByteArray>()
    return "data:image/jpeg;base64,${Base64.encode(bytes)}"
  }

  private suspend fun followRedirectsAndCheckError(
      initialResponse: HttpResponse,
      noRedirectClient: HttpClient,
  ) {
    var currentResponse = initialResponse
    var passwordExpiryIgnored = false
    var redirectCount = 0
    while (true) {
      while (currentResponse.status.value in 300..399) {
        if (++redirectCount > 12)
            throw ApiCallException("认证跳转未完成，请手动登录", code = "auth_redirect_limit")
        val location = currentResponse.headers[HttpHeaders.Location] ?: break
        currentResponse =
            noRedirectClient.get(resolveRedirectUrl(currentResponse.call.request.url, location))
      }

      val bodyText = runCatching { currentResponse.bodyAsText() }.getOrNull().orEmpty()
      if (LocalCasParser.isIgnorablePasswordExpiryPage(bodyText)) {
        if (passwordExpiryIgnored) {
          throw ApiCallException("登录失败，请稍后重试")
        }
        val execution = LocalCasParser.extractExecution(bodyText)
        if (execution.isBlank()) {
          throw ApiCallException("登录失败，请稍后重试")
        }
        passwordExpiryIgnored = true
        currentResponse =
            noRedirectClient.post(stripQuery(currentResponse.call.request.url.toString())) {
              setBody(
                  FormDataContent(LocalCasParser.buildIgnorePasswordExpiryParameters(execution))
              )
            }
        continue
      }

      val finalUrl = currentResponse.call.request.url.toString()
      if ("exception.message=" in finalUrl) {
        throw ApiCallException(finalUrl.substringAfter("exception.message=").substringBefore("&"))
      }
      val loginError =
          LocalCasParser.findLoginError(bodyText) ?: LocalCasParser.extractTipText(bodyText)
      if (currentResponse.status == HttpStatusCode.Unauthorized || !loginError.isNullOrBlank()) {
        throw ApiCallException(loginError ?: "账号或密码错误，请重试")
      }
      if (bodyText.contains("input name=\"execution\"")) {
        throw ApiCallException("账号或密码错误，请重试")
      }
      return
    }
  }

  private fun stripQuery(url: String): String = url.substringBefore("?")

  private fun secureAuthRedirect(location: String): String {
    val target = Url(location)
    if (target.protocol != URLProtocol.HTTP) return location
    if (
        target.host !in setOf("sso.buaa.edu.cn", "uc.buaa.edu.cn") ||
            target.port != 80 ||
            !target.user.isNullOrEmpty() ||
            !target.password.isNullOrEmpty()
    ) {
      throw ApiCallException("认证返回不安全的跳转，请手动登录", code = "auth_insecure_redirect")
    }
    // UC advertises an HTTP SSO redirect on campus. Keep its service/query, but never downgrade
    // TLS.
    return URLBuilder(target)
        .apply {
          protocol = URLProtocol.HTTPS
          port = 443
        }
        .buildString()
  }

  private fun resolveRedirectUrl(currentUrl: Url, location: String): String {
    if (location.startsWith("http://") || location.startsWith("https://")) {
      return authUrl(secureAuthRedirect(location))
    }
    if (location.startsWith("//")) {
      return authUrl(secureAuthRedirect("${currentUrl.protocol.name}:$location"))
    }
    val authority =
        "${currentUrl.protocol.name}://${currentUrl.host}${if (currentUrl.specifiedPort != currentUrl.protocol.defaultPort) ":${currentUrl.specifiedPort}" else ""}"
    if (location.startsWith("/")) {
      return "$authority$location"
    }
    val basePath = currentUrl.encodedPath.substringBeforeLast('/', "")
    val separator = if (basePath.endsWith("/")) "" else "/"
    val relativePath = "$basePath$separator$location"
    return "$authority$relativePath"
  }

  private fun traceAuthentication(
      stage: AuthenticationStage,
      status: Int? = null,
      challenge: AuthenticationChallenge? = null,
      submissions: Int = 0,
  ) {
    AuthenticationDiagnostics.record(
        AuthenticationDiagnostic(
            ConnectionRuntime.currentMode(),
            if (ConnectionRuntime.currentMode() == ConnectionMode.WEBVPN)
                AuthenticationPath.OFFICIAL_GATEWAY
            else AuthenticationPath.DIRECT,
            stage,
            status,
            challenge,
            submissions,
        )
    )
  }

  private fun loginUrl(): String = authUrl("https://sso.buaa.edu.cn/login")

  private fun captchaUrl(): String = authUrl("https://sso.buaa.edu.cn/captcha")

  private fun logoutUrl(): String = authUrl("https://sso.buaa.edu.cn/logout")
}

private fun localUcUrl(url: String): String = localUpstreamUrl(url)

private fun localUcStatusUrl(): String = localUcUrl("https://uc.buaa.edu.cn/api/uc/status")

private fun localUcActivateUrl(): String =
    localUcUrl(
        "https://uc.buaa.edu.cn/api/login?target=https%3A%2F%2Fuc.buaa.edu.cn%2F%23%2Fuser%2Flogin"
    )

internal class LocalUserServiceBackend : UserServiceBackend {
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun getUserInfo(): Result<UserInfo> {
    return try {
      val response =
          LocalUpstreamClientProvider.shared()
              .get(localUcUrl("https://uc.buaa.edu.cn/api/uc/userinfo"))
      val body = response.bodyAsText()
      if (isUcSessionExpired(response, body)) {
        clearLocalConnectionSession(notifyExpiry = true)
        return Result.failure(localUnauthenticatedApiException())
      }
      if (response.status != HttpStatusCode.OK) {
        return Result.failure(
            ApiCallException(
                message = userFacingMessageForCode("user_info_failed", response.status),
                status = response.status,
                code = "user_info_failed",
            )
        )
      }

      val payload = json.decodeFromString<UserInfoResponse>(body)
      val info = payload.data
      if (payload.code != 0 || info == null) {
        return Result.failure(
            ApiCallException(
                message =
                    userFacingMessageForCode(
                        "user_info_failed",
                        HttpStatusCode.InternalServerError,
                    ),
                status = HttpStatusCode.InternalServerError,
                code = "user_info_failed",
            )
        )
      }

      Result.success(info)
    } catch (e: Exception) {
      Result.failure(e.toUserFacingApiException("用户信息查询失败，请稍后重试"))
    }
  }

  private fun isUcSessionExpired(response: HttpResponse, body: String): Boolean {
    if (response.status == HttpStatusCode.Unauthorized) return true
    if (localIsSsoUrl(response.call.request.url.toString())) return true
    val trimmed = body.trimStart()
    return trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
        trimmed.startsWith("<html", ignoreCase = true) ||
        body.contains("input name=\"execution\"") ||
        body.contains("统一身份认证", ignoreCase = true)
  }
}

internal fun clearLocalConnectionSession(notifyExpiry: Boolean = false) {
  val hadSession = LocalAuthSessionStore.get() != null
  LocalAuthSessionStore.clear()
  LocalCookieStore.clear()
  LocalUpstreamClientProvider.reset()
  if (notifyExpiry && hadSession) app.bucc.api.auth.LocalSessionExpiryEvents.notifyExpired()
}

internal fun localUnauthenticatedApiException(): ApiCallException =
    ApiCallException(
        message = userFacingMessageForCode("unauthenticated", HttpStatusCode.Unauthorized),
        status = HttpStatusCode.Unauthorized,
        code = "unauthenticated",
    )

private fun nowIsoString(): String = Instant.fromEpochMilliseconds(nowMillis()).toString()

private fun nowMillis(): Long = GMTDate().timestamp

private fun CaptchaInfo.withBase64Image(encodedImage: String?): CaptchaInfo =
    copy(base64Image = encodedImage ?: base64Image)

internal object LocalCasParser {
  private val executionRegex =
      Regex(
          """<input[^>]*name=["']execution["'][^>]*value=["']([^"']+)["'][^>]*>""",
          RegexOption.IGNORE_CASE,
      )
  private val tipRegex =
      Regex(
          """<div[^>]*class=["'][^"']*tip-text[^"']*["'][^>]*>([\s\S]*?)</div>""",
          RegexOption.IGNORE_CASE,
      )
  private val captchaRegex =
      Regex("""config\.captcha\s*=\s*\{\s*type:\s*['"]([^'"]+)['"],\s*id:\s*['"]([^'"]+)['"]""")
  private val inputRegex = Regex("""<input\b([^>]*)>""", RegexOption.IGNORE_CASE)
  private val attrRegex = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*["']([^"']*)["']""")

  fun loginFormUrl(html: String, pageUrl: String): String {
    val form =
        Regex("""<form\b([^>]*)>([\s\S]*?)</form>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .firstOrNull { extractExecution(it.value).isNotBlank() } ?: return pageUrl
    val action =
        attrRegex
            .findAll(form.groupValues[1])
            .firstOrNull { it.groupValues[1].equals("action", ignoreCase = true) }
            ?.groupValues
            ?.get(2)
            ?.replace("&amp;", "&")
            ?.trim()
            .orEmpty()
    if (action.isEmpty()) return pageUrl
    val page = Url(pageUrl)
    val authority = "${page.protocol.name}://${page.host}:${page.port}"
    val target =
        when {
          action.startsWith("//") -> "${page.protocol.name}:$action"
          action.startsWith("/") -> authority + action
          action.startsWith("?") -> pageUrl.substringBefore('?').substringBefore('#') + action
          "://" in action -> action
          else ->
              pageUrl.substringBefore('?').substringBefore('#').substringBeforeLast('/') +
                  "/" +
                  action
        }
    val url = Url(target)
    require(
        url.protocol == page.protocol &&
            url.host == page.host &&
            url.port == page.port &&
            url.user.isNullOrEmpty() &&
            url.password.isNullOrEmpty()
    ) {
      "auth_form_origin_mismatch"
    }
    return url.toString()
  }

  fun extractExecution(html: String): String =
      executionRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()

  fun detectCaptcha(html: String, captchaUrlBase: String): CaptchaInfo? {
    val match = captchaRegex.find(html) ?: return null
    val type = match.groupValues[1]
    val id = match.groupValues[2]
    return CaptchaInfo(id = id, type = type, imageUrl = "$captchaUrlBase?captchaId=$id")
  }

  fun extractTipText(html: String): String? =
      tipRegex.find(html)?.groupValues?.getOrNull(1)?.stripHtml()?.trim()?.takeIf {
        it.isNotBlank()
      }

  fun findLoginError(html: String): String? {
    if (html.isBlank()) return null
    extractTipText(html)?.let {
      return it
    }
    val candidates =
        listOf(
            Regex(
                """<div[^>]*id=["']errorDiv["'][^>]*>([\s\S]*?)</div>""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """<div[^>]*class=["'][^"']*errors[^"']*["'][^>]*>([\s\S]*?)</div>""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """<p[^>]*class=["'][^"']*errors[^"']*["'][^>]*>([\s\S]*?)</p>""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """<span[^>]*class=["'][^"']*errors[^"']*["'][^>]*>([\s\S]*?)</span>""",
                RegexOption.IGNORE_CASE,
            ),
        )
    return candidates
        .asSequence()
        .mapNotNull { it.find(html)?.groupValues?.getOrNull(1)?.stripHtml()?.trim() }
        .firstOrNull { it.isNotBlank() }
  }

  fun isIgnorablePasswordExpiryPage(html: String): Boolean {
    if (html.isBlank() || extractExecution(html).isBlank()) return false
    return html.contains("continueForm", ignoreCase = true) ||
        html.contains("ignoreAndContinue", ignoreCase = true) ||
        html.contains("账号存在安全风险") ||
        html.contains("密码过期")
  }

  fun buildIgnorePasswordExpiryParameters(execution: String): Parameters =
      Parameters.build {
        append("execution", execution)
        append("_eventId", "ignoreAndContinue")
      }

  fun buildCasLoginParameters(html: String, request: LoginRequest): Parameters {
    val inputs = inputRegex.findAll(html).map { parseAttributes(it.groupValues[1]) }.toList()
    val presentNames = mutableSetOf<String>()
    return Parameters.build {
      inputs.forEach { attrs ->
        val name = attrs["name"]?.trim().orEmpty()
        if (name.isBlank()) return@forEach
        val type = attrs["type"]?.trim()?.lowercase().orEmpty()
        val value = attrs["value"].orEmpty()
        when (name) {
          "username",
          "password" -> {
            presentNames += name
          }
          else -> {
            when (type) {
              "submit",
              "button",
              "image" -> Unit
              "checkbox" -> {
                presentNames += name
                if ("checked" in attrs) {
                  append(name, value.ifBlank { "on" })
                }
              }
              else -> {
                presentNames += name
                if (type == "hidden" || value.isNotBlank()) {
                  append(name, value)
                }
              }
            }
          }
        }
      }
      append("username", request.username)
      append("password", request.password)
      append("submit", "登录")
      request.captcha
          ?.takeIf { it.isNotBlank() }
          ?.let { captchaValue ->
            if (inputs.any { it["name"] == "captcha" }) append("captcha", captchaValue)
            if (inputs.any { it["name"] == "captchaResponse" })
                append("captchaResponse", captchaValue)
          }
      if (!presentNames.contains("_eventId")) append("_eventId", "submit")
      if (!presentNames.contains("execution")) append("execution", request.execution.orEmpty())
      if (!presentNames.contains("type")) append("type", "username_password")
    }
  }

  fun buildCaptchaLoginParameters(
      request: LoginRequest,
      execution: String = request.execution.orEmpty(),
  ): Parameters =
      Parameters.build {
        val captcha = request.captcha.orEmpty()
        append("username", request.username)
        append("password", request.password)
        append("captcha", captcha)
        append("captchaResponse", captcha)
        append("execution", execution)
        append("_eventId", "submit")
        append("submit", "登录")
        append("type", "username_password")
      }

  private fun parseAttributes(attributes: String): Map<String, String> {
    val values = linkedMapOf<String, String>()
    attrRegex.findAll(attributes).forEach { match ->
      values[match.groupValues[1]] = match.groupValues[2]
    }
    if (Regex("""\bchecked\b""", RegexOption.IGNORE_CASE).containsMatchIn(attributes)) {
      values["checked"] = "checked"
    }
    return values
  }

  private fun String.stripHtml(): String = replace(Regex("<[^>]+>"), " ")
}
