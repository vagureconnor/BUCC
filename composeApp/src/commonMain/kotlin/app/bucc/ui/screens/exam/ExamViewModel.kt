package app.bucc.ui.screens.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bucc.api.feature.ScheduleApi
import app.bucc.model.dto.ExamArrangementData
import app.bucc.model.dto.Term
import app.bucc.repository.GlobalTermRepository
import app.bucc.repository.TermRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExamViewModel(
    private val scheduleApi: ScheduleApi = ScheduleApi(),
    private val termRepository: TermRepository = GlobalTermRepository.instance,
) : ViewModel() {
  private var loadedOnce = false
  private var generation = 0

  private val _uiState = MutableStateFlow(ExamUiState())
  val uiState: StateFlow<ExamUiState> = _uiState.asStateFlow()

  fun ensureLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && loadedOnce) return
    loadTerms(forceRefresh)
  }

  /** 重置内部加载标记与 UI 状态，用于连接模式切换等场景。 */
  fun resetLoadedState() {
    loadedOnce = false
    generation++
    _uiState.value = ExamUiState()
  }

  fun loadTerms(forceRefresh: Boolean = false) {
    loadedOnce = true
    val token = ++generation
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isLoading = true, examData = null, error = null)

      termRepository
          .getTerms(forceRefresh)
          .onSuccess { terms ->
            if (token != generation) return@onSuccess
            val selectedTerm = terms.find { it.selected } ?: terms.firstOrNull()
            _uiState.value =
                _uiState.value.copy(
                    isLoading = selectedTerm != null,
                    terms = terms,
                    selectedTerm = selectedTerm,
                    examData = null,
                    error = null,
                )
            selectedTerm?.let { loadExams(it.itemCode, token) }
          }
          .onFailure { exception ->
            if (token != generation) return@onFailure
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    examData = null,
                    error = exception.message ?: "加载学期信息失败",
                )
          }
    }
  }

  fun selectTerm(term: Term) {
    if (_uiState.value.selectedTerm != term) {
      val token = ++generation
      _uiState.value =
          _uiState.value.copy(selectedTerm = term, isLoading = true, examData = null, error = null)
      loadExams(term.itemCode, token)
    }
  }

  private fun loadExams(termCode: String, token: Int) {
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isLoading = true, error = null)

      scheduleApi
          .getExamArrangement(termCode)
          .onSuccess { examData ->
            if (token != generation) return@onSuccess
            _uiState.value =
                _uiState.value.copy(isLoading = false, examData = examData, error = null)
          }
          .onFailure { exception ->
            if (token != generation) return@onFailure
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    examData = null,
                    error = exception.message ?: "加载考试信息失败",
                )
          }
    }
  }
}

data class ExamUiState(
    val isLoading: Boolean = false,
    val terms: List<Term> = emptyList(),
    val selectedTerm: Term? = null,
    val examData: ExamArrangementData? = null,
    val error: String? = null,
)
