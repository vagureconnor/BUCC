package app.bucc.ui

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import app.bucc.api.auth.ApiCallException
import app.bucc.api.auth.AuthService
import app.bucc.api.auth.CaptchaRequiredClientException
import app.bucc.api.auth.SessionStatusResponse
import app.bucc.api.auth.UserService
import app.bucc.api.finance.CampusFinanceSession
import app.bucc.api.storage.CredentialStore
import app.bucc.model.dto.CaptchaInfo
import app.bucc.model.dto.LoginPreloadResponse
import app.bucc.model.dto.LoginResponse
import app.bucc.model.dto.UserData
import app.bucc.model.dto.UserInfo
import app.bucc.security.CampusCredential
import app.bucc.security.CampusCredentialStorage
import app.bucc.td.TdCampusCredentials
import app.bucc.ui.screens.auth.AuthViewModel
import com.russhwolf.settings.MapSettings
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelAutoLoginTest {
  @BeforeTest
  fun setup() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    CredentialStore.settings = MapSettings()
    CredentialStore.clear()
    CampusFinanceSession.reset()
    TdCampusCredentials.clearSession()
    ConnectionModeStore.clear()
    ConnectionRuntime.clearSelectedMode()
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
    CampusFinanceSession.reset()
    TdCampusCredentials.clearSession()
    CredentialStore.clear()
    ConnectionModeStore.clear()
    ConnectionRuntime.clearSelectedMode()
  }

  @Test
  fun `mode switch accepts verified preload without requiring or posting a password`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val credentials = FakeCredentialStorage(null)
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, userData = USER)
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.isLoggedIn)
    assertEquals(USER, viewModel.uiState.value.userData)
    assertTrue(auth.loginCalls.isEmpty())
    assertTrue(credentials.loadedUsernames.isEmpty())
  }

  @Test
  fun `mode switch retains safe network failure category without exposing exception text`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        rememberUsername()
        val auth =
            RecordingAuthService(
                preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
                loginFailure =
                    ApiCallException(
                        "private token=must-not-appear",
                        HttpStatusCode.ServiceUnavailable,
                        "auth_upstream_unreachable",
                    ),
            )
        val viewModel =
            AuthViewModel(
                authService = auth,
                userService = StubUserService(),
                secureCredentialStorage =
                    FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD)),
            )

        viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error.orEmpty().contains("无法连接统一认证服务"))
        assertFalse(viewModel.uiState.value.error.orEmpty().contains("must-not-appear"))
        assertEquals(1, auth.loginCalls.size)
      }

  @Test
  fun `mode switch reauthenticates with remembered session credential`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "mode-fresh")
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.updateUsername(USERNAME)
    viewModel.updatePassword(PASSWORD)
    viewModel.login()
    advanceUntilIdle()
    auth.loginCalls.clear()

    viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()

    assertEquals(ConnectionMode.WEBVPN, ConnectionRuntime.currentMode())
    assertEquals(1, auth.preloadCalls)
    assertEquals(listOf(LoginCall(USERNAME, PASSWORD, null, "mode-fresh")), auth.loginCalls)
    assertTrue(viewModel.uiState.value.isLoggedIn)
    assertTrue(CredentialStore.isRememberUsername())
    assertTrue(credentials.loadedUsernames.isEmpty())
  }

  @Test
  fun `mode switch reads secure credential only when remember is enabled`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "stored-mode")
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()

    assertEquals(listOf(USERNAME), credentials.loadedUsernames)
    assertEquals(listOf(LoginCall(USERNAME, PASSWORD, null, "stored-mode")), auth.loginCalls)
    assertTrue(CredentialStore.isRememberUsername())
    assertTrue(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `mode switch reuses unremembered in memory credential only`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "mode-fresh")
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.updateUsername(USERNAME)
    viewModel.updatePassword(PASSWORD)
    viewModel.login()
    advanceUntilIdle()
    auth.loginCalls.clear()

    viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()

    assertEquals(listOf(LoginCall(USERNAME, PASSWORD, null, "mode-fresh")), auth.loginCalls)
    assertTrue(credentials.loadedUsernames.isEmpty())
    assertFalse(CredentialStore.isRememberUsername())
    assertTrue(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `mode switch keeps previous user visible in recovery state while login is suspended`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        val loginGate = CompletableDeferred<Unit>()
        val auth =
            RecordingAuthService(
                persistedSession = true,
                status = Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                preload = LoginPreloadResponse(captchaRequired = false, execution = "mode-fresh"),
            )
        val viewModel =
            AuthViewModel(
                authService = auth,
                userService = StubUserService(),
                secureCredentialStorage = FakeCredentialStorage(null),
            )

        viewModel.updateUsername(USERNAME)
        viewModel.updatePassword(PASSWORD)
        viewModel.login()
        advanceUntilIdle()
        auth.loginCalls.clear()
        auth.loginGate = loginGate
        viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
        runCurrent()

        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertTrue(viewModel.uiState.value.isRecoveringSession)
        assertEquals(USER, viewModel.uiState.value.userData)

        loginGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertFalse(viewModel.uiState.value.isRecoveringSession)
        assertEquals(USER, viewModel.uiState.value.userData)
      }

  @Test
  fun `mode switch without in memory credential never reads saved password when remember is off`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
        val auth =
            RecordingAuthService(
                preload = LoginPreloadResponse(captchaRequired = false, execution = "manual")
            )
        val viewModel =
            AuthViewModel(
                authService = auth,
                userService = StubUserService(),
                secureCredentialStorage = credentials,
            )
        viewModel.updateUsername(USERNAME)

        viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
        advanceUntilIdle()

        assertTrue(auth.loginCalls.isEmpty())
        assertTrue(credentials.loadedUsernames.isEmpty())
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertTrue(viewModel.uiState.value.error.orEmpty().contains("手动登录"))
      }

  @Test
  fun `mode switch captcha and mfa failures ask for manual login without retry`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val captcha = CaptchaInfo("challenge", imageUrl = "captcha-image")
    val captchaAuth =
        RecordingAuthService(
            preload =
                LoginPreloadResponse(
                    captchaRequired = true,
                    captcha = captcha,
                    execution = "captcha-execution",
                ),
        )
    val captchaViewModel =
        AuthViewModel(
            authService = captchaAuth,
            userService = StubUserService(),
            secureCredentialStorage = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD)),
        )

    captchaViewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()

    assertTrue(captchaViewModel.uiState.value.captchaRequired)
    assertEquals(captcha, captchaViewModel.uiState.value.captchaInfo)
    assertTrue(captchaAuth.loginCalls.isEmpty())
    assertTrue(captchaViewModel.uiState.value.error.orEmpty().contains("手动"))

    rememberUsername()
    ConnectionRuntime.switchMode(ConnectionMode.DIRECT)
    val mfaAuth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "mfa-execution"),
            loginFailure =
                ApiCallException("MFA required", HttpStatusCode.Forbidden, "mfa_required"),
        )
    val mfaViewModel =
        AuthViewModel(
            authService = mfaAuth,
            userService = StubUserService(),
            secureCredentialStorage = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD)),
        )

    mfaViewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()
    mfaViewModel.initializeApp()
    advanceUntilIdle()

    assertEquals(1, mfaAuth.loginCalls.size)
    assertEquals("会话已过期，请重新登录", mfaViewModel.uiState.value.error)
    assertFalse(mfaViewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `latest concurrent mode switch keeps captured credential and prevents stale login finish`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        val loginGate = CompletableDeferred<Unit>()
        val auth =
            RecordingAuthService(
                persistedSession = true,
                status = Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                preload = LoginPreloadResponse(captchaRequired = false, execution = "latest-mode"),
            )
        val viewModel =
            AuthViewModel(
                authService = auth,
                userService = StubUserService(),
                secureCredentialStorage = FakeCredentialStorage(null),
            )

        viewModel.updateUsername(USERNAME)
        viewModel.updatePassword(PASSWORD)
        viewModel.login()
        advanceUntilIdle()
        auth.loginCalls.clear()
        auth.loginGate = loginGate
        assertTrue(viewModel.uiState.value.isLoggedIn)

        viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
        runCurrent()
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertTrue(viewModel.uiState.value.isRecoveringSession)
        assertEquals(USER, viewModel.uiState.value.userData)
        viewModel.switchConnectionMode(ConnectionMode.DIRECT)
        runCurrent()
        loginGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ConnectionMode.DIRECT, ConnectionRuntime.currentMode())
        assertEquals(1, auth.loginCalls.size)
        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertEquals(PASSWORD, TdCampusCredentials.current()?.password)
      }

  @Test
  fun `mode switch cancels a suspended regular login so it cannot finish stale`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val loginGate = CompletableDeferred<Unit>()
    val auth = RecordingAuthService(loginGate = loginGate)
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = FakeCredentialStorage(null),
        )
    viewModel.updateUsername(USERNAME)
    viewModel.updatePassword(PASSWORD)

    viewModel.login()
    runCurrent()
    viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()
    loginGate.complete(Unit)
    advanceUntilIdle()

    assertTrue(auth.loginCalls.isEmpty())
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertNull(TdCampusCredentials.current())
    assertTrue(viewModel.uiState.value.error.orEmpty().contains("手动登录"))
  }

  @Test
  fun `startup uses a fresh preload and remembered secure credential once`() = runTest {
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    rememberUsername()
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh-execution"),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertEquals(1, auth.preloadCalls)
    assertEquals(listOf(LoginCall(USERNAME, PASSWORD, null, "fresh-execution")), auth.loginCalls)
    assertTrue(viewModel.uiState.value.isLoggedIn)
    assertEquals("Synthetic User", viewModel.uiState.value.userData?.name)
    assertFalse(viewModel.loginForm.value.password.isNotEmpty())
  }

  @Test
  fun `startup reloads remembered account preferences changed after view model creation`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        val auth =
            RecordingAuthService(
                preload =
                    LoginPreloadResponse(
                        captchaRequired = true,
                        captcha = CaptchaInfo("challenge", imageUrl = "captcha-image"),
                        execution = "challenge-execution",
                    ),
            )
        val credentials = FakeCredentialStorage(null)
        val viewModel = AuthViewModel(auth, StubUserService(), credentials)
        CredentialStore.setRememberUsername(true)
        CredentialStore.saveUsername(USERNAME)

        viewModel.initializeApp()
        advanceUntilIdle()

        assertEquals(USERNAME, viewModel.loginForm.value.username)
        assertTrue(viewModel.loginForm.value.rememberUsername)
        assertTrue(viewModel.uiState.value.captchaRequired)
        assertTrue(credentials.loadedUsernames.isEmpty())
        assertTrue(auth.loginCalls.isEmpty())
      }

  @Test
  fun `startup preference refresh does not replace a pending account edit`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val auth = RecordingAuthService()
    val viewModel = AuthViewModel(auth, StubUserService(), FakeCredentialStorage(null))
    val typedAccount = "account-being-edited"
    viewModel.updateUsername(typedAccount)
    CredentialStore.setRememberUsername(true)
    CredentialStore.saveUsername("newly-saved-account")

    viewModel.initializeApp()
    advanceUntilIdle()

    assertTrue(viewModel.hasPendingAccountEdit)
    assertEquals(typedAccount, viewModel.loginForm.value.username)
    assertEquals(0, auth.preloadCalls)
    assertTrue(auth.loginCalls.isEmpty())
  }

  @Test
  fun `startup clears remembered account fields when remember is disabled after view model creation`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        rememberUsername()
        val preloadGate = CompletableDeferred<Unit>()
        val auth =
            RecordingAuthService(
                preload =
                    LoginPreloadResponse(
                        captchaRequired = true,
                        captcha = CaptchaInfo("challenge", imageUrl = "captcha-image"),
                        execution = "challenge-execution",
                    ),
                preloadGate = preloadGate,
            )
        val viewModel = AuthViewModel(auth, StubUserService(), FakeCredentialStorage(null))
        viewModel.updatePassword(PASSWORD)
        viewModel.updateCaptcha("stale-captcha")
        CredentialStore.setRememberUsername(false)

        viewModel.initializeApp()
        runCurrent()

        assertFalse(viewModel.hasPendingAccountEdit)
        assertEquals("", viewModel.loginForm.value.username)
        assertEquals("", viewModel.loginForm.value.password)
        assertEquals("", viewModel.loginForm.value.captcha)
        assertFalse(viewModel.loginForm.value.rememberUsername)

        preloadGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.captchaRequired)
        assertTrue(auth.loginCalls.isEmpty())
      }

  @Test
  fun `valid restored sso session skips password login`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            persistedSession = true,
            status = Result.success(SessionStatusResponse(USER, "last", "authenticated")),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.isLoggedIn)
    assertEquals(0, auth.preloadCalls)
    assertTrue(auth.loginCalls.isEmpty())
    assertTrue(credentials.loadedUsernames.isEmpty())
  }

  @Test
  fun `expired session preloads fresh execution then logs in once`() = runTest {
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            persistedSession = true,
            status =
                Result.failure(
                    ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated")
                ),
            preload = LoginPreloadResponse(captchaRequired = false, execution = "new-execution"),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertEquals(1, auth.statusCalls)
    assertEquals(1, auth.preloadCalls)
    assertEquals(1, auth.loginCalls.size)
    assertEquals("new-execution", auth.loginCalls.single().execution)
    assertTrue(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `captcha challenge hands control to login ui without automatic submission`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val captcha = CaptchaInfo("challenge", imageUrl = "captcha-image")
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            preload =
                LoginPreloadResponse(
                    captchaRequired = true,
                    captcha = captcha,
                    execution = "challenge-execution",
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertTrue(auth.loginCalls.isEmpty())
    assertTrue(viewModel.uiState.value.captchaRequired)
    assertEquals(captcha, viewModel.uiState.value.captchaInfo)
    assertFalse(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `captcha returned by automatic login is handed to ui without retry`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
            loginFailure =
                CaptchaRequiredClientException(
                    CaptchaInfo("challenge", imageUrl = "captcha-image"),
                    "challenge-execution",
                    "captcha needed",
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertEquals(1, auth.loginCalls.size)
    assertTrue(viewModel.uiState.value.captchaRequired)
    assertEquals("challenge-execution", viewModel.uiState.value.execution)
    assertEquals("", viewModel.loginForm.value.password)
  }

  @Test
  fun `automatic login failure is shown without a retry loop`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
            loginFailure =
                ApiCallException(
                    "second factor required",
                    HttpStatusCode.Forbidden,
                    "mfa_required",
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    assertEquals("自动登录失败，请手动登录", viewModel.uiState.value.error)
    viewModel.initializeApp()
    advanceUntilIdle()

    assertEquals(1, auth.loginCalls.size)
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertEquals("", viewModel.loginForm.value.password)
  }

  @Test
  fun `timeout does not load credential or submit password`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            persistedSession = true,
            status =
                Result.failure(
                    ApiCallException(
                        "timeout",
                        HttpStatusCode.ServiceUnavailable,
                        "auth_upstream_timeout",
                    )
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertEquals(0, auth.preloadCalls)
    assertTrue(auth.loginCalls.isEmpty())
    assertTrue(credentials.loadedUsernames.isEmpty())
  }

  @Test
  fun `remember disabled never loads or submits stored credentials`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    CredentialStore.saveUsername(USERNAME)
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh")
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertTrue(credentials.loadedUsernames.isEmpty())
    assertTrue(auth.loginCalls.isEmpty())
  }

  @Test
  fun `login without remember deletes any previously saved secure credential`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth = RecordingAuthService()
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.updateUsername(USERNAME)
    viewModel.updatePassword(PASSWORD)
    viewModel.updateRememberUsername(false)

    viewModel.login()
    advanceUntilIdle()

    assertEquals(listOf(USERNAME), credentials.deletedUsernames)
    assertTrue(credentials.savedCredentials.isEmpty())
    assertFalse(CredentialStore.isRememberUsername())
  }

  @Test
  fun `missing secure credential does not submit or retry`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(null)
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh")
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.initializeApp()
    advanceUntilIdle()

    assertEquals(1, credentials.loadedUsernames.size)
    assertTrue(auth.loginCalls.isEmpty())
  }

  @Test
  fun `missing remembered credential falls back to a clean manual login form`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(null)
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh")
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertNull(viewModel.uiState.value.error)
    assertEquals(USERNAME, viewModel.loginForm.value.username)
    assertFalse(viewModel.loginForm.value.rememberUsername)
    assertFalse(CredentialStore.isRememberUsername())
    assertTrue(CredentialStore.getUsername() == null)
    assertEquals(listOf(USERNAME), credentials.deletedUsernames)
  }

  @Test
  fun `failed unremembered login clears old remembered account and credential`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            loginFailure =
                ApiCallException(
                    "invalid credentials",
                    HttpStatusCode.Unauthorized,
                    "invalid_credentials",
                )
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.updateUsername("another-account")
    viewModel.updatePassword("wrong-password")
    viewModel.updateRememberUsername(false)

    viewModel.login()
    advanceUntilIdle()

    assertFalse(CredentialStore.isRememberUsername())
    assertTrue(CredentialStore.getUsername() == null)
    assertTrue(credentials.deletedUsernames.contains(USERNAME))
    assertEquals("another-account", viewModel.loginForm.value.username)
    assertFalse(viewModel.loginForm.value.rememberUsername)
    assertFalse(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `preload failures do not expose upstream error text`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val auth =
        RecordingAuthService(
            preloadFailure = IllegalStateException("sensitive upstream message"),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = FakeCredentialStorage(null),
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertEquals("无法加载登录状态，请稍后重试", viewModel.uiState.value.error)
  }

  @Test
  fun `expired foreground session restores with a fresh preload`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            persistedSession = true,
            statusSequence =
                mutableListOf(
                    Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                    Result.failure(
                        ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated")
                    ),
                ),
            preload =
                LoginPreloadResponse(captchaRequired = false, execution = "fresh-after-expiry"),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(2, auth.statusCalls)
    assertEquals(1, auth.preloadCalls)
    assertEquals(1, auth.loginCalls.size)
    assertEquals("fresh-after-expiry", auth.loginCalls.single().execution)
    assertTrue(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `expired unremembered main session clears secure credential and session residues`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
        val auth =
            RecordingAuthService(
                persistedSession = true,
                statusSequence =
                    mutableListOf(
                        Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                        Result.failure(
                            ApiCallException(
                                "expired",
                                HttpStatusCode.Unauthorized,
                                "unauthenticated",
                            )
                        ),
                    ),
                preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
            )
        val viewModel =
            AuthViewModel(
                authService = auth,
                userService = StubUserService(),
                secureCredentialStorage = credentials,
            )

        viewModel.initializeApp()
        advanceUntilIdle()
        TdCampusCredentials.setAuthenticated(USERNAME, PASSWORD)
        val previousFinanceSession = CampusFinanceSession.current()
        viewModel.validateSession()
        advanceUntilIdle()

        assertEquals(listOf(USERNAME), credentials.deletedUsernames)
        assertEquals(1, auth.clearStoredSessionCalls)
        assertNull(TdCampusCredentials.current())
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertFalse(previousFinanceSession === CampusFinanceSession.current())
      }

  @Test
  fun `expired unremembered session does not trust restored preload user`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            persistedSession = true,
            statusSequence =
                mutableListOf(
                    Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                    Result.failure(
                        ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated")
                    ),
                ),
            preload = LoginPreloadResponse(captchaRequired = false, userData = USER),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.updateRememberUsername(false)
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(
        2,
        auth.clearStoredSessionCalls,
        "A preload that restored a user must be cleared again before manual login",
    )
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertNull(viewModel.uiState.value.userData)
    assertEquals("会话已过期，请重新登录", viewModel.uiState.value.error)
    assertTrue(credentials.loadedUsernames.isEmpty())
    assertTrue(auth.loginCalls.isEmpty())
  }

  @Test
  fun `expired remembered session with missing secure credential shows clean manual login`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        rememberUsername()
        val credentials = FakeCredentialStorage(null)
        val auth =
            RecordingAuthService(
                persistedSession = true,
                statusSequence =
                    mutableListOf(
                        Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                        Result.failure(
                            ApiCallException(
                                "expired",
                                HttpStatusCode.Unauthorized,
                                "unauthenticated",
                            )
                        ),
                    ),
                preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
            )
        val viewModel =
            AuthViewModel(
                authService = auth,
                userService = StubUserService(),
                secureCredentialStorage = credentials,
            )
        viewModel.initializeApp()
        advanceUntilIdle()
        viewModel.validateSession()
        advanceUntilIdle()

        assertEquals(1, auth.clearStoredSessionCalls)
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.loginForm.value.rememberUsername)
        assertFalse(CredentialStore.isRememberUsername())
        assertEquals(listOf(USERNAME), credentials.loadedUsernames)
        assertEquals(listOf(USERNAME), credentials.deletedUsernames)
        assertTrue(auth.loginCalls.isEmpty())
      }

  @Test
  fun `expired remembered session with invalid credentials exits with prompt`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        expiredRecordingAuth(
            loginFailure =
                ApiCallException(
                    "invalid credentials",
                    HttpStatusCode.Unauthorized,
                    "invalid_credentials",
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(1, auth.clearStoredSessionCalls)
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertFalse(viewModel.uiState.value.isRecoveringSession)
    assertNull(viewModel.uiState.value.userData)
    assertEquals("账号或密码错误，请重试", viewModel.uiState.value.error)
    assertEquals(1, auth.loginCalls.size)
  }

  @Test
  fun `expired remembered session network login failure exits with prompt`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        expiredRecordingAuth(
            loginFailure =
                ApiCallException(
                    "network unavailable",
                    HttpStatusCode.ServiceUnavailable,
                    "network_error",
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(1, auth.clearStoredSessionCalls)
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertFalse(viewModel.uiState.value.isRecoveringSession)
    assertNull(viewModel.uiState.value.userData)
    assertEquals("自动登录失败，请手动登录", viewModel.uiState.value.error)
    assertEquals(1, auth.loginCalls.size)
  }

  @Test
  fun `expired remembered session requiring mfa exits with prompt`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        expiredRecordingAuth(
            loginFailure =
                ApiCallException(
                    "second factor required",
                    HttpStatusCode.Forbidden,
                    "mfa_required",
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(1, auth.clearStoredSessionCalls)
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertFalse(viewModel.uiState.value.isRecoveringSession)
    assertNull(viewModel.uiState.value.userData)
    assertEquals("自动登录失败，请手动登录", viewModel.uiState.value.error)
  }

  @Test
  fun `expired remembered session requiring captcha exits to captcha ui`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        expiredRecordingAuth(
            loginFailure =
                CaptchaRequiredClientException(
                    CaptchaInfo("challenge", imageUrl = "captcha-image"),
                    "captcha-execution",
                    "captcha needed",
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(1, auth.clearStoredSessionCalls)
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertFalse(viewModel.uiState.value.isRecoveringSession)
    assertNull(viewModel.uiState.value.userData)
    assertTrue(viewModel.uiState.value.captchaRequired)
    assertEquals("captcha-execution", viewModel.uiState.value.execution)
  }

  @Test
  fun `remembered expiry keeps original user in noninteractive recovery state`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val preloadGate = CompletableDeferred<Unit>()
    val loginGate = CompletableDeferred<Unit>()
    val auth = expiredRecordingAuth(preloadGate = preloadGate, loginGate = loginGate)
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()

    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertTrue(viewModel.uiState.value.isRecoveringSession)
    assertEquals(USER, viewModel.uiState.value.userData)
    assertNull(viewModel.uiState.value.accessToken)

    preloadGate.complete(Unit)
    runCurrent()
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertTrue(viewModel.uiState.value.isRecoveringSession)
    assertEquals(USER, viewModel.uiState.value.userData)
    assertNull(viewModel.uiState.value.accessToken)

    loginGate.complete(Unit)
    advanceUntilIdle()

    assertTrue(viewModel.uiState.value.isLoggedIn)
    assertFalse(viewModel.uiState.value.isRecoveringSession)
    assertEquals(USER, viewModel.uiState.value.userData)
  }

  @Test
  fun `concurrent expired session validation only clears and recovers once`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth = expiredRecordingAuth()
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.initializeApp()
    advanceUntilIdle()
    auth.statusGate = CompletableDeferred()

    viewModel.validateSession()
    viewModel.validateSession()
    auth.statusGate?.complete(Unit)
    advanceUntilIdle()

    assertEquals(2, auth.statusCalls)
    assertEquals(1, auth.clearStoredSessionCalls)
    assertEquals(1, auth.loginCalls.size)
    assertTrue(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `expired event after recovery validates new session without clearing it`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth = expiredRecordingAuth()
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(3, auth.statusCalls)
    assertEquals(1, auth.clearStoredSessionCalls)
    assertEquals(1, auth.loginCalls.size)
    assertTrue(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `business expiry immediately after successful status does not restart recovery loop`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        rememberUsername()
        val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
        val expired =
            Result.failure<SessionStatusResponse>(
                ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated"),
            )
        val auth =
            RecordingAuthService(
                persistedSession = true,
                statusSequence =
                    mutableListOf(
                        Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                        expired,
                        Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                        expired,
                    ),
                preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
            )
        val viewModel =
            AuthViewModel(
                authService = auth,
                userService = StubUserService(),
                secureCredentialStorage = credentials,
            )
        viewModel.initializeApp()
        advanceUntilIdle()
        viewModel.validateSession()
        advanceUntilIdle()
        viewModel.validateSession()
        advanceUntilIdle()
        viewModel.validateSession()
        advanceUntilIdle()

        assertEquals(4, auth.statusCalls)
        assertEquals(2, auth.clearStoredSessionCalls)
        assertEquals(1, auth.loginCalls.size)
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertEquals("会话已过期，请重新登录", viewModel.uiState.value.error)
      }

  @Test
  fun `healthy status does not immediately reset automatic recovery cooldown`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    var nowMillis = 1_000L
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val expired =
        Result.failure<SessionStatusResponse>(
            ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated"),
        )
    val auth =
        RecordingAuthService(
            persistedSession = true,
            statusSequence =
                mutableListOf(
                    Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                    expired,
                    Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                    expired,
                ),
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
            nowMonotonicMillis = { nowMillis },
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(4, auth.statusCalls)
    assertEquals(1, auth.loginCalls.size)
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertEquals("会话已过期，请重新登录", viewModel.uiState.value.error)
  }

  @Test
  fun `healthy session may recover again after cooldown`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    var nowMillis = 1_000L
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val expired =
        Result.failure<SessionStatusResponse>(
            ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated"),
        )
    val auth =
        RecordingAuthService(
            persistedSession = true,
            statusSequence =
                mutableListOf(
                    Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                    expired,
                    Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                    expired,
                    Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                ),
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
            nowMonotonicMillis = { nowMillis },
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()
    nowMillis += 30 * 60 * 1000L
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(4, auth.statusCalls)
    assertEquals(2, auth.loginCalls.size)
    assertTrue(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `cooldown alone without healthy status does not allow another recovery`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    var nowMillis = 1_000L
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val expired =
        Result.failure<SessionStatusResponse>(
            ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated"),
        )
    val auth =
        RecordingAuthService(
            persistedSession = true,
            statusSequence =
                mutableListOf(
                    Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                    expired,
                    expired,
                ),
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
            nowMonotonicMillis = { nowMillis },
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()
    nowMillis += 30 * 60 * 1000L
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(3, auth.statusCalls)
    assertEquals(1, auth.loginCalls.size)
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertEquals("会话已过期，请重新登录", viewModel.uiState.value.error)
  }

  @Test
  fun `consecutive expiry after automatic recovery does not repeat password login`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val expired =
        Result.failure<SessionStatusResponse>(
            ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated"),
        )
    val auth =
        RecordingAuthService(
            persistedSession = true,
            statusSequence =
                mutableListOf(
                    Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                    expired,
                    expired,
                ),
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(3, auth.statusCalls)
    assertEquals(2, auth.preloadCalls)
    assertEquals(1, auth.loginCalls.size)
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertEquals("会话已过期，请重新登录", viewModel.uiState.value.error)
  }

  @Test
  fun `restored sso preload resets automatic login recovery allowance`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            persistedSession = true,
            status =
                Result.failure(
                    ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated")
                ),
            preloadSequence =
                mutableListOf(
                    LoginPreloadResponse(captchaRequired = false, userData = USER),
                    LoginPreloadResponse(captchaRequired = false, execution = "fresh-after-expiry"),
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.validateSession()
    advanceUntilIdle()

    assertEquals(2, auth.preloadCalls)
    assertEquals(1, auth.loginCalls.size)
    assertEquals("fresh-after-expiry", auth.loginCalls.single().execution)
    assertTrue(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `non unauthenticated status failure does not start credential recovery`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            persistedSession = true,
            status =
                Result.failure(
                    ApiCallException("forbidden", HttpStatusCode.Forbidden, "forbidden")
                ),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertEquals(0, auth.preloadCalls)
    assertTrue(auth.loginCalls.isEmpty())
    assertTrue(credentials.loadedUsernames.isEmpty())
  }

  @Test
  fun `manual logout suppresses automatic credential login`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            persistedSession = true,
            status = Result.success(SessionStatusResponse(USER, "last", "authenticated")),
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()
    viewModel.logout()
    advanceUntilIdle()

    assertTrue(auth.loginCalls.isEmpty())
    assertTrue(credentials.loadedUsernames.isEmpty())
    assertFalse(viewModel.uiState.value.isLoggedIn)
  }

  @Test
  fun `concurrent initialization shares one automatic login attempt`() = runTest {
    ConnectionModeStore.save(ConnectionMode.WEBVPN)
    rememberUsername()
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val preloadGate = CompletableDeferred<Unit>()
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh-execution"),
            preloadGate = preloadGate,
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    viewModel.initializeApp()
    preloadGate.complete(Unit)
    advanceUntilIdle()

    assertEquals(1, auth.preloadCalls)
    assertEquals(1, auth.loginCalls.size)
  }

  @Test
  fun `manual account edit blocks initialization and old account mode restoration`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val auth = RecordingAuthService()
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD)),
        )
    viewModel.updateUsername("new-fixture-account")
    viewModel.initializeApp()
    advanceUntilIdle()
    assertEquals(0, auth.preloadCalls)
    viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()
    assertEquals(0, auth.preloadCalls)
    assertTrue(auth.loginCalls.isEmpty())
    assertEquals("new-fixture-account", viewModel.loginForm.value.username)
    assertTrue(viewModel.hasPendingAccountEdit)
  }

  @Test
  fun `editing username cancels pending automatic restoration without loading old credentials`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        rememberUsername()
        val gate = CompletableDeferred<Unit>()
        val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
        val auth = RecordingAuthService(preloadGate = gate)
        val viewModel =
            AuthViewModel(
                authService = auth,
                userService = StubUserService(),
                secureCredentialStorage = credentials,
            )
        viewModel.initializeApp()
        runCurrent()
        viewModel.updateUsername("new-fixture-account")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("new-fixture-account", viewModel.loginForm.value.username)
        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(credentials.loadedUsernames.isEmpty())
        assertTrue(auth.loginCalls.isEmpty())
        assertEquals(1L, viewModel.accountEditRevision.value)
        assertTrue(viewModel.hasPendingAccountEdit)
        viewModel.updateUsername("new-fixture-account")
        assertEquals(1L, viewModel.accountEditRevision.value)
      }

  @Test
  fun `edited account cannot be replaced by a late non cancellable login result`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val gate = CompletableDeferred<Unit>()
    val auth = RecordingAuthService(loginGate = gate, ignoreLoginCancellation = true)
    val credentials = FakeCredentialStorage(null)
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )
    viewModel.updateUsername(USERNAME)
    viewModel.updatePassword(PASSWORD)
    viewModel.login()
    runCurrent()
    viewModel.updateUsername("new-fixture-account")
    gate.complete(Unit)
    advanceUntilIdle()
    assertEquals("new-fixture-account", viewModel.loginForm.value.username)
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertTrue(credentials.savedCredentials.isEmpty())
    assertEquals(1, auth.loginCalls.size)
  }

  @Test
  fun `manual account change clears pending account edit before login`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    val next = "new-fixture-account"
    val auth = RecordingAuthService(loginUser = UserData("Fixture", next))
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = FakeCredentialStorage(null),
        )
    viewModel.updateUsername(next)
    viewModel.updatePassword(PASSWORD)
    viewModel.login()
    advanceUntilIdle()
    assertFalse(viewModel.hasPendingAccountEdit)
  }

  @Test
  fun `remembered login alias survives restart when server returns a different school id`() =
      runTest {
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        val records = mutableMapOf<String, CampusCredential>()
        val storage =
            object : CampusCredentialStorage {
              override suspend fun save(credential: CampusCredential) {
                records[credential.username] = credential
              }

              override suspend fun load(username: String) = records[username]

              override suspend fun delete(username: String) {
                records.remove(username)
              }
            }
        val identity = UserData("Fixture", "fixture-school-id")
        val first =
            AuthViewModel(RecordingAuthService(loginUser = identity), StubUserService(), storage)
        first.updateUsername("fixture-alias")
        first.updatePassword(PASSWORD)
        first.updateRememberUsername(true)
        first.login()
        advanceUntilIdle()
        assertTrue(first.uiState.value.isLoggedIn)
        TdCampusCredentials.clearSession()
        val auth = RecordingAuthService(loginUser = identity)
        val restarted = AuthViewModel(auth, StubUserService(), storage)
        restarted.initializeApp()
        advanceUntilIdle()
        assertTrue(restarted.uiState.value.isLoggedIn)
        assertEquals(listOf("fixture-alias"), auth.loginCalls.map { it.username })
        assertEquals("fixture-school-id", restarted.uiState.value.userData?.schoolid)
      }

  @Test
  fun `mode switch uses main login principal after TD session is cleared`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val auth = RecordingAuthService(loginUser = UserData("Fixture", "fixture-school-id"))
    val viewModel = AuthViewModel(auth, StubUserService(), FakeCredentialStorage(null))
    viewModel.updateUsername("fixture-alias")
    viewModel.updatePassword(PASSWORD)
    viewModel.login()
    advanceUntilIdle()
    auth.loginCalls.clear()
    TdCampusCredentials.clearSession()
    viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.isLoggedIn)
    assertEquals(listOf("fixture-alias"), auth.loginCalls.map { it.username })
  }

  @Test
  fun `mode switch accepts the same verified school id for a remembered alias`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val identity = UserData("Fixture", "fixture-school-id")
    val auth =
        RecordingAuthService(
            loginUser = identity,
            preload = LoginPreloadResponse(false, userData = identity),
        )
    val viewModel = AuthViewModel(auth, StubUserService(), FakeCredentialStorage(null))
    viewModel.updateUsername("fixture-alias")
    viewModel.updatePassword(PASSWORD)
    viewModel.updateRememberUsername(true)
    viewModel.login()
    advanceUntilIdle()
    auth.loginCalls.clear()
    viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.isLoggedIn)
    assertEquals(identity, viewModel.uiState.value.userData)
    assertTrue(auth.loginCalls.isEmpty())
  }

  @Test
  fun `unremembered alias login deletes the legacy canonical credential too`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val storage = FakeCredentialStorage(null)
    val viewModel =
        AuthViewModel(
            RecordingAuthService(loginUser = UserData("Fixture", "fixture-school-id")),
            StubUserService(),
            storage,
        )
    viewModel.updateUsername("fixture-alias")
    viewModel.updatePassword(PASSWORD)
    viewModel.login()
    advanceUntilIdle()
    assertTrue(storage.deletedUsernames.contains("fixture-alias"))
    assertTrue(storage.deletedUsernames.contains("fixture-school-id"))
    assertFalse(CredentialStore.isRememberUsername())
  }

  @Test
  fun `unreadable secure storage falls back safely on startup and on mode switch`() = runTest {
    for (switchMode in listOf(false, true)) {
      ConnectionModeStore.save(ConnectionMode.DIRECT)
      rememberUsername()
      val storage =
          object : CampusCredentialStorage {
            override suspend fun save(credential: CampusCredential) = Unit

            override suspend fun load(username: String): CampusCredential? =
                error("Synthetic damaged storage")

            override suspend fun delete(username: String) = Unit
          }
      val auth = RecordingAuthService()
      val viewModel = AuthViewModel(auth, StubUserService(), storage)
      if (switchMode) viewModel.switchConnectionMode(ConnectionMode.WEBVPN)
      else viewModel.initializeApp()
      advanceUntilIdle()
      assertFalse(viewModel.uiState.value.isLoggedIn)
      assertFalse(viewModel.uiState.value.isLoading)
      assertFalse(viewModel.uiState.value.isRecoveringSession)
      assertTrue(auth.loginCalls.isEmpty())
      assertFalse(CredentialStore.isRememberUsername())
      assertEquals("", viewModel.loginForm.value.password)
    }
  }

  @Test
  fun `logout removes both remembered alias and legacy canonical credentials`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    val storage = FakeCredentialStorage(null)
    val viewModel =
        AuthViewModel(
            RecordingAuthService(loginUser = UserData("Fixture", "fixture-school-id")),
            StubUserService(),
            storage,
        )
    viewModel.updateUsername("fixture-alias")
    viewModel.updatePassword(PASSWORD)
    viewModel.updateRememberUsername(true)
    viewModel.login()
    advanceUntilIdle()
    viewModel.logout()
    advanceUntilIdle()
    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertTrue(storage.deletedUsernames.contains("fixture-alias"))
    assertTrue(storage.deletedUsernames.contains("fixture-school-id"))
  }

  @Test
  fun `disabled automatic login keeps the remembered account on the manual form`() = runTest {
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    rememberUsername()
    CredentialStore.setAutoLogin(false)
    val credentials = FakeCredentialStorage(CampusCredential(USERNAME, PASSWORD))
    val auth =
        RecordingAuthService(
            preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh")
        )
    val viewModel =
        AuthViewModel(
            authService = auth,
            userService = StubUserService(),
            secureCredentialStorage = credentials,
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertFalse(viewModel.uiState.value.isLoggedIn)
    assertEquals(USERNAME, viewModel.loginForm.value.username)
    assertTrue(auth.loginCalls.isEmpty())
    assertTrue(credentials.loadedUsernames.isEmpty())
  }

  private fun rememberUsername() {
    CredentialStore.setRememberUsername(true)
    CredentialStore.saveUsername(USERNAME)
  }

  private fun expiredRecordingAuth(
      loginFailure: Throwable? = null,
      preloadGate: CompletableDeferred<Unit>? = null,
      loginGate: CompletableDeferred<Unit>? = null,
  ): RecordingAuthService =
      RecordingAuthService(
          persistedSession = true,
          statusSequence =
              mutableListOf(
                  Result.success(SessionStatusResponse(USER, "last", "authenticated")),
                  Result.failure(
                      ApiCallException("expired", HttpStatusCode.Unauthorized, "unauthenticated")
                  ),
                  Result.success(SessionStatusResponse(USER, "last", "authenticated")),
              ),
          preload = LoginPreloadResponse(captchaRequired = false, execution = "fresh"),
          preloadGate = preloadGate,
          loginGate = loginGate,
          loginFailure = loginFailure,
      )

  private class FakeCredentialStorage(private val credential: CampusCredential?) :
      CampusCredentialStorage {
    val loadedUsernames = mutableListOf<String>()
    val savedCredentials = mutableListOf<CampusCredential>()
    val deletedUsernames = mutableListOf<String>()

    override suspend fun save(credential: CampusCredential) {
      savedCredentials += credential
    }

    override suspend fun load(username: String): CampusCredential? {
      loadedUsernames += username
      return credential?.takeIf { it.username == username }
    }

    override suspend fun delete(username: String) {
      deletedUsernames += username
    }
  }

  private data class LoginCall(
      val username: String,
      val password: String,
      val captcha: String?,
      val execution: String?,
  )

  private class RecordingAuthService(
      private val persistedSession: Boolean = false,
      private val status: Result<SessionStatusResponse> =
          Result.failure(IllegalStateException("no session")),
      private val statusSequence: MutableList<Result<SessionStatusResponse>> =
          mutableListOf(status),
      private val preload: LoginPreloadResponse = LoginPreloadResponse(captchaRequired = false),
      private val preloadGate: CompletableDeferred<Unit>? = null,
      var loginGate: CompletableDeferred<Unit>? = null,
      private val loginFailure: Throwable? = null,
      private val preloadSequence: MutableList<LoginPreloadResponse> = mutableListOf(preload),
      private val preloadFailure: Throwable? = null,
      private val ignoreLoginCancellation: Boolean = false,
      private val loginUser: UserData = USER,
  ) : AuthService() {
    var statusCalls = 0
    var clearStoredSessionCalls = 0
    var preloadCalls = 0
    var statusGate: CompletableDeferred<Unit>? = null
    val loginCalls = mutableListOf<LoginCall>()

    override fun hasPersistedSession(): Boolean = persistedSession

    override fun applyStoredTokens() = Unit

    override fun clearStoredSession() {
      clearStoredSessionCalls++
    }

    override suspend fun getAuthStatus(): Result<SessionStatusResponse> {
      statusCalls++
      statusGate?.await()
      return statusSequence.getOrElse(statusCalls - 1) { statusSequence.last() }
    }

    override suspend fun preloadLoginState(): Result<LoginPreloadResponse> {
      preloadCalls++
      preloadGate?.await()
      return if (preloadFailure != null) Result.failure(preloadFailure)
      else Result.success(preloadSequence.getOrElse(preloadCalls - 1) { preloadSequence.last() })
    }

    override suspend fun login(
        username: String,
        password: String,
        captcha: String?,
        execution: String?,
    ): Result<LoginResponse> {
      if (ignoreLoginCancellation) withContext(NonCancellable) { loginGate?.await() }
      else loginGate?.await()
      loginCalls += LoginCall(username, password, captcha, execution)
      return if (loginFailure != null) Result.failure(loginFailure)
      else Result.success(LoginResponse(loginUser, "", "", "", ""))
    }

    override suspend fun logout(): Result<Unit> = Result.success(Unit)
  }

  private class StubUserService : UserService() {
    override suspend fun getUserInfo(): Result<UserInfo> =
        Result.failure(IllegalStateException("not used"))
  }

  private companion object {
    const val USERNAME = "synthetic-user"
    const val PASSWORD = "synthetic-password"
    val USER = UserData("Synthetic User", USERNAME)
  }
}
