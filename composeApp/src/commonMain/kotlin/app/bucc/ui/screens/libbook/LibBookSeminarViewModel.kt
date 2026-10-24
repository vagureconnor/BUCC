package app.bucc.ui.screens.libbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bucc.api.feature.LibBookSeminarApi
import app.bucc.model.dto.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject

data class LibBookSeminarUiState(
    val filters: LibBookSeminarFilters = LibBookSeminarFilters(),
    val campusId: String? = null,
    val buildingId: String? = null,
    val floorId: String? = null,
    val dates: List<String> = emptyList(),
    val rooms: List<LibBookSeminarRoom> = emptyList(),
    val date: String = "",
    val detail: LibBookSeminarDetail? = null,
    val begin: Int? = null,
    val finish: Int? = null,
    val categoryId: String = "",
    val title: String = "",
    val titleId: String = "",
    val content: String = "",
    val mobile: String = "",
    val memberInput: String = "",
    val members: List<LibBookSeminarMember> = emptyList(),
    val isPublic: Boolean = true,
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val addingMember: Boolean = false,
    val uploading: Boolean = false,
    val attachment: JsonObject? = null,
    val attachmentName: String = "",
    val message: String? = null,
    val confirmation: LibBookSeminarRequest? = null,
    val stale: Boolean = false,
)

@OptIn(ExperimentalTime::class)
class LibBookSeminarViewModel(
    private val api: LibBookSeminarApi = LibBookSeminarApi(),
    private val now: () -> Pair<String, Int> = {
      Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).let {
        it.date.toString() to (it.hour * 60 + it.minute)
      }
    },
) : ViewModel() {
  private val state = MutableStateFlow(LibBookSeminarUiState())
  val uiState = state.asStateFlow()
  private var generation = 0
  private var started = false

  fun ensureLoaded() {
    if (started) return
    started = true
    load()
  }

  fun resetLoadedState() {
    generation++
    started = false
    state.value = LibBookSeminarUiState()
  }

  fun notBefore(): Int =
      if (state.value.date == now().first) now().second + 1
      else if (state.value.date < now().first) 1440 else 0

  fun currentDay(): LibBookSeminarDay? =
      state.value.detail?.days?.firstOrNull { it.date == state.value.date }

  fun load() {
    val token = ++generation
    viewModelScope.launch {
      state.value = state.value.copy(loading = true, message = null, confirmation = null)
      api.dates()
          .onSuccess { dates ->
            if (token != generation) return@onSuccess
            val date = state.value.date.takeIf { it in dates } ?: dates.firstOrNull().orEmpty()
            state.value = state.value.copy(dates = dates, date = date)
            if (date.isNotBlank()) {
              api.filters(date)
                  .onSuccess { filters ->
                    if (token != generation) return@onSuccess
                    val old = state.value
                    val campus =
                        filters.campuses.firstOrNull { it.id == old.campusId }
                            ?: filters.campuses.firstOrNull()
                    val building =
                        campus?.buildings?.firstOrNull { it.id == old.buildingId }
                            ?: campus?.buildings?.firstOrNull()
                    val floor =
                        building?.floors?.firstOrNull { it.id == old.floorId }
                            ?: building?.floors?.firstOrNull()
                    state.value =
                        old.copy(
                            filters = filters,
                            campusId = campus?.id,
                            buildingId = building?.id,
                            floorId = floor?.id,
                            rooms = emptyList(),
                        )
                    loadRooms(date, token)
                  }
                  .onFailure {
                    if (token == generation)
                        state.value =
                            state.value.copy(
                                loading = false,
                                rooms = emptyList(),
                                message = it.message ?: "校区楼馆筛选加载失败，请刷新",
                            )
                  }
            } else state.value = state.value.copy(loading = false, message = "暂无可预约日期")
          }
          .onFailure {
            if (token == generation)
                state.value = state.value.copy(loading = false, message = it.message ?: "加载失败")
          }
    }
  }

  private suspend fun loadRooms(date: String, token: Int) {
    if (token != generation) return
    val building = state.value.buildingId
    if (building == null) {
      state.value = state.value.copy(rooms = emptyList(), loading = false, message = "暂无可用楼馆筛选，请刷新")
      return
    }
    val floor = state.value.floorId
    api.rooms(date, building, floor)
        .onSuccess { rooms ->
          if (token == generation) state.value = state.value.copy(rooms = rooms, loading = false)
        }
        .onFailure {
          if (token == generation)
              state.value = state.value.copy(loading = false, message = it.message ?: "房间加载失败")
        }
  }

  fun selectCampus(id: String) {
    val s = state.value
    if (s.uploading || s.submitting || id == s.campusId) return
    val campus = s.filters.campuses.firstOrNull { it.id == id } ?: return
    val building = campus.buildings.firstOrNull()
    changeLocation(id, building?.id, building?.floors?.firstOrNull()?.id)
  }

  fun selectBuilding(id: String) {
    val s = state.value
    if (s.uploading || s.submitting || id == s.buildingId) return
    val building =
        s.filters.campuses
            .firstOrNull { it.id == s.campusId }
            ?.buildings
            ?.firstOrNull { it.id == id } ?: return
    changeLocation(s.campusId, id, building.floors.firstOrNull()?.id)
  }

  fun selectFloor(id: String) {
    val s = state.value
    if (s.uploading || s.submitting || id == s.floorId) return
    val building =
        s.filters.campuses
            .firstOrNull { it.id == s.campusId }
            ?.buildings
            ?.firstOrNull { it.id == s.buildingId } ?: return
    if (building.floors.none { it.id == id }) return
    changeLocation(s.campusId, s.buildingId, id)
  }

  private fun changeLocation(campusId: String?, buildingId: String?, floorId: String?) {
    val token = ++generation
    state.value =
        state.value.copy(
            campusId = campusId,
            buildingId = buildingId,
            floorId = floorId,
            rooms = emptyList(),
            detail = null,
            begin = null,
            finish = null,
            categoryId = "",
            members = emptyList(),
            memberInput = "",
            attachment = null,
            attachmentName = "",
            confirmation = null,
            loading = true,
            message = null,
            stale = false,
        )
    val date = state.value.date
    viewModelScope.launch { loadRooms(date, token) }
  }

  fun selectDate(date: String) {
    if (date == state.value.date || state.value.uploading || state.value.submitting) return
    val id = state.value.detail?.room?.id
    val token = ++generation
    state.value =
        state.value.copy(
            date = date,
            begin = null,
            finish = null,
            categoryId = "",
            members = emptyList(),
            rooms = if (id == null) emptyList() else state.value.rooms,
            confirmation = null,
            loading = true,
        )
    viewModelScope.launch {
      if (id != null) refreshDetail(id, date, token) else loadRooms(date, token)
    }
  }

  fun openRoom(id: String) {
    if (state.value.uploading || state.value.submitting) return
    val token = ++generation
    state.value =
        state.value.copy(
            detail = null,
            begin = null,
            finish = null,
            categoryId = "",
            members = emptyList(),
            attachment = null,
            attachmentName = "",
            uploading = false,
            loading = true,
            confirmation = null,
            message = null,
        )
    viewModelScope.launch { refreshDetail(id, state.value.date, token) }
  }

  fun closeRoom() {
    if (state.value.uploading || state.value.submitting) return
    generation++
    state.value =
        state.value.copy(
            detail = null,
            begin = null,
            finish = null,
            confirmation = null,
            loading = false,
        )
    refresh()
  }

  fun refresh() {
    if (
        state.value.loading ||
            state.value.submitting ||
            state.value.uploading ||
            state.value.confirmation != null
    )
        return
    if (state.value.filters.campuses.isEmpty()) {
      load()
      return
    }
    val id = state.value.detail?.room?.id
    val date = state.value.date
    val token = ++generation
    state.value =
        state.value.copy(
            rooms = if (id == null) emptyList() else state.value.rooms,
            loading = true,
            stale = false,
        )
    viewModelScope.launch {
      if (id != null) refreshDetail(id, date, token) else loadRooms(date, token)
    }
  }

  private suspend fun refreshDetail(id: String, date: String, token: Int) {
    api.detail(id, date)
        .onSuccess { detail ->
          if (token != generation) return@onSuccess
          val s = state.value
          val valid =
              s.begin != null &&
                  s.finish != null &&
                  detail.days
                      .firstOrNull { it.date == date }
                      ?.permits(s.begin, s.finish, notBefore()) == true
          state.value =
              s.copy(
                  detail = detail,
                  dates = detail.days.map { it.date },
                  loading = false,
                  stale = false,
                  begin = s.begin.takeIf { valid },
                  finish = s.finish.takeIf { valid },
                  categoryId = s.categoryId.takeIf { valid }.orEmpty(),
                  message = if (s.begin != null && !valid) "所选时段已失效，请重新选择" else s.message,
              )
        }
        .onFailure {
          if (token == generation)
              state.value =
                  state.value.copy(
                      loading = false,
                      stale = true,
                      message = it.message ?: "刷新失败，状态可能已过期",
                  )
        }
  }

  fun selectBegin(value: Int) {
    state.value =
        state.value.copy(
            begin = value,
            finish = null,
            categoryId = "",
            members = emptyList(),
            confirmation = null,
        )
  }

  fun selectFinish(value: Int) {
    state.value = state.value.copy(finish = value, members = emptyList(), confirmation = null)
  }

  fun selectCategory(value: LibBookSeminarCategory) {
    state.value =
        state.value.copy(
            begin = value.start,
            finish = value.end,
            categoryId = value.id,
            members = emptyList(),
            confirmation = null,
        )
  }

  fun editTitle(value: String) {
    state.value = state.value.copy(title = value, confirmation = null)
  }

  fun selectTitle(value: String) {
    state.value = state.value.copy(titleId = value, confirmation = null)
  }

  fun editContent(value: String) {
    state.value = state.value.copy(content = value.take(200), confirmation = null)
  }

  fun editMobile(value: String) {
    state.value =
        state.value.copy(mobile = value.filter(Char::isDigit).take(11), confirmation = null)
  }

  fun editMember(value: String) {
    state.value = state.value.copy(memberInput = value)
  }

  fun setPublic(value: Boolean) {
    state.value = state.value.copy(isPublic = value, confirmation = null)
  }

  fun removeMember(id: String) {
    state.value =
        state.value.copy(
            members = state.value.members.filterNot { it.id == id },
            confirmation = null,
        )
  }

  fun dismissMessage() {
    state.value = state.value.copy(message = null)
  }

  fun dismissConfirmation() {
    state.value = state.value.copy(confirmation = null)
  }

  fun addMember() {
    val s = state.value
    val detail = s.detail ?: return
    val begin = s.begin
    val finish = s.finish
    if (s.addingMember) return
    if (begin == null || finish == null) {
      state.value = s.copy(message = "请先选择预约时间")
      return
    }
    if (s.memberInput.isBlank()) {
      state.value = s.copy(message = "请输入参与人学号")
      return
    }
    if (s.members.size + 1 >= detail.maxPeople) {
      state.value = s.copy(message = "已达到房间人数上限")
      return
    }
    val token = generation
    state.value = s.copy(addingMember = true)
    viewModelScope.launch {
      api.member(detail.room.id, s.memberInput, s.date, begin, finish)
          .onSuccess { member ->
            if (token == generation && state.value.begin == begin && state.value.finish == finish) {
              val current = state.value
              state.value =
                  if (current.members.any { it.id == member.id }) current.copy(message = "该成员已添加")
                  else
                      current.copy(
                          members = current.members + member,
                          memberInput = "",
                          confirmation = null,
                      )
            }
          }
          .onFailure {
            if (token == generation)
                state.value = state.value.copy(message = it.message ?: "成员验证失败")
          }
      state.value = state.value.copy(addingMember = false)
    }
  }

  fun attachmentError(message: String) {
    state.value = state.value.copy(message = message)
  }

  fun removeAttachment() {
    state.value = state.value.copy(attachment = null, attachmentName = "", confirmation = null)
  }

  fun uploadAttachment(fileName: String, mimeType: String, bytes: ByteArray) {
    val s = state.value
    if (s.uploading || s.submitting || s.detail == null) {
      bytes.fill(0)
      return
    }
    if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) {
      bytes.fill(0)
      state.value = s.copy(message = "附件须小于 10 MiB 且不能为空")
      return
    }
    if (mimeType !in setOf("image/jpeg", "image/png", "image/bmp", "application/pdf")) {
      bytes.fill(0)
      state.value = s.copy(message = "仅支持 JPG、PNG、BMP 图片或 PDF")
      return
    }
    val token = generation
    val roomId = s.detail?.room?.id
    state.value =
        s.copy(uploading = true, attachment = null, attachmentName = "", confirmation = null)
    viewModelScope.launch {
      try {
        api.uploadAttachment(fileName, mimeType, bytes)
            .onSuccess { data ->
              if (token == generation && state.value.detail?.room?.id == roomId)
                  state.value = state.value.copy(attachment = data, attachmentName = fileName)
            }
            .onFailure {
              if (token == generation)
                  state.value = state.value.copy(message = it.message ?: "附件上传失败")
            }
      } finally {
        bytes.fill(0)
        if (token == generation) state.value = state.value.copy(uploading = false)
      }
    }
  }

  private fun request(): LibBookSeminarRequest? {
    val s = state.value
    val id = s.detail?.room?.id ?: return null
    return LibBookSeminarRequest(
        id,
        s.date,
        s.begin ?: return null,
        s.finish ?: return null,
        s.title,
        s.titleId,
        s.content,
        s.mobile,
        s.members,
        s.isPublic,
        s.categoryId,
        s.attachment,
    )
  }

  fun prepareConfirmation() {
    val s = state.value
    if (s.submitting || s.loading || s.uploading) return
    val request =
        request()
            ?: run {
              state.value = s.copy(message = "请选择完整预约时段")
              return
            }
    s.detail?.validate(request, notBefore())?.let {
      state.value = s.copy(message = it)
      return
    }
    val token = ++generation
    state.value = s.copy(loading = true)
    viewModelScope.launch {
      api.detail(request.roomId, request.date)
          .onSuccess { latest ->
            if (token != generation) return@onSuccess
            val error =
                latest.validate(request, notBefore())
                    ?: if (request() != request) "预约内容已修改，请重新核验" else null
            state.value =
                state.value.copy(
                    detail = latest,
                    loading = false,
                    stale = false,
                    confirmation = request.takeIf { error == null },
                    message = error,
                )
          }
          .onFailure {
            if (token == generation)
                state.value =
                    state.value.copy(
                        loading = false,
                        stale = true,
                        message = it.message ?: "无法核验可用性，请重试",
                    )
          }
    }
  }

  fun submitConfirmed() {
    val s = state.value
    val request = s.confirmation ?: return
    val detail = s.detail ?: return
    if (s.submitting) return
    detail.validate(request, notBefore())?.let {
      state.value = s.copy(confirmation = null, message = it)
      return
    }
    state.value = s.copy(submitting = true, confirmation = null)
    val token = generation
    viewModelScope.launch {
      api.submit(detail, request)
          .onSuccess {
            if (token == generation)
                state.value =
                    state.value.copy(
                        submitting = false,
                        begin = null,
                        finish = null,
                        members = emptyList(),
                        message = it.message,
                    )
          }
          .onFailure {
            if (token == generation)
                state.value =
                    state.value.copy(
                        submitting = false,
                        message = (it.message ?: "预约结果未知") + "。请先在官网核对预约记录；不会自动重试。",
                    )
          }
      if (token == generation) refresh()
    }
  }
}
