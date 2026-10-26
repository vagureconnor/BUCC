package app.bucc.ui.screens.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bucc.api.feature.SigninApi
import app.bucc.model.dto.SigninClassDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 课堂签到模块 UI 状态。 */
data class SigninUiState(
    val isLoading: Boolean = false,
    val classes: List<SigninClassDto> = emptyList(),
    val error: String? = null,
    /** 签到操作的结果提示（如“签到成功”）。 */
    val signinResult: String? = null,
    /** 当前正在执行签到的课程 ID（用于显示局部加载状态）。 */
    val signingInCourseId: String? = null,
)

/** 管理课堂签到功能的 ViewModel。 */
class SigninViewModel(
    private val signinApi: SigninApi = SigninApi(),
) : ViewModel() {
  private var loadedOnce = false
  private var sessionGeneration = 0L
  private var loadJob: Job? = null
  private var signinJob: Job? = null

  private val _uiState = MutableStateFlow(SigninUiState())
  /** 签到状态流。 */
  val uiState: StateFlow<SigninUiState> = _uiState.asStateFlow()

  fun ensureTodayLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && loadedOnce) return
    loadTodayClasses()
  }

  internal fun hasTodayLoaded(): Boolean = loadedOnce

  /** 拉取今日所有可签到的课堂。 */
  fun loadTodayClasses() {
    if (loadJob?.isActive == true) return
    val generation = sessionGeneration
    _uiState.value = _uiState.value.copy(isLoading = true, error = null)
    loadJob =
        viewModelScope.launch {
          val result = signinApi.getTodayClasses()
          if (generation != sessionGeneration) return@launch
          result
              .onSuccess {
                loadedOnce = true
                _uiState.value = _uiState.value.copy(isLoading = false, classes = it.data)
              }
              .onFailure {
                loadedOnce = false
                _uiState.value =
                    _uiState.value.copy(isLoading = false, error = it.message ?: "加载课程失败")
              }
        }
  }

  /** 提交签到请求。 */
  fun performSignin(courseId: String) {
    if (signinJob?.isActive == true) return
    val generation = sessionGeneration
    _uiState.value = _uiState.value.copy(signingInCourseId = courseId, signinResult = null)
    signinJob =
        viewModelScope.launch {
          try {
            val result = signinApi.performSignin(courseId)
            if (generation != sessionGeneration) return@launch
            result
                .onSuccess { response ->
                  _uiState.value =
                      _uiState.value.copy(
                          signingInCourseId = null,
                          signinResult =
                              if (response.success) "签到成功" else "签到失败: ${response.message}",
                      )
                  if (response.success) {
                    loadedOnce = false
                    ensureTodayLoaded(forceRefresh = true)
                  }
                }
                .onFailure {
                  _uiState.value =
                      _uiState.value.copy(
                          signingInCourseId = null,
                          signinResult = "签到异常: ${it.message}",
                      )
                }
          } finally {
            if (generation == sessionGeneration && _uiState.value.signingInCourseId == courseId) {
              _uiState.value = _uiState.value.copy(signingInCourseId = null)
            }
          }
        }
  }

  /** 重置内部加载标记与 UI 状态，用于连接模式切换等场景。 */
  fun resetLoadedState() {
    sessionGeneration++
    loadJob?.cancel()
    signinJob?.cancel()
    loadJob = null
    signinJob = null
    loadedOnce = false
    _uiState.value = SigninUiState()
  }

  /** 清除当前的签到结果提示。 */
  fun clearSigninResult() {
    _uiState.value = _uiState.value.copy(signinResult = null)
  }
}
