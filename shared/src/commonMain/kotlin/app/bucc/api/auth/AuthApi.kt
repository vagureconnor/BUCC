package app.bucc.api.auth

import app.bucc.api.ConnectionRuntime
import app.bucc.model.dto.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/** Signals that a previously established local SSO session was confirmed expired. */
object LocalSessionExpiryEvents {
  private val mutableEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val events: SharedFlow<Unit> = mutableEvents.asSharedFlow()

  internal fun notifyExpired(): Boolean = mutableEvents.tryEmit(Unit)
}

/**
 * 会话状态响应。
 *
 * @property user 用户基本身份信息。
 * @property lastActivity 最后活动时间。
 * @property authenticatedAt 认证时间。
 */
@Serializable
data class SessionStatusResponse(
    val user: UserData,
    val lastActivity: String,
    val authenticatedAt: String,
)

interface AuthServiceBackend {
  fun hasPersistedSession(): Boolean

  fun applyStoredSession()

  fun clearStoredSession()

  suspend fun preloadLoginState(): Result<LoginPreloadResponse>

  suspend fun login(
      username: String,
      password: String,
      captcha: String?,
      execution: String?,
  ): Result<LoginResponse>

  suspend fun getAuthStatus(): Result<SessionStatusResponse>

  suspend fun logout(): Result<Unit>
}

interface UserServiceBackend {
  suspend fun getUserInfo(): Result<UserInfo>
}

/**
 * 认证服务，负责登录、预加载、注销及会话状态查询。
 *
 * 当前认证模式的入口服务。
 */
open class AuthService(
    private val backendProvider: () -> AuthServiceBackend = {
      ConnectionRuntime.apiFactory().authService()
    }
) {
  internal constructor(backend: AuthServiceBackend) : this({ backend })

  private fun currentBackend(): AuthServiceBackend = backendProvider()

  open fun hasPersistedSession(): Boolean = currentBackend().hasPersistedSession()

  /** 恢复当前认证模式下保存的本地会话。 */
  open fun applyStoredTokens() {
    currentBackend().applyStoredSession()
  }

  open fun clearStoredSession() {
    currentBackend().clearStoredSession()
  }

  /**
   * 预加载登录状态。 为当前客户端创建或关联专属会话，并获取登录所需的附加信息（如验证码）。
   *
   * @return 预加载结果，包含验证码信息或已登录的令牌。
   */
  open suspend fun preloadLoginState(): Result<LoginPreloadResponse> {
    return currentBackend().preloadLoginState()
  }

  /**
   * 执行用户登录。 使用 preload 时创建的会话和执行标识（execution）进行认证。
   *
   * @param username 用户名（学号）。
   * @param password 密码。
   * @param captcha 验证码（如果需要）。
   * @param execution SSO 流程执行标识。
   * @return 登录结果，成功则返回登录响应。
   */
  open suspend fun login(
      username: String,
      password: String,
      captcha: String? = null,
      execution: String? = null,
  ): Result<LoginResponse> {
    return currentBackend().login(username, password, captcha, execution)
  }

  /**
   * 查询当前会话状态。
   *
   * @return 包含用户信息和活动时间的 SessionStatusResponse。
   */
  open suspend fun getAuthStatus(): Result<SessionStatusResponse> {
    return currentBackend().getAuthStatus()
  }

  /**
   * 注销当前用户并清理本地会话状态。
   *
   * @return 注销操作结果。
   */
  open suspend fun logout(): Result<Unit> {
    return currentBackend().logout()
  }
}

/** 用户资料服务，委托给当前认证模式对应的实现。 */
open class UserService(
    private val backendProvider: () -> UserServiceBackend = {
      ConnectionRuntime.apiFactory().userService()
    }
) {
  internal constructor(backend: UserServiceBackend) : this({ backend })

  private fun currentBackend(): UserServiceBackend = backendProvider()

  open suspend fun getUserInfo(): Result<UserInfo> = currentBackend().getUserInfo()
}
