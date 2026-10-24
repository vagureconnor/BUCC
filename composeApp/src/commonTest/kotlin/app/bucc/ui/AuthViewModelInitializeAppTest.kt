package app.bucc.ui

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import app.bucc.api.auth.ApiCallException
import app.bucc.api.auth.AuthService
import app.bucc.api.auth.SessionStatusResponse
import app.bucc.api.auth.UserService
import app.bucc.api.storage.AuthTokensStore
import app.bucc.api.storage.CredentialStore
import app.bucc.api.storage.StoredAuthTokens
import app.bucc.model.dto.LoginPreloadResponse
import app.bucc.model.dto.LoginResponse
import app.bucc.model.dto.UserInfo
import app.bucc.td.TdCampusCredentials
import app.bucc.ui.screens.auth.AuthViewModel
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelInitializeAppTest {
  @BeforeTest
  fun setup() {
    AuthTokensStore.clear()
    CredentialStore.clear()
    TdCampusCredentials.clearSession()
    ConnectionModeStore.clear()
    ConnectionRuntime.clearSelectedMode()
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
    CredentialStore.clear()
    TdCampusCredentials.clearSession()
    AuthTokensStore.clear()
    ConnectionModeStore.clear()
    ConnectionRuntime.clearSelectedMode()
  }

  @Test
  fun `initializeApp is skipped until connection mode is selected`() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    val authService = TimeoutAuthService()
    val viewModel =
        AuthViewModel(
            authService = authService,
            userService = StubUserService(),
        )

    viewModel.initializeApp()
    advanceUntilIdle()

    assertEquals(0, authService.statusCalls)
    assertEquals(0, authService.preloadCalls)
    assertEquals(0, authService.loginCalls)
    assertFalse(authService.applyStoredTokensCalled)
    assertFalse(viewModel.uiState.value.isLoading)
    assertNull(viewModel.uiState.value.error)
  }

  @Test
  fun `initializeApp preserves tokens and skips relogin when auth status times out upstream`() =
      runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        ConnectionModeStore.save(ConnectionMode.WEBVPN)
        AuthTokensStore.save(
            StoredAuthTokens(
                accessToken = "stale-access-token",
                refreshToken = "stale-refresh-token",
            )
        )
        assertNotNull(AuthTokensStore.get())

        val authService = TimeoutAuthService()
        val viewModel =
            AuthViewModel(
                authService = authService,
                userService = StubUserService(),
            )

        viewModel.initializeApp()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, authService.statusCalls)
        assertEquals(0, authService.preloadCalls)
        assertEquals(0, authService.loginCalls)
        assertTrue(authService.applyStoredTokensCalled)
        assertFalse(state.isLoading)
        assertFalse(state.isLoggedIn)
        assertNull(state.userData)
        assertEquals("认证服务响应超时，请稍后重试", state.error)
        assertNotNull(AuthTokensStore.get())
        assertEquals("stale-access-token", AuthTokensStore.get()?.accessToken)
        assertEquals("stale-refresh-token", AuthTokensStore.get()?.refreshToken)
      }

  @Test
  fun `initializeApp relies on auth service persisted session contract instead of relay tokens`() =
      runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        val authService = TimeoutAuthService(hasPersistedSession = true)
        val viewModel =
            AuthViewModel(
                authService = authService,
                userService = StubUserService(),
            )

        viewModel.initializeApp()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, authService.statusCalls)
        assertEquals(0, authService.preloadCalls)
        assertEquals(0, authService.loginCalls)
        assertTrue(authService.applyStoredTokensCalled)
        assertFalse(state.isLoading)
        assertEquals("认证服务响应超时，请稍后重试", state.error)
      }

  @Test
  fun `preloadLoginState treats local mode restored session as logged in without relay token`() =
      runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        ConnectionModeStore.save(ConnectionMode.DIRECT)
        val authService =
            PreloadedSessionAuthService(
                LoginPreloadResponse(
                    captchaRequired = false,
                    userData = app.bucc.model.dto.UserData("Test User", "22373333"),
                )
            )
        val viewModel =
            AuthViewModel(
                authService = authService,
                userService = StubUserService(),
            )

        viewModel.preloadLoginState()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, authService.preloadCalls)
        assertTrue(state.isLoggedIn)
        assertEquals("Test User", state.userData?.name)
        assertNull(state.accessToken)
        assertFalse(state.isPreloading)
      }

  private class TimeoutAuthService(
      private val hasPersistedSession: Boolean = AuthTokensStore.get() != null
  ) : AuthService() {
    var applyStoredTokensCalled = false
      private set

    var statusCalls = 0
      private set

    var preloadCalls = 0
      private set

    var loginCalls = 0
      private set

    override fun hasPersistedSession(): Boolean = hasPersistedSession

    override fun applyStoredTokens() {
      applyStoredTokensCalled = true
    }

    override suspend fun preloadLoginState(): Result<LoginPreloadResponse> {
      preloadCalls++
      return Result.failure(IllegalStateException("preload should not be called"))
    }

    override suspend fun login(
        username: String,
        password: String,
        captcha: String?,
        execution: String?,
    ): Result<LoginResponse> {
      loginCalls++
      return Result.failure(IllegalStateException("login should not be called"))
    }

    override suspend fun getAuthStatus(): Result<SessionStatusResponse> {
      statusCalls++
      return Result.failure(
          ApiCallException(
              message = "认证服务响应超时，请稍后重试",
              status = HttpStatusCode.ServiceUnavailable,
              code = "auth_upstream_timeout",
          )
      )
    }
  }

  private class PreloadedSessionAuthService(private val preloadResponse: LoginPreloadResponse) :
      AuthService() {
    var preloadCalls = 0
      private set

    override suspend fun preloadLoginState(): Result<LoginPreloadResponse> {
      preloadCalls++
      return Result.success(preloadResponse)
    }

    override suspend fun login(
        username: String,
        password: String,
        captcha: String?,
        execution: String?,
    ): Result<LoginResponse> {
      return Result.failure(IllegalStateException("login should not be called"))
    }

    override suspend fun getAuthStatus(): Result<SessionStatusResponse> {
      return Result.failure(IllegalStateException("status should not be called"))
    }
  }

  private class StubUserService : UserService() {
    override suspend fun getUserInfo(): Result<UserInfo> {
      return Result.failure(IllegalStateException("user info should not be requested"))
    }
  }
}
