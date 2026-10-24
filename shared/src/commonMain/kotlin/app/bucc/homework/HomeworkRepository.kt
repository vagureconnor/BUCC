package app.bucc.homework

import app.bucc.repository.ScheduleSnapshotStorage
import app.bucc.repository.ScheduleStore
import app.bucc.security.CampusCredentialStorage
import com.russhwolf.settings.Settings
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class HomeworkSnapshot(val items: List<HomeworkItem>, val updatedAt: String)

data class HomeworkSourceState(
    val items: List<HomeworkItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val updatedAt: String? = null,
)

data class HomeworkState(val sources: Map<HomeworkSource, HomeworkSourceState> = emptyMap()) {
  val items: List<HomeworkItem>
    get() = sources.values.flatMap { it.items }

  val isLoading: Boolean
    get() = sources.values.any { it.isLoading }
}

/** Every source refresh replaces only its own successful snapshot. */
class HomeworkRepository(
    val account: String,
    private val adapters: List<HomeworkAdapter>,
    private val settings: Settings = ScheduleStore.settings,
    private val credentialStorage: CampusCredentialStorage = PlatformHomeworkCredentialStorage,
    private val isCurrentAccount: () -> Boolean = { ScheduleStore.account() == account },
) {
  constructor(
      account: String,
      adapters: List<HomeworkAdapter>,
      settings: Settings,
      isCurrentAccount: () -> Boolean,
  ) : this(account, adapters, settings, PlatformHomeworkCredentialStorage, isCurrentAccount)

  private val json = Json { ignoreUnknownKeys = true }
  // Cross-semester snapshots created before the current-semester policy must not reappear.
  private val snapshots = ScheduleSnapshotStorage(settings, "homework_current")
  private val completions = ScheduleSnapshotStorage(settings, "homework_completed")

  private fun cacheAccount(source: HomeworkSource) = "${account}_${source.name}"

  private var completedIds =
      runCatching { completions.read(account)?.let { json.decodeFromString<Set<String>>(it) } }
          .getOrNull()
          .orEmpty()
  private val _state =
      MutableStateFlow(
          HomeworkState(
              HomeworkSource.entries.associateWith { source ->
                val saved =
                    runCatching {
                          snapshots.read(cacheAccount(source))?.let {
                            json.decodeFromString<HomeworkSnapshot>(it)
                          }
                        }
                        .getOrNull()
                HomeworkSourceState(
                    items = saved?.items.orEmpty().map(::applyCompletion),
                    updatedAt = saved?.updatedAt,
                )
              }
          )
      )
  val state = _state.asStateFlow()
  private val sourceMutexes = HomeworkSource.entries.associateWith { Mutex() }
  private val attemptedCredentialRecovery = MutableStateFlow<Set<HomeworkSource>>(emptySet())
  private val savedCredentials = HomeworkCredentialStore(credentialStorage)

  fun needsSeparateLogin(source: HomeworkSource): Boolean =
      adapters.firstOrNull { it.source == source }?.requiresSeparateLogin == true &&
          !homeworkHasSavedSession(account, source, settings)

  private fun applyCompletion(item: HomeworkItem) =
      item.copy(
          submission =
              if (item.source == HomeworkSource.BYXT && item.reports.any { it.uploaded }) {
                HomeworkSubmission.SUBMITTED
              } else item.submission,
          canMarkComplete = true,
          locallyCompleted = item.id in completedIds,
      )

  private fun update(
      source: HomeworkSource,
      transform: (HomeworkSourceState) -> HomeworkSourceState,
  ) {
    _state.update { state ->
      state.copy(sources = state.sources + (source to transform(state.sources.getValue(source))))
    }
  }

  suspend fun refresh(source: HomeworkSource? = null) = supervisorScope {
    adapters
        .filter { (source == null || it.source == source) && isHomeworkSourceAvailable(it.source) }
        .forEach { adapter ->
          launch {
            sourceMutexes.getValue(adapter.source).withLock {
              update(adapter.source) { it.copy(isLoading = true, error = null) }
              try {
                var warning: String? = null
                val loaded =
                    try {
                      loadWithCredentialRecovery(adapter)
                    } catch (partial: HomeworkPartialLoadException) {
                      warning = partial.message
                      partial.items +
                          _state.value.sources
                              .getValue(adapter.source)
                              .items
                              .filter { it.courseId in partial.failedCourseIds }
                              .map { it.copy(submissionHint = "本课程更新失败，暂显示上次记录") }
                    }
                val items = loaded.distinctBy { it.id }
                if (!isCurrentAccount()) return@withLock
                val timestamp =
                    Clock.System.now()
                        .toLocalDateTime(TimeZone.of("+08:00"))
                        .toString()
                        .take(16)
                        .replace('T', ' ')
                snapshots.write(
                    cacheAccount(adapter.source),
                    json.encodeToString(HomeworkSnapshot(items, timestamp)),
                )
                update(adapter.source) {
                  HomeworkSourceState(
                      items.map(::applyCompletion),
                      error = warning,
                      updatedAt = timestamp,
                  )
                }
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                if (isCurrentAccount())
                    update(adapter.source) {
                      it.copy(isLoading = false, error = e.message ?: "查询失败")
                    }
              } finally {
                update(adapter.source) { it.copy(isLoading = false) }
              }
            }
          }
        }
  }

  fun markCompleted(item: HomeworkItem, completed: Boolean) {
    if (!isCurrentAccount()) return
    completedIds = if (completed) completedIds + item.id else completedIds - item.id
    completions.write(account, json.encodeToString(completedIds))
    update(item.source) { it.copy(items = it.items.map(::applyCompletion)) }
  }

  suspend fun login(source: HomeworkSource, username: String, password: String) {
    check(isHomeworkSourceAvailable(source)) { "WebVPN 模式下 PatPat 暂不可用，请切换普通网络" }
    require(username.isNotBlank() && password.isNotBlank()) { "请输入账号和密码" }
    val adapter = adapters.first { it.source == source }
    var credentialSaveFailed = false
    sourceMutexes.getValue(source).withLock {
      adapter.login(username.trim(), password)
      if (adapter.requiresSeparateLogin) {
        attemptedCredentialRecovery.update { it - source }
        if (isCurrentAccount()) {
          try {
            savedCredentials.save(account, source, username.trim(), password)
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            credentialSaveFailed = true
            try {
              savedCredentials.delete(account, source)
            } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {
              // Keep the successful login result; the warning below explains the save failure.
            }
          }
        }
      }
    }
    if (isCurrentAccount()) refresh(source)
    if (credentialSaveFailed && isCurrentAccount()) {
      update(source) { it.copy(error = "已成功登录，但独立账号凭据未能加密保存；下次连接时请手动登录") }
    }
  }

  private suspend fun loadWithCredentialRecovery(adapter: HomeworkAdapter): List<HomeworkItem> {
    try {
      return adapter.load()
    } catch (expired: HomeworkLoginRequiredException) {
      if (!isCurrentAccount()) return emptyList()
      if (!adapter.requiresSeparateLogin) throw expired
      if (!markCredentialRecoveryAttempt(adapter.source)) {
        throw HomeworkLoginRequiredException("${adapter.source.label}登录状态已过期，请手动登录")
      }
      val credentials =
          try {
            savedCredentials.load(account, adapter.source)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            try {
              savedCredentials.delete(account, adapter.source)
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: Exception) {
              // The stored credential may already be unavailable or corrupt.
            }
            throw HomeworkLoginRequiredException(
                "${adapter.source.label}登录状态已过期，已保存凭据不可读取，请手动重新登录",
                e,
            )
          }
              ?: throw HomeworkLoginRequiredException(
                  "${adapter.source.label}登录状态已过期，没有可用的已保存凭据，请手动登录",
              )
      if (!isCurrentAccount()) return emptyList()
      try {
        adapter.login(credentials.username, credentials.password)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw HomeworkLoginRequiredException(
            "${adapter.source.label}自动登录失败；若账号密码已更改，请手动重新登录",
            e,
        )
      }
      if (!isCurrentAccount()) return emptyList()
      return try {
        adapter.load()
      } catch (e: HomeworkLoginRequiredException) {
        throw HomeworkLoginRequiredException(
            "${adapter.source.label}自动登录后仍未通过验证，请手动重新登录",
            e,
        )
      }
    }
  }

  private fun markCredentialRecoveryAttempt(source: HomeworkSource): Boolean {
    while (true) {
      val previous = attemptedCredentialRecovery.value
      if (source in previous) return false
      if (attemptedCredentialRecovery.compareAndSet(previous, previous + source)) return true
    }
  }

  suspend fun detail(item: HomeworkItem): HomeworkItem {
    check(isHomeworkSourceAvailable(item.source)) { "WebVPN 模式下 PatPat 暂不可用，请切换普通网络" }
    return adapters.first { it.source == item.source }.detail(item).let(::applyCompletion)
  }

  fun close() = adapters.forEach { it.close() }
}

/** Usernames are identifiers only; passwords remain in the platform credential flow. */
object HomeworkAccountNames {
  private const val KEY_PREFIX = "homework_account_name"

  fun get(
      account: String,
      source: HomeworkSource,
      settings: Settings = ScheduleStore.settings,
  ): String? =
      settings.getStringOrNull("$KEY_PREFIX:$account:${source.name}")?.takeIf(String::isNotBlank)

  fun set(
      account: String,
      source: HomeworkSource,
      username: String,
      settings: Settings = ScheduleStore.settings,
  ) {
    if (account.isBlank()) return
    val key = "$KEY_PREFIX:$account:${source.name}"
    if (username.isBlank()) settings.remove(key) else settings.putString(key, username.trim())
  }
}

fun isHomeworkSourceAvailable(
    source: HomeworkSource,
    mode: app.bucc.api.ConnectionMode? = app.bucc.api.ConnectionRuntime.currentMode(),
): Boolean = source != HomeworkSource.PATPAT || mode != app.bucc.api.ConnectionMode.WEBVPN
