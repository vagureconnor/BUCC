package app.bucc.api

import app.bucc.api.core.ApiFactory
import app.bucc.api.core.DefaultApiFactory
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalJudgeApiCache
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.storage.AuthTokensStore
import app.bucc.api.storage.GradeScoreCacheStore
import app.bucc.repository.GlobalTermRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionMode(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
  DIRECT(
      storageKey = "direct",
      displayName = "直连模式",
      description = "全能模式：直连校内服务，支持 TD 打卡和 PatPat。",
  ),
  WEBVPN(
      storageKey = "webvpn",
      displayName = "WebVPN模式",
      description = "通过北航 WebVPN 访问服务；TD 仅支持次数查询，PatPat 暂不可用。",
  );

  companion object {
    const val introduction =
        "直连模式可使用完整校园服务，包括 TD 打卡和 PatPat；WebVPN 模式下部分服务受限：TD 仅支持次数查询，PatPat 暂不可用。"

    fun fromStorageKey(value: String?): ConnectionMode? =
        entries.firstOrNull { it.storageKey == value?.trim()?.lowercase() }
  }

  val usesWebVpnGateway: Boolean
    get() = this == WEBVPN
}

object ConnectionModeStore {
  private const val KEY_CONNECTION_MODE = "connection_mode"
  private var _settings: Settings? = null
  var settings: Settings
    get() = _settings ?: Settings().also { _settings = it }
    set(value) {
      _settings = value
    }

  fun save(mode: ConnectionMode) {
    settings.putString(KEY_CONNECTION_MODE, mode.storageKey)
  }

  fun get(): ConnectionMode? {
    val stored = settings.getStringOrNull(KEY_CONNECTION_MODE) ?: return null
    val mode = ConnectionMode.fromStorageKey(stored)
    if (mode == null) settings.remove(KEY_CONNECTION_MODE)
    return mode
  }

  fun clear() {
    settings.remove(KEY_CONNECTION_MODE)
  }
}

object ConnectionRuntime {
  internal var apiFactoryProvider: () -> ApiFactory = { DefaultApiFactory }

  private val _currentMode = MutableStateFlow(ConnectionModeStore.get())
  val currentModeFlow: StateFlow<ConnectionMode?> = _currentMode.asStateFlow()

  internal fun apiFactory(): ApiFactory = apiFactoryProvider()

  fun availableModes(): List<ConnectionMode> = ConnectionMode.entries

  fun resolveSelectedMode(): ConnectionMode? {
    val allowedModes = availableModes()
    val storedMode = ConnectionModeStore.get()
    val resolvedMode =
        when {
          storedMode != null && storedMode in allowedModes -> storedMode
          allowedModes.size == 1 -> allowedModes.single().also(ConnectionModeStore::save)
          else -> null
        }
    _currentMode.value = resolvedMode
    return resolvedMode
  }

  fun currentMode(): ConnectionMode? = _currentMode.value ?: resolveSelectedMode()

  fun switchMode(mode: ConnectionMode) {
    require(mode in availableModes()) {
      "Connection mode ${mode.name} is not supported on this platform"
    }
    val current = currentMode()
    if (current == mode && ConnectionModeStore.get() == mode) {
      return
    }
    app.bucc.connection.AuthenticationDiagnostics.record(
        app.bucc.connection.AuthenticationDiagnostic(
            mode,
            if (mode == ConnectionMode.WEBVPN)
                app.bucc.connection.AuthenticationPath.OFFICIAL_GATEWAY
            else app.bucc.connection.AuthenticationPath.DIRECT,
            app.bucc.connection.AuthenticationStage.MODE_SWITCH,
        )
    )
    resetSession(preserveModeSessions = true)
    ConnectionModeStore.save(mode)
    _currentMode.value = mode
  }

  fun resetSession(preserveModeSessions: Boolean = false) {
    AuthTokensStore.clearAllScopes()
    GradeScoreCacheStore.clearAllScopes()
    if (!preserveModeSessions) {
      LocalAuthSessionStore.clearAllScopes()
      LocalCookieStore.clearAllScopes()
    }
    DefaultApiFactory.clearCachedBackends()
    LocalUpstreamClientProvider.reset()
    LocalJudgeApiCache.clearAll()
    GlobalTermRepository.instance.clear()
  }

  fun clearSelectedMode() {
    resetSession()
    ConnectionModeStore.clear()
    _currentMode.value = null
  }
}

internal object ModeScopedSessionStore {
  private const val LEGACY_PREFIX = ""

  fun scopedKey(key: String, mode: ConnectionMode? = ConnectionModeStore.get()): String {
    val effectiveMode = mode ?: ConnectionMode.DIRECT
    return "mode_${effectiveMode.storageKey}_$key"
  }

  fun legacyKey(key: String): String = "$LEGACY_PREFIX$key"
}
