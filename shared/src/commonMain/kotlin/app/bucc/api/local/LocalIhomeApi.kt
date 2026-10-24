package app.bucc.api.local

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.auth.ApiCallException
import app.bucc.api.feature.*
import app.bucc.model.dto.*
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

internal class LocalIhomeApiBackend : IhomeApiBackend {
  private val mutex = Mutex()
  private var cachedKey: Triple<String, String, ConnectionMode>? = null
  private var cached: IhomeUpstreamClient? = null

  fun clearCache() {
    cached?.close()
    cached = null
    cachedKey = null
  }

  private suspend fun current(): IhomeUpstreamClient =
      mutex.withLock {
        val session = LocalAuthSessionStore.get() ?: throw localUnauthenticatedApiException()
        val mode = ConnectionRuntime.currentMode() ?: throw localUnauthenticatedApiException()
        val key = Triple(session.username, session.authenticatedAt, mode)
        if (key != cachedKey) {
          clearCache()
          cached =
              IhomeUpstreamClient(
                  LocalUpstreamClientProvider.shared(),
                  route = ::localUpstreamUrl,
                  unroute = LocalWebVpnSupport::fromWebVpnUrl,
              )
          cachedKey = key
        }
        cached!!
      }

  private suspend fun <T> call(block: suspend IhomeUpstreamClient.() -> T): Result<T> =
      try {
        Result.success(current().block())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(mapIhomeFailure(e))
      }

  override suspend fun getPage(query: IhomeQuery) = call { getPage(query) }

  override suspend fun getDetail(id: Long, mine: Boolean) = call { getDetail(id, mine) }

  override suspend fun getConfig() = call { getConfig() }

  override suspend fun getNotices() = call { getNotices() }

  override suspend fun getNotice(id: Long) = call { getNotice(id) }

  override suspend fun setReaction(id: Long, request: IhomeReactionRequest) = call {
    setReaction(id, request)
  }
}

/** Converts failures to safe, stage-specific UI errors without surfacing exception text. */
internal fun mapIhomeFailure(error: Exception): ApiCallException =
    when (error) {
      is ApiCallException -> {
        val statusSuffix = error.status?.let { "（HTTP ${it.value}）" }.orEmpty()
        when (error.code) {
          "unauthenticated",
          "unauthorized" ->
              ApiCallException(
                  "主账号登录状态已失效$statusSuffix，请重新登录后重试",
                  status = error.status,
                  code = "ihome_session",
              )
          "auth_upstream_timeout" ->
              ApiCallException(
                  "统一认证服务响应超时$statusSuffix，请稍后重试",
                  status = error.status,
                  code = "ihome_auth_timeout",
              )
          else ->
              ApiCallException(
                  "iHome 本机认证状态异常$statusSuffix，请重新登录后重试",
                  status = error.status,
                  code = "ihome_local_session",
              )
        }
      }
      is IhomeException ->
          when (error.code) {
            "ihome_auth" -> ApiCallException("iHome 认证阶段失败，请重新登录 BUCC 后重试", code = error.code)
            "ihome_upstream" -> ApiCallException("iHome 上游服务暂时不可用，请稍后重试", code = error.code)
            "ihome_protocol" -> ApiCallException("iHome 响应格式无法识别，请稍后重试", code = error.code)
            "ihome_business" -> ApiCallException("iHome 暂时无法完成此操作，请稍后重试", code = error.code)
            "ihome_disabled" -> ApiCallException("iHome 当前未开放此功能", code = error.code)
            else -> ApiCallException("iHome 请求在服务处理阶段失败，请稍后重试", code = "ihome_service")
          }
      is HttpRequestTimeoutException,
      is ConnectTimeoutException,
      is SocketTimeoutException -> ApiCallException("iHome 请求超时，请检查网络后重试", code = "ihome_timeout")
      is SerializationException -> ApiCallException("iHome 响应格式无法识别，请稍后重试", code = "ihome_protocol")
      else -> ApiCallException("iHome 请求在网络或响应处理阶段失败，请稍后重试", code = "ihome_error")
    }
