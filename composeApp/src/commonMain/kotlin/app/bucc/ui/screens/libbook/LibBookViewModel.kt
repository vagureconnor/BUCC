package app.bucc.ui.screens.libbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bucc.api.feature.LibBookApi
import app.bucc.model.dto.LibBookAreaDetailDto
import app.bucc.model.dto.LibBookAreaDto
import app.bucc.model.dto.LibBookBookingsResponse
import app.bucc.model.dto.LibBookLibraryDto
import app.bucc.model.dto.LibBookMapImagesDto
import app.bucc.model.dto.LibBookReserveRequest
import app.bucc.model.dto.LibBookSeatDto
import app.bucc.model.dto.LibBookTimeSlotDto
import app.bucc.model.dto.cancelBlockedMessage
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class LibBookUiState(
    val isInitialLoading: Boolean = false,
    val isAreasLoading: Boolean = false,
    val isSeatsLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val isRefreshingSeats: Boolean = false,
    val seatStatusStale: Boolean = false,
    val mapImages: LibBookMapImagesDto = LibBookMapImagesDto(),
    val isBookingsLoading: Boolean = false,
    val libraries: List<LibBookLibraryDto> = emptyList(),
    val areas: List<LibBookAreaDto> = emptyList(),
    val areaDetail: LibBookAreaDetailDto? = null,
    val seats: List<LibBookSeatDto> = emptyList(),
    val bookings: LibBookBookingsResponse = LibBookBookingsResponse(),
    val selectedLibraryId: String? = null,
    val selectedStoreyId: String? = null,
    val selectedAreaId: String? = null,
    val selectedDay: String = "",
    val selectedSlotId: String? = null,
    val selectedSeatId: String? = null,
    val initialError: String? = null,
    val areasError: String? = null,
    val seatsError: String? = null,
    val bookingsError: String? = null,
    val actionMessage: String? = null,
    val cancelingBookingId: String? = null,
)

@OptIn(ExperimentalTime::class)
class LibBookViewModel(
    private val libBookApi: LibBookApi = LibBookApi(),
    private val currentDateProvider: () -> String = {
      val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai"))
      val month = (now.month.ordinal + 1).toString().padStart(2, '0')
      val day = now.day.toString().padStart(2, '0')
      "${now.year}-$month-$day"
    },
) : ViewModel() {
  private var sessionVersion = 0
  private var seatsVersion = 0
  private var detailVersion = 0
  private var areasVersion = 0
  private var librariesVersion = 0
  private var initialLoadedOnce = false
  private var bookingsLoadedOnce = false
  private val _uiState = MutableStateFlow(LibBookUiState(selectedDay = currentDateProvider()))
  val uiState: StateFlow<LibBookUiState> = _uiState.asStateFlow()

  fun ensureInitialLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && initialLoadedOnce) return
    loadLibraries()
  }

  /** 重置内部加载标记与 UI 状态，用于连接模式切换等场景。 */
  fun resetLoadedState() {
    sessionVersion++
    librariesVersion++
    areasVersion++
    detailVersion++
    seatsVersion++
    initialLoadedOnce = false
    bookingsLoadedOnce = false
    _uiState.value = LibBookUiState(selectedDay = _uiState.value.selectedDay)
  }

  fun ensureBookingsLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && bookingsLoadedOnce) return
    loadBookings()
  }

  fun loadLibraries() {
    initialLoadedOnce = true
    val day = _uiState.value.selectedDay.ifBlank { currentDateProvider() }
    val version = ++librariesVersion
    viewModelScope.launch {
      _uiState.value =
          _uiState.value.copy(
              isInitialLoading = true,
              initialError = null,
              areasError = null,
              seatsError = null,
              actionMessage = null,
          )
      libBookApi
          .getLibraries(day)
          .onSuccess { libraries ->
            if (version != librariesVersion) return@onSuccess
            val libraryId =
                _uiState.value.selectedLibraryId?.takeIf { id -> libraries.any { it.id == id } }
                    ?: libraries.firstOrNull()?.id
            val storeyId = resolveStoreyId(libraries, libraryId, _uiState.value.selectedStoreyId)
            _uiState.value =
                _uiState.value.copy(
                    isInitialLoading = false,
                    libraries = libraries,
                    selectedLibraryId = libraryId,
                    selectedStoreyId = storeyId,
                    selectedDay = day,
                    initialError = null,
                )
            if (libraryId != null) {
              loadAreas(libraryId, storeyId, day)
            }
          }
          .onFailure {
            if (version != librariesVersion) return@onFailure
            _uiState.value =
                _uiState.value.copy(
                    isInitialLoading = false,
                    initialError = it.message ?: "加载楼馆失败",
                )
          }
    }
  }

  fun selectLibrary(libraryId: String) {
    if (_uiState.value.isSubmitting) return
    areasVersion++
    detailVersion++
    seatsVersion++
    val libraries = _uiState.value.libraries
    val storeyId = resolveStoreyId(libraries, libraryId, null)
    _uiState.value =
        _uiState.value.copy(
            selectedLibraryId = libraryId,
            selectedStoreyId = storeyId,
            selectedAreaId = null,
            selectedSlotId = null,
            selectedSeatId = null,
            areas = emptyList(),
            areaDetail = null,
            seats = emptyList(),
            actionMessage = null,
        )
    loadAreas(libraryId, storeyId, _uiState.value.selectedDay.ifBlank { currentDateProvider() })
  }

  fun selectStorey(storeyId: String?) {
    if (_uiState.value.isSubmitting) return
    areasVersion++
    detailVersion++
    seatsVersion++
    val libraryId = _uiState.value.selectedLibraryId ?: return
    _uiState.value =
        _uiState.value.copy(
            selectedStoreyId = storeyId,
            selectedAreaId = null,
            selectedSlotId = null,
            selectedSeatId = null,
            areaDetail = null,
            seats = emptyList(),
            actionMessage = null,
        )
    loadAreas(libraryId, storeyId, _uiState.value.selectedDay.ifBlank { currentDateProvider() })
  }

  fun selectArea(areaId: String) {
    if (_uiState.value.isSubmitting) return
    areasVersion++
    detailVersion++
    seatsVersion++
    _uiState.value =
        _uiState.value.copy(
            selectedAreaId = areaId,
            selectedSlotId = null,
            selectedSeatId = null,
            seats = emptyList(),
            seatsError = null,
            actionMessage = null,
        )
    loadAreaDetailAndSeats(areaId)
  }

  fun selectSlot(slotId: String) {
    if (_uiState.value.isSubmitting) return
    val current = _uiState.value
    val slot = current.availableSlots.firstOrNull { it.id == slotId } ?: return
    val areaId = current.selectedAreaId ?: return
    _uiState.value =
        current.copy(
            selectedSlotId = slot.id,
            selectedSeatId = null,
            seats = emptyList(),
            actionMessage = null,
        )
    loadSeats(areaId, current.selectedDay.ifBlank { currentDateProvider() }, slot)
  }

  fun selectDay(day: String) {
    val current = _uiState.value
    val detail = current.areaDetail ?: return
    if (day !in detail.availableDates || current.isSubmitting) return
    val slot = (detail.timeSlotsByDay[day] ?: detail.timeSlots).firstOrNull()
    seatsVersion++
    _uiState.value =
        current.copy(
            selectedDay = day,
            selectedSlotId = slot?.id,
            selectedSeatId = null,
            seats = emptyList(),
            actionMessage = null,
        )
    if (slot != null) current.selectedAreaId?.let { loadSeats(it, day, slot) }
  }

  fun startNormalReservation() {
    if (_uiState.value.isSubmitting) return
    initialLoadedOnce = false
    loadLibraries()
  }

  fun selectSeat(seatId: String) {
    if (_uiState.value.isSubmitting) return
    val selected = _uiState.value.seats.firstOrNull { it.id == seatId } ?: return
    if (!selected.isAvailable || !selected.inLabel) {
      _uiState.value = _uiState.value.copy(actionMessage = "该座位当前不可预约")
      return
    }
    _uiState.value =
        _uiState.value.copy(
            selectedSeatId = if (_uiState.value.selectedSeatId == seatId) null else seatId,
            actionMessage = null,
        )
  }

  fun submitReservation() {
    val current = _uiState.value
    if (current.isSubmitting) return
    val areaId = current.selectedAreaId ?: return setActionMessage("请先选择分区")
    val seatId = current.selectedSeatId ?: return setActionMessage("请先选择可预约座位")
    val slot = current.selectedSlot ?: return setActionMessage("请先选择预约时段")
    val day = current.selectedDay.ifBlank { currentDateProvider() }
    val session = sessionVersion
    _uiState.value = current.copy(isSubmitting = true, actionMessage = null)
    viewModelScope.launch {
      val fresh = libBookApi.getSeatSnapshot(areaId, day, slot.start, slot.end)
      if (session != sessionVersion) return@launch
      val snapshot = fresh.getOrNull()
      if (
          snapshot == null ||
              snapshot.seats.none { it.id == seatId && it.isAvailable && it.inLabel }
      ) {
        _uiState.value =
            _uiState.value.copy(
                isSubmitting = false,
                selectedSeatId = null,
                seatStatusStale = snapshot == null,
                seats = snapshot?.seats ?: _uiState.value.seats,
                actionMessage = if (snapshot == null) "无法核对最新座位状态，请刷新后重试" else "该座位已不可预约，请重新选择",
            )
        return@launch
      }
      libBookApi
          .reserve(
              LibBookReserveRequest(
                  areaId = areaId,
                  seatId = seatId,
                  day = day,
                  segment = slot.id,
                  startTime = slot.start,
                  endTime = slot.end,
              )
          )
          .onSuccess {
            if (session != sessionVersion) return@onSuccess
            _uiState.value =
                _uiState.value.copy(
                    isSubmitting = false,
                    selectedSeatId = null,
                    actionMessage = it.message,
                )
            loadSeats(areaId, day, slot)
            loadBookings()
          }
          .onFailure {
            if (session != sessionVersion) return@onFailure
            _uiState.value =
                _uiState.value.copy(
                    isSubmitting = false,
                    actionMessage = it.message ?: "预约失败",
                )
          }
    }
  }

  fun loadBookings(page: Int = 1, limit: Int = 20) {
    bookingsLoadedOnce = true
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isBookingsLoading = true, bookingsError = null)
      libBookApi
          .getBookings(page, limit)
          .onSuccess {
            _uiState.value =
                _uiState.value.copy(
                    isBookingsLoading = false,
                    bookings = it,
                    bookingsError = null,
                )
          }
          .onFailure {
            _uiState.value =
                _uiState.value.copy(
                    isBookingsLoading = false,
                    bookingsError = it.message ?: "加载预约记录失败",
                )
          }
    }
  }

  fun cancelBooking(bookingId: String) {
    val current = _uiState.value
    if (current.cancelingBookingId == bookingId) return
    val booking = current.bookings.bookings.firstOrNull { it.id == bookingId }
    val blockedMessage =
        booking?.cancelBlockedMessage() ?: if (bookingId.isBlank()) "预约记录不存在或已失效，请刷新后重试" else null
    if (blockedMessage != null) {
      setActionMessage(blockedMessage)
      return
    }
    _uiState.value = current.copy(cancelingBookingId = bookingId, actionMessage = null)
    viewModelScope.launch {
      libBookApi
          .cancelBooking(bookingId)
          .onSuccess {
            _uiState.value =
                _uiState.value.copy(
                    cancelingBookingId = null,
                    actionMessage = it.message,
                )
            loadBookings(_uiState.value.bookings.page, _uiState.value.bookings.limit)
          }
          .onFailure {
            _uiState.value =
                _uiState.value.copy(
                    cancelingBookingId = null,
                    actionMessage = it.message ?: "取消预约失败",
                )
          }
    }
  }

  fun refreshReserveData() {
    val current = _uiState.value
    val areaId = current.selectedAreaId
    val slot = current.selectedSlot
    when {
      areaId != null && slot != null -> loadSeats(areaId, current.selectedDay, slot)
      areaId != null -> loadAreaDetailAndSeats(areaId)
      current.selectedLibraryId != null ->
          loadAreas(current.selectedLibraryId, current.selectedStoreyId, current.selectedDay)
      else -> loadLibraries()
    }
  }

  fun refreshSelectedSeats() {
    val current = _uiState.value
    val areaId = current.selectedAreaId ?: return
    val slot = current.selectedSlot ?: return
    loadSeats(areaId, current.selectedDay.ifBlank { currentDateProvider() }, slot)
  }

  fun clearActionMessage() {
    _uiState.value = _uiState.value.copy(actionMessage = null)
  }

  private fun loadAreas(libraryId: String, storeyId: String?, day: String) {
    val version = ++areasVersion
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isAreasLoading = true, areasError = null)
      libBookApi
          .getAreas(libraryId, storeyId, day)
          .onSuccess { areas ->
            if (
                version != areasVersion ||
                    _uiState.value.selectedLibraryId != libraryId ||
                    _uiState.value.selectedStoreyId != storeyId
            )
                return@onSuccess
            val areaId =
                _uiState.value.selectedAreaId?.takeIf { id -> areas.any { it.id == id } }
                    ?: areas.firstOrNull()?.id
            _uiState.value =
                _uiState.value.copy(
                    isAreasLoading = false,
                    areas = areas,
                    selectedAreaId = areaId,
                    areasError = null,
                    selectedSeatId = null,
                )
            if (areaId != null) {
              loadAreaDetailAndSeats(areaId)
            }
          }
          .onFailure {
            if (version != areasVersion) return@onFailure
            _uiState.value =
                _uiState.value.copy(
                    isAreasLoading = false,
                    areasError = it.message ?: "加载分区失败",
                )
          }
    }
  }

  private fun loadAreaDetailAndSeats(areaId: String) {
    val version = ++detailVersion
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isSeatsLoading = true, seatsError = null)
      libBookApi
          .getAreaDetail(areaId)
          .onSuccess { detail ->
            if (version != detailVersion || _uiState.value.selectedAreaId != areaId)
                return@onSuccess
            val requestedDay = _uiState.value.selectedDay
            val day =
                requestedDay.takeIf { it in detail.availableDates }
                    ?: detail.availableDates.firstOrNull()
                    ?: currentDateProvider()
            val slot = (detail.timeSlotsByDay[day] ?: detail.timeSlots).firstOrNull()
            _uiState.value =
                _uiState.value.copy(
                    areaDetail = detail,
                    selectedDay = day,
                    selectedSlotId = slot?.id,
                    selectedSeatId = null,
                    seats = emptyList(),
                    mapImages = detail.mapImages,
                    isSeatsLoading = false,
                    seatsError = if (slot == null) "当前分区暂无可预约时段" else null,
                )
            if (slot != null) loadSeats(areaId, day, slot)
          }
          .onFailure {
            if (version != detailVersion) return@onFailure
            _uiState.value =
                _uiState.value.copy(isSeatsLoading = false, seatsError = it.message ?: "加载分区信息失败")
          }
    }
  }

  private fun loadSeats(areaId: String, day: String, slot: LibBookTimeSlotDto) {
    val version = ++seatsVersion
    fun currentRequest(): Boolean =
        version == seatsVersion &&
            _uiState.value.selectedAreaId == areaId &&
            _uiState.value.selectedDay == day &&
            _uiState.value.selectedSlotId == slot.id
    viewModelScope.launch {
      _uiState.value =
          _uiState.value.copy(
              isSeatsLoading = _uiState.value.seats.isEmpty(),
              isRefreshingSeats = true,
              seatsError = null,
          )
      libBookApi
          .getSeatSnapshot(areaId, day, slot.start, slot.end)
          .onSuccess { snapshot ->
            if (!currentRequest()) return@onSuccess
            val seats = snapshot.seats
            val requestedSeat = _uiState.value.selectedSeatId
            val selectedSeat =
                requestedSeat?.takeIf { id ->
                  seats.any { it.id == id && it.isAvailable && it.inLabel }
                }
            val lostSelection = requestedSeat != null && selectedSeat == null
            _uiState.value =
                _uiState.value.copy(
                    isSeatsLoading = false,
                    isRefreshingSeats = false,
                    seats = seats,
                    selectedSeatId = selectedSeat,
                    seatStatusStale = false,
                    mapImages =
                        snapshot.mapImages.takeIf { it.free.isNotBlank() }
                            ?: _uiState.value.mapImages,
                    seatsError = null,
                    actionMessage =
                        if (lostSelection) "所选座位当前不可预约，请重新选择" else _uiState.value.actionMessage,
                )
          }
          .onFailure {
            if (!currentRequest()) return@onFailure
            _uiState.value =
                _uiState.value.copy(
                    isSeatsLoading = false,
                    isRefreshingSeats = false,
                    seatStatusStale = true,
                    seatsError = it.message ?: "加载座位失败",
                )
          }
    }
  }

  private fun setActionMessage(message: String) {
    _uiState.value = _uiState.value.copy(actionMessage = message)
  }

  private fun resolveStoreyId(
      libraries: List<LibBookLibraryDto>,
      libraryId: String?,
      preferredStoreyId: String?,
  ): String? {
    val storeys = libraries.firstOrNull { it.id == libraryId }?.storeys.orEmpty()
    if (storeys.isEmpty()) return null
    return preferredStoreyId?.takeIf { id -> storeys.any { it.id == id } } ?: storeys.first().id
  }
}

val LibBookUiState.selectedLibrary: LibBookLibraryDto?
  get() = libraries.firstOrNull { it.id == selectedLibraryId }

val LibBookUiState.selectedArea: LibBookAreaDto?
  get() = areas.firstOrNull { it.id == selectedAreaId }

val LibBookUiState.selectedSlot: LibBookTimeSlotDto?
  get() = availableSlots.firstOrNull { it.id == selectedSlotId }

val LibBookUiState.selectedSeat: LibBookSeatDto?
  get() = seats.firstOrNull { it.id == selectedSeatId }

val LibBookUiState.availableSlots: List<LibBookTimeSlotDto>
  get() = areaDetail?.let { it.timeSlotsByDay[selectedDay] ?: it.timeSlots }.orEmpty()
