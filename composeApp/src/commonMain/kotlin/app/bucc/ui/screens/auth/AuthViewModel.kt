package app.bucc.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.auth.ApiCallException
import app.bucc.api.auth.AuthService
import app.bucc.api.auth.CaptchaRequiredClientException
import app.bucc.api.auth.UserService
import app.bucc.api.auth.authenticationFailureMessage
import app.bucc.api.finance.CampusFinanceSession
import app.bucc.api.storage.AuthTokensStore
import app.bucc.api.storage.CredentialStore
import app.bucc.connection.AuthenticationDiagnostic
import app.bucc.connection.AuthenticationDiagnostics
import app.bucc.connection.AuthenticationPath
import app.bucc.connection.AuthenticationStage
import app.bucc.model.dto.CaptchaInfo
import app.bucc.model.dto.LoginResponse
import app.bucc.model.dto.UserData
import app.bucc.model.dto.UserInfo
import app.bucc.repository.ScheduleStore
import app.bucc.security.CampusCredential
import app.bucc.security.CampusCredentialStorage
import app.bucc.security.CampusSecureCredentials
import app.bucc.td.TdCampusCredentials
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val authViewModelMonotonicStart = TimeSource.Monotonic.markNow()
private const val AUTOMATIC_RECOVERY_COOLDOWN_MILLIS = 5 * 60 * 1000L

private object PlatformSecureCredentialStorage : CampusCredentialStorage {
  override suspend fun save(credential: CampusCredential) = CampusSecureCredentials.save(credential)

  override suspend fun load(username: String): CampusCredential? {
    AuthenticationDiagnostics.record(
        AuthenticationDiagnostic(
            ConnectionRuntime.currentMode(),
            if (ConnectionRuntime.currentMode() == ConnectionMode.WEBVPN)
                AuthenticationPath.OFFICIAL_GATEWAY
            else AuthenticationPath.DIRECT,
            AuthenticationStage.CREDENTIAL_LOAD,
        )
    )
    return CampusSecureCredentials.load(username)
  }

  override suspend fun delete(username: String) = CampusSecureCredentials.delete(username)
}

/** 身份认证与用户信息管理的 ViewModel。 负责处理登录流程、验证码、用户信息加载以及会话状态同步。 */
class AuthViewModel(
    private val authService: AuthService = AuthService(),
    private val userService: UserService = UserService(),
    private val secureCredentialStorage: CampusCredentialStorage = PlatformSecureCredentialStorage,
    private val nowMonotonicMillis: () -> Long = {
      authViewModelMonotonicStart.elapsedNow().inWholeMilliseconds
    },
) : ViewModel() {
  private var userInfoLoadedOnce = false
  private var userInfoLoading = false
  private var authOperation: Job? = null
  private var automaticLoginAttempted = false
  private var automaticRecoveryAtMillis: Long? = null
  private var automaticRecoveryAccount: String? = null
  private var automaticRecoveryHealthy = false
  private var suppressCredentialAutoLogin = false
  private var requireManualLoginAfterExpiry = false
  private var sessionCredential: CampusCredential? = null
  private var sessionCredentialAccount: String? = null
  private var pendingModeSwitchCredential: CampusCredential? = null
  private var modeSwitchGeneration = 0
  private val _accountEditRevision = MutableStateFlow(0L)
  val accountEditRevision: StateFlow<Long> = _accountEditRevision.asStateFlow()
  private var authenticatedAccountEditRevision = 0L
  val hasPendingAccountEdit: Boolean
    get() = _accountEditRevision.value != authenticatedAccountEditRevision

  private val _uiState = MutableStateFlow(AuthUiState())
  /** 整体认证状态流。 */
  val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

  private val _loginForm = MutableStateFlow(LoginFormState())
  /** 登录表单输入状态流。 */
  val loginForm: StateFlow<LoginFormState> = _loginForm.asStateFlow()

  init {
    loadSavedCredentials()
  }

  /** 从本地持久化存储加载保存的凭据。 */
  private fun loadSavedCredentials() {
    val remember = CredentialStore.isRememberUsername()
    if (remember) {
      val username = CredentialStore.getUsername() ?: ""
      _loginForm.value =
          _loginForm.value.copy(
              username = username,
              password = "",
              rememberUsername = true,
          )
    } else {
      _loginForm.value =
          _loginForm.value.copy(
              username = "",
              password = "",
              captcha = "",
              rememberUsername = false,
          )
    }
  }

  /** 更新表单中的用户名。 */
  fun updateUsername(username: String) {
    if (_loginForm.value.username == username) return
    modeSwitchGeneration++
    pendingModeSwitchCredential = null
    sessionCredential = null
    sessionCredentialAccount = null
    suppressCredentialAutoLogin = true
    automaticLoginAttempted = true
    cancelAuthOperation()
    _loginForm.value = _loginForm.value.copy(username = username)
    _uiState.value =
        _uiState.value.copy(
            isLoggedIn = false,
            isLoading = false,
            isPreloading = false,
            isRecoveringSession = false,
            error = null,
        )
    _accountEditRevision.value++
  }

  /** 更新表单中的密码。 */
  fun updatePassword(password: String) {
    _loginForm.value = _loginForm.value.copy(password = password)
  }

  /** 更新表单中的验证码。 */
  fun updateCaptcha(captcha: String) {
    _loginForm.value = _loginForm.value.copy(captcha = captcha)
  }

  /** 更新“记住账号”选项。 */
  fun updateRememberUsername(enabled: Boolean) {
    _loginForm.value = _loginForm.value.copy(rememberUsername = enabled)
  }

  /** 预加载登录状态。 访问服务端探测当前 SSO 是否已登录，并获取登录所需的执行标识（execution）或验证码。 */
  fun preloadLoginState() {
    launchAuthOperation { preloadAndRestore() }
  }

  /** 仅刷新验证码图片，不重置其他表单状态。 */
  fun refreshCaptcha() {
    val generation = modeSwitchGeneration
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isRefreshingCaptcha = true, error = null)
      authService
          .preloadLoginState()
          .onSuccess { response ->
            if (generation != modeSwitchGeneration) return@onSuccess
            _uiState.value =
                _uiState.value.copy(
                    isRefreshingCaptcha = false,
                    captchaRequired = response.captchaRequired,
                    captchaInfo = response.captcha,
                    execution = response.execution,
                )
          }
          .onFailure {
            if (generation != modeSwitchGeneration) return@onFailure
            _uiState.value =
                _uiState.value.copy(isRefreshingCaptcha = false, error = "刷新验证码失败: ${it.message}")
          }
    }
  }

  /** 执行登录提交。 */
  fun login() {
    val form = _loginForm.value.copy(username = _loginForm.value.username.trim())
    val state = _uiState.value
    val forgottenUsernames =
        if (form.rememberUsername) emptyList()
        else listOfNotNull(CredentialStore.getUsername(), CredentialStore.getAccountId()).distinct()
    if (!form.rememberUsername) {
      CredentialStore.setRememberUsername(false)
      _loginForm.value = _loginForm.value.copy(rememberUsername = false)
    }
    if (form.username.isBlank() || form.password.isBlank()) {
      if (forgottenUsernames.isNotEmpty())
          viewModelScope.launch { deleteSecureCredentials(forgottenUsernames) }
      _uiState.value = _uiState.value.copy(error = "用户名和密码不能为空")
      return
    }
    if (state.captchaRequired && form.captcha.isBlank()) {
      if (forgottenUsernames.isNotEmpty())
          viewModelScope.launch { deleteSecureCredentials(forgottenUsernames) }
      _uiState.value = _uiState.value.copy(error = "请输入验证码")
      return
    }

    suppressCredentialAutoLogin = false
    automaticLoginAttempted = false
    modeSwitchGeneration += 1
    val generation = modeSwitchGeneration
    pendingModeSwitchCredential = null
    cancelAuthOperation()
    launchAuthOperation {
      deleteSecureCredentials(forgottenUsernames)
      _uiState.value = _uiState.value.copy(isLoading = true, error = null)
      val captcha = if (state.captchaRequired) form.captcha else null
      var execution = state.execution
      var result = authService.login(form.username, form.password, captcha, execution)
      // A stale CAS execution or a one-shot service challenge can make the first
      // submission fail even though the same credentials succeed immediately after.
      // Refresh the login page and retry once, without asking the user to click twice.
      val firstError = result.exceptionOrNull()
      if (
          firstError != null &&
              firstError !is CaptchaRequiredClientException &&
              isRetryableLoginFailure(firstError)
      ) {
        val refreshed = authService.preloadLoginState().getOrNull()
        if (refreshed?.captchaRequired == true || refreshed?.captcha != null) {
          _uiState.value =
              _uiState.value.copy(
                  isLoading = false,
                  captchaRequired = true,
                  captchaInfo = refreshed.captcha,
                  execution = refreshed.execution,
                  error = "验证码已更新，请重新输入",
              )
          return@launchAuthOperation
        }
        if (refreshed?.userData != null) {
          if (generation == modeSwitchGeneration) {
            ScheduleStore.useAccount(refreshed.userData!!.schoolid)
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    userData = refreshed.userData,
                    accessToken = refreshed.accessToken?.takeIf(String::isNotBlank),
                    error = null,
                )
            resetUserInfoState()
          }
          return@launchAuthOperation
        }
        execution = refreshed?.execution ?: execution
        result = authService.login(form.username, form.password, captcha, execution)
      }
      result
          .onSuccess { loginResponse ->
            if (generation == modeSwitchGeneration) {
              finishLogin(
                  loginResponse,
                  form.username,
                  form.password,
                  form.rememberUsername,
                  isCurrent = { generation == modeSwitchGeneration },
              )
            }
          }
          .onFailure { exception ->
            if (generation != modeSwitchGeneration) return@onFailure
            if (exception is CaptchaRequiredClientException) {
              _loginForm.value = _loginForm.value.copy(captcha = "")
              _uiState.value =
                  _uiState.value.copy(
                      isLoading = false,
                      captchaRequired = true,
                      captchaInfo = exception.captcha,
                      execution = exception.execution,
                      error = "验证码已更新，请重新输入",
                  )
            } else {
              _uiState.value =
                  _uiState.value.copy(isLoading = false, error = exception.message ?: "登录失败")
            }
          }
    }
  }

  fun onAppBackgrounded() {
    cancelAuthOperation()
  }

  /** 应用全局初始化入口。 用于检查本地 Token 是否有效，若失效则根据设置决定跳转登录页或尝试自动登录。 */
  fun initializeApp() {
    if (hasPendingAccountEdit) return
    if (ConnectionRuntime.currentMode() == null) return
    loadSavedCredentials()
    val generation = modeSwitchGeneration
    launchAuthOperation {
      val restoredAccessToken = AuthTokensStore.get()?.accessToken?.takeIf { it.isNotBlank() }
      if (!authService.hasPersistedSession()) {
        preloadAndRestore()
        return@launchAuthOperation
      }

      authService.applyStoredTokens()
      _uiState.value = _uiState.value.copy(isLoading = true)
      authService
          .getAuthStatus()
          .onSuccess { status ->
            if (generation != modeSwitchGeneration) return@onSuccess
            markAutomaticRecoveryHealthy(status.user.schoolid)
            TdCampusCredentials.setActiveAccount(status.user.schoolid)
            ScheduleStore.useAccount(status.user.schoolid)
            _uiState.value =
                _uiState.value.copy(
                    isLoggedIn = true,
                    isLoading = false,
                    isRecoveringSession = false,
                    userData = status.user,
                    accessToken = restoredAccessToken,
                )
            resetUserInfoState()
          }
          .onFailure { error ->
            if (generation != modeSwitchGeneration) return@onFailure
            _uiState.value = _uiState.value.copy(isLoading = false)
            if (error is ApiCallException && error.code == "auth_upstream_timeout") {
              _uiState.value = _uiState.value.copy(error = error.message)
              return@onFailure
            }
            if (isUnauthenticated(error)) {
              clearExpiredMainSession()
              preloadAndRestore()
            } else {
              _uiState.value = _uiState.value.copy(error = error.message ?: "无法验证登录状态")
            }
          }
    }
  }

  fun switchConnectionMode(mode: ConnectionMode) {
    val previousUserData = _uiState.value.userData
    val credential =
        sessionCredential?.takeIf { sessionCredentialAccount == previousUserData?.schoolid }
            ?: pendingModeSwitchCredential
    if (credential != null) pendingModeSwitchCredential = credential
    val remembered = CredentialStore.isRememberUsername()
    val rememberedUsername = CredentialStore.getUsername()?.takeIf(String::isNotBlank)
    val username =
        credential?.username
            ?: rememberedUsername
            ?: _loginForm.value.username.takeIf(String::isNotBlank)
    val generation = ++modeSwitchGeneration
    TdCampusCredentials.clearSession()
    val recoveringUser =
        previousUserData.takeIf { credential != null || (remembered && username != null) }
    _uiState.value =
        AuthUiState(
            isLoading = true,
            isPreloading = true,
            isRecoveringSession = recoveringUser != null,
            userData = recoveringUser,
        )
    cancelAuthOperation()
    launchAuthOperation {
      try {
        reconnectAfterModeSwitch(mode, credential, remembered, username, generation, recoveringUser)
      } finally {
        if (generation == modeSwitchGeneration) pendingModeSwitchCredential = null
      }
    }
  }

  private suspend fun reconnectAfterModeSwitch(
      mode: ConnectionMode,
      capturedCredential: CampusCredential?,
      remembered: Boolean,
      username: String?,
      generation: Int,
      recoveringUser: UserData?,
  ) {
    if (generation != modeSwitchGeneration) return
    ConnectionRuntime.switchMode(mode)
    if (hasPendingAccountEdit) {
      _uiState.value = AuthUiState(error = "账号已修改，请手动登录")
      return
    }
    resetUserInfoState()
    suppressCredentialAutoLogin = false
    automaticLoginAttempted = true
    automaticRecoveryAtMillis = null
    automaticRecoveryAccount = null
    automaticRecoveryHealthy = false
    requireManualLoginAfterExpiry = false
    _uiState.value =
        AuthUiState(
            isLoading = true,
            isPreloading = true,
            isRecoveringSession = recoveringUser != null,
            userData = recoveringUser,
        )
    _loginForm.value = LoginFormState(username = username.orEmpty(), rememberUsername = remembered)

    val preload =
        try {
          authService.preloadLoginState().getOrElse { error ->
            if (error is CancellationException) throw error
            if (generation != modeSwitchGeneration) return
            _uiState.value =
                AuthUiState(error = authenticationFailureMessage(error, "切换连接模式后无法加载登录状态，请手动登录"))
            return
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          if (generation != modeSwitchGeneration) return
          _uiState.value = AuthUiState(error = "切换连接模式后无法加载登录状态，请手动登录")
          return
        }
    if (generation != modeSwitchGeneration) return
    preload.userData?.let { restored ->
      val expectedAccount =
          recoveringUser?.schoolid
              ?: CredentialStore.getAccountId().takeIf { remembered }
              ?: username
      if (
          restored.schoolid.isBlank() ||
              (expectedAccount != null && expectedAccount != restored.schoolid)
      ) {
        authService.clearStoredSession()
        _uiState.value = AuthUiState(error = "认证返回的账号与当前账号不一致，请手动登录")
        return
      }
      if (capturedCredential != null) {
        sessionCredential = capturedCredential
        sessionCredentialAccount = restored.schoolid
        TdCampusCredentials.setAuthenticated(restored.schoolid, capturedCredential.password)
      } else TdCampusCredentials.setActiveAccount(restored.schoolid)
      ScheduleStore.useAccount(restored.schoolid)
      _uiState.value =
          AuthUiState(
              isLoggedIn = true,
              userData = restored,
              accessToken = preload.accessToken?.takeIf(String::isNotBlank),
          )
      _loginForm.value =
          LoginFormState(username = username ?: restored.schoolid, rememberUsername = remembered)
      return
    }

    val credential =
        capturedCredential
            ?: if (remembered && username != null) {
              try {
                secureCredentialStorage.load(username)?.takeIf { it.username == username }
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (_: Throwable) {
                null
              }
            } else {
              null
            }
    if (generation != modeSwitchGeneration) return

    val formUsername = credential?.username ?: username.orEmpty()
    _loginForm.value = LoginFormState(username = formUsername, rememberUsername = remembered)
    if (preload.captchaRequired || preload.captcha != null) {
      _uiState.value =
          AuthUiState(
              captchaRequired = true,
              captchaInfo = preload.captcha,
              execution = preload.execution,
              error = "请完成验证码后手动登录",
          )
      return
    }
    if (credential == null || credential.username.isBlank() || credential.password.isBlank()) {
      if (remembered) clearUnavailableRememberedCredential(username)
      _uiState.value = AuthUiState(error = "切换连接模式后无法自动重连，请手动登录")
      return
    }

    _uiState.value =
        AuthUiState(
            isLoading = true,
            isRecoveringSession = recoveringUser != null,
            userData = recoveringUser,
            execution = preload.execution,
        )
    val loginResult =
        try {
          authService.login(
              credential.username,
              credential.password,
              captcha = null,
              execution = preload.execution,
          )
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Result.failure(error)
        }
    if (generation != modeSwitchGeneration) return
    loginResult
        .onSuccess { response ->
          val expectedAccount =
              recoveringUser?.schoolid ?: CredentialStore.getAccountId().takeIf { remembered }
          if (
              response.user.schoolid.isBlank() ||
                  (expectedAccount != null && response.user.schoolid != expectedAccount)
          ) {
            authService.clearStoredSession()
            _uiState.value = AuthUiState(error = "认证返回的账号与当前账号不一致，请手动登录")
            return@onSuccess
          }
          // finishLogin checks the same generation again after any credential-storage suspension.
          finishLogin(
              response,
              credential.username,
              credential.password,
              rememberUsername = remembered,
              isCurrent = { generation == modeSwitchGeneration },
          )
        }
        .onFailure { error ->
          _loginForm.value = _loginForm.value.copy(password = "", captcha = "")
          if (error is CaptchaRequiredClientException) {
            _uiState.value =
                AuthUiState(
                    captchaRequired = true,
                    captchaInfo = error.captcha,
                    execution = error.execution,
                    error = "请完成验证码后手动登录",
                )
          } else {
            _uiState.value =
                AuthUiState(error = authenticationFailureMessage(error, "切换连接模式后登录失败，请手动登录"))
          }
        }
  }

  /** 注销登录，清理所有本地状态。 */
  fun logout() {
    val accountsToClear =
        listOfNotNull(
                sessionCredential?.username,
                CredentialStore.getUsername(),
                CredentialStore.getAccountId(),
                _uiState.value.userData?.schoolid,
            )
            .distinct()
    suppressCredentialAutoLogin = true
    modeSwitchGeneration += 1
    pendingModeSwitchCredential = null
    cancelAuthOperation()
    sessionCredential = null
    sessionCredentialAccount = null
    TdCampusCredentials.clearSession()
    ScheduleStore.forgetAccount()
    _uiState.value = AuthUiState(isLoading = true)
    viewModelScope.launch {
      deleteSecureCredentials(accountsToClear)
      app.bucc.api.finance.CampusFinanceSession.reset()
      try {
        app.bucc.notifications.cancelBuccNotifications()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {}
      _uiState.value = _uiState.value.copy(isLoading = true)
      authService
          .logout()
          .onSuccess {
            resetUserInfoState()
            _uiState.value = AuthUiState()
            loadSavedCredentials()
            preloadAndRestore()
          }
          .onFailure {
            resetUserInfoState()
            _uiState.value = AuthUiState(error = "注销完成但有警告: ${it.message}")
            loadSavedCredentials()
            preloadAndRestore()
          }
    }
  }

  /** 按需加载用户的详细档案信息。 */
  fun ensureUserInfoLoaded(forceRefresh: Boolean = false) {
    if (userInfoLoading) return
    if (!forceRefresh && (userInfoLoadedOnce || _uiState.value.userInfo != null)) {
      return
    }
    userInfoLoading = true
    viewModelScope.launch {
      userService
          .getUserInfo()
          .onSuccess { info ->
            userInfoLoadedOnce = true
            _uiState.value = _uiState.value.copy(userInfo = info)
          }
          .onFailure { userInfoLoadedOnce = false }
      userInfoLoading = false
    }
  }

  /** 前台恢复时验证当前会话是否仍然有效。 如果会话过期或网络暂时不可用，根据错误类型决定保留还是清除会话。 */
  fun validateSession() {
    if (!_uiState.value.isLoggedIn) return
    val generation = modeSwitchGeneration
    launchAuthOperation {
      if (!_uiState.value.isLoggedIn) return@launchAuthOperation
      authService
          .getAuthStatus()
          .onSuccess { status ->
            if (generation != modeSwitchGeneration) return@onSuccess
            markAutomaticRecoveryHealthy(status.user.schoolid)
            TdCampusCredentials.setActiveAccount(status.user.schoolid)
            ScheduleStore.useAccount(status.user.schoolid)
            _uiState.value =
                _uiState.value.copy(
                    isLoggedIn = true,
                    userData = status.user,
                )
          }
          .onFailure { error ->
            if (generation != modeSwitchGeneration) return@onFailure
            if (error is ApiCallException && error.code == "auth_upstream_timeout") {
              // 网络暂时不可用，保留会话状态
              return@onFailure
            }
            if (isUnauthenticated(error)) {
              clearExpiredMainSession()
              preloadAndRestore()
            } else {
              _uiState.value = _uiState.value.copy(error = error.message ?: "无法验证登录状态")
            }
          }
    }
  }

  /** 清除当前的错误提示。 */
  fun clearError() {
    _uiState.value = _uiState.value.copy(error = null)
  }

  private fun launchAuthOperation(block: suspend () -> Unit) {
    if (authOperation?.isActive == true) return
    lateinit var job: Job
    job =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          try {
            block()
          } finally {
            if (authOperation === job) authOperation = null
          }
        }
    authOperation = job
    job.start()
  }

  private fun cancelAuthOperation() {
    authOperation?.cancel()
    authOperation = null
  }

  private suspend fun preloadAndRestore() {
    val generation = modeSwitchGeneration
    _uiState.value = _uiState.value.copy(isLoading = false, isPreloading = true, error = null)
    val preloadResult =
        try {
          authService.preloadLoginState()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (generation != modeSwitchGeneration) return
          _uiState.value = AuthUiState(error = "无法加载登录状态，请稍后重试")
          return
        }
    if (generation != modeSwitchGeneration) return
    val preload =
        preloadResult.getOrElse { error ->
          if (error is CancellationException) throw error
          _uiState.value = AuthUiState(error = "无法加载登录状态，请稍后重试")
          return
        }

    val recoveryUser = _uiState.value.userData.takeIf { _uiState.value.isRecoveringSession }
    val restoredUser = preload.userData
    if (restoredUser != null) {
      if (requireManualLoginAfterExpiry) {
        // Preloading may itself restore and persist a CAS session; manual-login policy
        // must clear that replacement as well, rather than only hiding it in the UI.
        authService.clearStoredSession()
        TdCampusCredentials.clearSession()
        CampusFinanceSession.reset()
        _uiState.value = AuthUiState(error = "会话已过期，请重新登录")
        _loginForm.value = _loginForm.value.copy(password = "", captcha = "")
        return
      }
      TdCampusCredentials.setActiveAccount(restoredUser.schoolid)
      ScheduleStore.useAccount(restoredUser.schoolid)
      _uiState.value =
          _uiState.value.copy(
              isPreloading = false,
              isLoading = false,
              isLoggedIn = true,
              isRecoveringSession = false,
              userData = restoredUser,
              accessToken = preload.accessToken?.takeIf { it.isNotBlank() },
              captchaRequired = false,
              captchaInfo = null,
              execution = null,
              error = null,
          )
      resetUserInfoState()
      _loginForm.value = LoginFormState()
      return
    }

    _uiState.value =
        _uiState.value.copy(
            isPreloading = false,
            isLoading = false,
            isLoggedIn = false,
            isRecoveringSession = recoveryUser != null,
            userData = recoveryUser,
            accessToken = null,
            captchaRequired = preload.captchaRequired || preload.captcha != null,
            captchaInfo = preload.captcha,
            execution = preload.execution,
            error = null,
        )
    _loginForm.value = _loginForm.value.copy(password = "", captcha = "")
    if (preload.captchaRequired || preload.captcha != null || suppressCredentialAutoLogin) {
      clearRecoveryForManualLogin()
      return
    }
    if (requireManualLoginAfterExpiry) {
      clearRecoveryForManualLogin("会话已过期，请重新登录")
      return
    }
    if (automaticLoginAttempted) {
      clearRecoveryForManualLogin("会话已过期，请重新登录")
      return
    }
    if (!CredentialStore.isRememberUsername() || !CredentialStore.isAutoLogin()) {
      clearRecoveryForManualLogin()
      return
    }
    automaticLoginAttempted = true

    val username =
        CredentialStore.getUsername()?.takeIf(String::isNotBlank)
            ?: run {
              clearUnavailableRememberedCredential(null)
              return
            }
    val credential =
        try {
          secureCredentialStorage.load(username)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          null
        }
            ?: run {
              clearUnavailableRememberedCredential(username)
              return
            }
    if (generation != modeSwitchGeneration) return
    if (credential.username != username || credential.password.isBlank()) {
      clearUnavailableRememberedCredential(username)
      return
    }

    _uiState.value = _uiState.value.copy(isLoading = true, error = null)
    val loginResult =
        try {
          authService.login(
              username,
              credential.password,
              captcha = null,
              execution = preload.execution,
          )
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Result.failure(error)
        }
    if (generation != modeSwitchGeneration) return
    loginResult
        .onSuccess { response ->
          finishLogin(
              response,
              username,
              credential.password,
              rememberUsername = true,
              automaticRecovery = true,
              isCurrent = { generation == modeSwitchGeneration },
          )
        }
        .onFailure { error ->
          _loginForm.value = _loginForm.value.copy(password = "", captcha = "")
          if (error is CaptchaRequiredClientException) {
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    isPreloading = false,
                    isRecoveringSession = false,
                    userData = null,
                    accessToken = null,
                    captchaRequired = true,
                    captchaInfo = error.captcha,
                    execution = error.execution,
                    error = null,
                )
          } else {
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    isPreloading = false,
                    isRecoveringSession = false,
                    userData = null,
                    accessToken = null,
                    error = authenticationFailureMessage(error, "自动登录失败，请手动登录"),
                )
          }
        }
  }

  private fun clearRecoveryForManualLogin(error: String? = null) {
    _uiState.value =
        AuthUiState(
            error = error,
            captchaRequired = _uiState.value.captchaRequired,
            captchaInfo = _uiState.value.captchaInfo,
            execution = _uiState.value.execution,
        )
  }

  private suspend fun clearUnavailableRememberedCredential(username: String?) {
    CredentialStore.setRememberUsername(false)
    val formUsername = username ?: _loginForm.value.username
    _loginForm.value = LoginFormState(username = formUsername)
    if (!username.isNullOrBlank()) deleteSecureCredentials(listOf(username))
    clearRecoveryForManualLogin()
  }

  private suspend fun deleteSecureCredentials(usernames: List<String>) {
    usernames.forEach { username ->
      try {
        secureCredentialStorage.delete(username)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        // Forgetting a local credential should never prevent a manual login.
      }
    }
  }

  private suspend fun finishLogin(
      response: LoginResponse,
      username: String,
      password: String,
      rememberUsername: Boolean,
      automaticRecovery: Boolean = false,
      isCurrent: () -> Boolean = { true },
  ) {
    if (!isCurrent()) return
    requireManualLoginAfterExpiry = false
    if (automaticRecovery) {
      automaticLoginAttempted = true
      automaticRecoveryAtMillis = nowMonotonicMillis()
      automaticRecoveryAccount = response.user.schoolid
      automaticRecoveryHealthy = false
    } else {
      automaticLoginAttempted = false
      automaticRecoveryAtMillis = null
      automaticRecoveryAccount = null
      automaticRecoveryHealthy = false
    }
    TdCampusCredentials.setAuthenticated(response.user.schoolid, password)
    try {
      if (rememberUsername) {
        secureCredentialStorage.save(CampusCredential(username, password))
      } else {
        // Also remove records written by versions that keyed credentials by school ID.
        deleteSecureCredentials(listOf(username, response.user.schoolid).distinct())
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      // Authentication remains valid in this process.
    }
    if (!isCurrent()) return
    sessionCredential = CampusCredential(username, password)
    sessionCredentialAccount = response.user.schoolid
    authenticatedAccountEditRevision = _accountEditRevision.value
    ScheduleStore.useAccount(response.user.schoolid)
    _uiState.value =
        _uiState.value.copy(
            isLoggedIn = true,
            isRecoveringSession = false,
            isLoading = false,
            isPreloading = false,
            userData = response.user,
            accessToken = response.accessToken.takeIf { it.isNotBlank() },
            error = null,
            captchaRequired = false,
            captchaInfo = null,
            execution = null,
        )
    resetUserInfoState()
    CredentialStore.setRememberUsername(rememberUsername)
    if (rememberUsername) {
      CredentialStore.saveUsername(username, response.user.schoolid)
      _loginForm.value = LoginFormState(username = username, rememberUsername = true)
    } else {
      _loginForm.value = LoginFormState()
    }
  }

  private fun isRetryableLoginFailure(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    if (error is ApiCallException && error.code == "auth_service_challenge_required") return true
    return listOf("登录页面已更新", "验证码已失效", "统一认证仍需验证", "认证服务响应超时").any(message::contains)
  }

  private fun isUnauthenticated(error: Throwable): Boolean =
      error is ApiCallException && error.code == "unauthenticated"

  private fun markAutomaticRecoveryHealthy(accountId: String) {
    if (automaticRecoveryAtMillis != null && automaticRecoveryAccount == accountId) {
      automaticRecoveryHealthy = true
    }
  }

  private suspend fun clearExpiredMainSession() {
    val expiredUser = _uiState.value.userData
    val rememberAccount = CredentialStore.isRememberUsername() && CredentialStore.isAutoLogin()
    val recoveryAt = automaticRecoveryAtMillis
    val now = nowMonotonicMillis()
    val canStartNextRecovery =
        automaticLoginAttempted &&
            automaticRecoveryHealthy &&
            recoveryAt != null &&
            automaticRecoveryAccount == expiredUser?.schoolid &&
            now >= recoveryAt &&
            now - recoveryAt >= AUTOMATIC_RECOVERY_COOLDOWN_MILLIS
    if (canStartNextRecovery) {
      automaticLoginAttempted = false
      automaticRecoveryAtMillis = null
      automaticRecoveryAccount = null
      automaticRecoveryHealthy = false
    }
    requireManualLoginAfterExpiry = !rememberAccount || automaticLoginAttempted
    val accountId =
        sessionCredential?.username
            ?: expiredUser?.schoolid
            ?: TdCampusCredentials.activeUsername()
            ?: ScheduleStore.account()?.takeIf(String::isNotBlank)
    authService.clearStoredSession()
    sessionCredential = null
    sessionCredentialAccount = null
    TdCampusCredentials.clearSession()
    CampusFinanceSession.reset()
    resetUserInfoState()
    _uiState.value =
        AuthUiState(
            userData = expiredUser.takeIf { rememberAccount },
            isRecoveringSession = rememberAccount && expiredUser != null,
        )
    _loginForm.value = _loginForm.value.copy(password = "", captcha = "")
    if (!rememberAccount) {
      accountId?.let { username ->
        try {
          secureCredentialStorage.delete(username)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          // Expired session cleanup continues even if credential storage is unavailable.
        }
      }
    }
  }

  private fun resetUserInfoState() {
    userInfoLoadedOnce = false
    userInfoLoading = false
    _uiState.value = _uiState.value.copy(userInfo = null)
  }
}

/** 认证模块 UI 状态模型。 */
data class AuthUiState(
    val isLoading: Boolean = false,
    val isPreloading: Boolean = false,
    val isRefreshingCaptcha: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isRecoveringSession: Boolean = false,
    val userData: UserData? = null,
    val userInfo: UserInfo? = null,
    val accessToken: String? = null,
    val error: String? = null,
    val captchaRequired: Boolean = false,
    val captchaInfo: CaptchaInfo? = null,
    val execution: String? = null,
)

/** 登录表单本地交互状态模型。 */
data class LoginFormState(
    val username: String = "",
    val password: String = "",
    val captcha: String = "",
    val rememberUsername: Boolean = false,
)
