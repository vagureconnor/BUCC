package app.bucc.ui.screens.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bucc.attendance.AttendanceApi
import app.bucc.attendance.AttendanceCourse
import app.bucc.attendance.AttendanceRecord
import app.bucc.model.dto.Term
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AttendanceUiState(
    val terms: List<Term> = emptyList(),
    val selectedTerm: Term? = null,
    val courses: List<AttendanceCourse> = emptyList(),
    val records: List<AttendanceRecord> = emptyList(),
    val selectedCourseId: String? = null,
    val isLoadingTerms: Boolean = false,
    val isLoadingSemester: Boolean = false,
    val error: String? = null,
)

class AttendanceViewModel(private val api: AttendanceApi) : ViewModel() {
  private val mutableState = MutableStateFlow(AttendanceUiState())
  val state = mutableState.asStateFlow()

  private var generation = 0
  private var loadJob: Job? = null
  private var started = false

  fun ensureLoaded() {
    if (started) return
    started = true
    refreshTerms()
  }

  fun refresh() {
    val current = mutableState.value
    if (current.selectedTerm != null) loadSemester(current.selectedTerm, clearBeforeLoad = false)
    else refreshTerms()
  }

  fun refreshTerms() {
    val token = ++generation
    loadJob?.cancel()
    loadJob =
        viewModelScope.launch {
          mutableState.value =
              mutableState.value.copy(
                  isLoadingTerms = true,
                  isLoadingSemester = false,
                  error = null,
              )
          try {
            api.getTerms()
                .fold(
                    onSuccess = { terms ->
                      if (token != generation) return@fold
                      val old = mutableState.value.selectedTerm
                      val selected =
                          terms.firstOrNull { it.itemCode == old?.itemCode }
                              ?: terms.firstOrNull { it.selected }
                              ?: terms.firstOrNull()
                      mutableState.value =
                          mutableState.value.copy(
                              terms = terms,
                              selectedTerm = selected,
                              isLoadingTerms = false,
                              error = null,
                          )
                      if (selected != null) loadSemester(selected, clearBeforeLoad = true)
                      else
                          mutableState.value =
                              mutableState.value.copy(
                                  courses = emptyList(),
                                  records = emptyList(),
                                  selectedCourseId = null,
                              )
                    },
                    onFailure = { error ->
                      if (token == generation)
                          mutableState.value =
                              mutableState.value.copy(
                                  isLoadingTerms = false,
                                  error = error.message ?: "学期列表加载失败，请重试",
                              )
                    },
                )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            if (token == generation)
                mutableState.value =
                    mutableState.value.copy(
                        isLoadingTerms = false,
                        error = error.message ?: "学期列表加载失败，请重试",
                    )
          }
        }
  }

  fun selectTerm(term: Term) {
    if (mutableState.value.selectedTerm?.itemCode == term.itemCode) return
    loadSemester(term, clearBeforeLoad = true)
  }

  private fun loadSemester(term: Term, clearBeforeLoad: Boolean) {
    val token = ++generation
    loadJob?.cancel()
    val old = mutableState.value
    mutableState.value =
        old.copy(
            selectedTerm = term,
            courses = if (clearBeforeLoad) emptyList() else old.courses,
            records = if (clearBeforeLoad) emptyList() else old.records,
            selectedCourseId = if (clearBeforeLoad) null else old.selectedCourseId,
            isLoadingTerms = false,
            isLoadingSemester = true,
            error = null,
        )
    loadJob =
        viewModelScope.launch {
          try {
            api.getSemester(term.itemCode)
                .fold(
                    onSuccess = { semester ->
                      if (token != generation) return@fold
                      val validCourseIds = semester.courses.mapTo(mutableSetOf()) { it.id }
                      mutableState.value =
                          mutableState.value.copy(
                              courses = semester.courses,
                              records = semester.records.filter { it.courseId in validCourseIds },
                              selectedCourseId =
                                  mutableState.value.selectedCourseId?.takeIf {
                                    it in validCourseIds
                                  },
                              isLoadingSemester = false,
                              error = null,
                          )
                    },
                    onFailure = { error ->
                      if (token == generation)
                          mutableState.value =
                              mutableState.value.copy(
                                  isLoadingSemester = false,
                                  error = error.message ?: "该学期出勤加载失败，请重试",
                              )
                    },
                )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            if (token == generation)
                mutableState.value =
                    mutableState.value.copy(
                        isLoadingSemester = false,
                        error = error.message ?: "该学期出勤加载失败，请重试",
                    )
          }
        }
  }

  fun selectCourse(courseId: String?) {
    if (courseId == null || mutableState.value.courses.any { it.id == courseId }) {
      mutableState.value = mutableState.value.copy(selectedCourseId = courseId)
    }
  }
}
