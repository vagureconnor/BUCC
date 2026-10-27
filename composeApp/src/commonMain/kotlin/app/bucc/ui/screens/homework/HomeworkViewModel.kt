package app.bucc.ui.screens.homework

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bucc.homework.*
import app.bucc.notifications.NotificationPreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeworkDetailState(
    val item: HomeworkItem? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class HomeworkLoginState(
    val source: HomeworkSource? = null,
    val username: String = "",
    val savedUsername: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class HomeworkViewModel
internal constructor(
    private val repository: HomeworkRepository,
    installBackgroundTaskHandler:
        (HomeworkBackgroundTaskHandler) -> HomeworkBackgroundTaskRegistration,
) : ViewModel() {
  constructor(
      repository: HomeworkRepository
  ) : this(repository, ::installHomeworkBackgroundTaskHandler)

  val state = repository.state
  private val refreshJobs = mutableMapOf<HomeworkSource, Job>()
  private var detailJob: Job? = null
  private var loginJob: Job? = null
  private var reminderJob: Job? = null
  private var foregroundCheckJob: Job? = null
  private var backgroundCheckJob: Job? = null
  private var closed = false
  private var loaded = false
  private var sessionAvailable = true
  private var sessionGeneration = 0L
  private val _detail = MutableStateFlow(HomeworkDetailState())
  val detail = _detail.asStateFlow()
  private val _login = MutableStateFlow(HomeworkLoginState())
  val login = _login.asStateFlow()
  private val reminderMonitor = HomeworkReminderMonitor(repository.account, repository)
  private val backgroundTaskRegistration: HomeworkBackgroundTaskRegistration

  init {
    backgroundTaskRegistration =
        installBackgroundTaskHandler(
            object : HomeworkBackgroundTaskHandler {
              override fun refresh(completion: HomeworkBackgroundTaskCompletion) {
                if (closed || !sessionAvailable) {
                  completion.finish("session_unavailable")
                  return
                }
                if (backgroundCheckJob?.isActive == true) {
                  completion.finish("busy")
                  return
                }
                val generation = sessionGeneration
                backgroundCheckJob =
                    viewModelScope.launch {
                      try {
                        val items = reminderMonitor.check()
                        if (!isCurrentSession(generation)) {
                          completion.finish("session_unavailable")
                          return@launch
                        }
                        if (NotificationPreferenceStore().read(repository.account).newHomework)
                            notifyNewHomework(items)
                        completion.finish("success")
                      } catch (e: CancellationException) {
                        completion.finish("cancelled")
                        throw e
                      } catch (e: Exception) {
                        completion.finish("failed")
                      }
                    }
              }
            }
        )
    reminderJob =
        viewModelScope.launch {
          delay(5_000)
          while (true) {
            try {
              if (!sessionAvailable) {
                delay(1_000)
                continue
              }
              val generation = sessionGeneration
              val items = reminderMonitor.check()
              if (
                  isCurrentSession(generation) &&
                      NotificationPreferenceStore().read(repository.account).newHomework
              )
                  notifyNewHomework(items)
            } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {}
            val interval = homeworkReminderPollIntervalMillis()
            if (interval <= 0L) break
            delay(interval)
          }
        }
  }

  fun setSessionAvailable(available: Boolean) {
    if (closed) return
    if (sessionAvailable == available) return
    sessionAvailable = available
    sessionGeneration++
    if (!available) {
      cancelRefresh()
      detailJob?.cancel()
      foregroundCheckJob?.cancel()
      foregroundCheckJob = null
      backgroundCheckJob?.cancel()
      backgroundCheckJob = null
      _detail.value = _detail.value.copy(isLoading = false)
      // Separate-platform login does not use the expired campus SSO session.
    }
  }

  fun onAppForegrounded() {
    if (!sessionAvailable || foregroundCheckJob?.isActive == true) return
    val generation = sessionGeneration
    foregroundCheckJob =
        viewModelScope.launch {
          try {
            val items = reminderMonitor.check()
            if (
                isCurrentSession(generation) &&
                    NotificationPreferenceStore().read(repository.account).newHomework
            )
                notifyNewHomework(items)
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {}
        }
  }

  fun hasLoaded() = loaded

  fun needsSeparateLogin(source: HomeworkSource) = repository.needsSeparateLogin(source)

  fun ensureLoaded(forceRefresh: Boolean = false) {
    if (!sessionAvailable) return
    if (!forceRefresh && (loaded || refreshJobs.values.any { it.isActive })) return
    refresh()
  }

  fun refresh(source: HomeworkSource? = null) {
    if (!sessionAvailable) return
    val sources = source?.let { listOf(it) } ?: HomeworkSource.entries
    sources.forEach { value ->
      val previous = refreshJobs[value]
      refreshJobs[value] =
          viewModelScope.launch {
            previous?.cancelAndJoin()
            repository.refresh(value)
            loaded = true
          }
    }
  }

  fun cancelRefresh() = refreshJobs.values.forEach { it.cancel() }

  fun showDetail(id: String) {
    if (!sessionAvailable) return
    val item = state.value.items.firstOrNull { it.id == id } ?: return
    detailJob?.cancel()
    _detail.value = HomeworkDetailState(item, isLoading = true)
    detailJob =
        viewModelScope.launch {
          try {
            _detail.value = HomeworkDetailState(repository.detail(item))
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            _detail.value = HomeworkDetailState(item, error = e.message ?: "详情加载失败")
          }
        }
  }

  fun dismissDetail() {
    detailJob?.cancel()
    _detail.value = HomeworkDetailState()
  }

  fun markCompleted(item: HomeworkItem, completed: Boolean) {
    repository.markCompleted(item, completed)
    if (_detail.value.item?.id == item.id) {
      _detail.value = _detail.value.copy(item = state.value.items.firstOrNull { it.id == item.id })
    }
  }

  fun showLogin(source: HomeworkSource) {
    if (!isHomeworkSourceAvailable(source)) return
    val savedUsername = HomeworkAccountNames.get(repository.account, source)
    _login.value =
        HomeworkLoginState(
            source,
            username = savedUsername.orEmpty(),
            savedUsername = savedUsername,
        )
  }

  fun updateLoginUsername(username: String) {
    _login.value = _login.value.copy(username = username, error = null)
  }

  fun dismissLogin() {
    loginJob?.cancel()
    _login.value = HomeworkLoginState()
  }

  fun login(username: String, password: String) {
    if (!sessionAvailable) return
    val source = _login.value.source ?: return
    if (!isHomeworkSourceAvailable(source)) return
    if (loginJob?.isActive == true) return
    val normalizedUsername = username.trim()
    loginJob =
        viewModelScope.launch {
          _login.value =
              _login.value.copy(username = normalizedUsername, isLoading = true, error = null)
          try {
            repository.login(source, normalizedUsername, password)
            HomeworkAccountNames.set(repository.account, source, normalizedUsername)
            _login.value = HomeworkLoginState()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            _login.value = _login.value.copy(isLoading = false, error = e.message ?: "登录失败")
          }
        }
  }

  fun close() {
    if (closed) return
    closed = true
    sessionAvailable = false
    sessionGeneration++
    backgroundTaskRegistration.dispose()
    cancelRefresh()
    detailJob?.cancel()
    loginJob?.cancel()
    reminderJob?.cancel()
    foregroundCheckJob?.cancel()
    backgroundCheckJob?.cancel()
    repository.close()
  }

  private fun isCurrentSession(generation: Long): Boolean =
      !closed && sessionAvailable && sessionGeneration == generation

  override fun onCleared() {
    close()
  }
}
