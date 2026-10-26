package app.bucc.ui

import app.bucc.api.feature.LibBookApi
import app.bucc.model.dto.LibBookAreaDetailDto
import app.bucc.model.dto.LibBookAreaDto
import app.bucc.model.dto.LibBookBookingDto
import app.bucc.model.dto.LibBookBookingsResponse
import app.bucc.model.dto.LibBookCancelResponse
import app.bucc.model.dto.LibBookLibraryDto
import app.bucc.model.dto.LibBookReserveRequest
import app.bucc.model.dto.LibBookReserveResponse
import app.bucc.model.dto.LibBookSeatDto
import app.bucc.model.dto.LibBookStoreyDto
import app.bucc.model.dto.LibBookTimeSlotDto
import app.bucc.ui.screens.libbook.LibBookViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LibBookViewModelTest {
  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `initial load selects first library area slot and loads seats`() = runTest {
    setMainDispatcher(testScheduler)
    val api = FakeLibBookApi()
    val viewModel = LibBookViewModel(api, currentDateProvider = { "2026-05-08" })

    viewModel.ensureInitialLoaded()
    advanceUntilIdle()

    assertEquals("9", viewModel.uiState.value.selectedLibraryId)
    assertEquals("10", viewModel.uiState.value.selectedStoreyId)
    assertEquals("8", viewModel.uiState.value.selectedAreaId)
    assertEquals("seg-1", viewModel.uiState.value.selectedSlotId)
    assertEquals(2, viewModel.uiState.value.seats.size)
    assertEquals(1, api.librariesCalls)
    assertEquals(1, api.areasCalls)
    assertEquals(1, api.seatsCalls)
  }

  @Test
  fun `submit reservation refreshes seats and bookings`() = runTest {
    setMainDispatcher(testScheduler)
    val api = FakeLibBookApi()
    val viewModel = LibBookViewModel(api, currentDateProvider = { "2026-05-08" })

    viewModel.ensureInitialLoaded()
    advanceUntilIdle()
    viewModel.selectSeat("101")
    viewModel.submitReservation()
    advanceUntilIdle()

    assertEquals(1, api.reserveCalls)
    assertEquals(3, api.seatsCalls)
    assertEquals(1, api.bookingsCalls)
    assertEquals("预约成功", viewModel.uiState.value.actionMessage)
    assertEquals(null, viewModel.uiState.value.selectedSeatId)
  }

  @Test
  fun `negative success message cannot navigate or refresh as a reservation success`() = runTest {
    setMainDispatcher(testScheduler)
    val api = FakeLibBookApi(reservation = LibBookReserveResponse(false, "预约未成功"))
    val viewModel = LibBookViewModel(api, currentDateProvider = { "2026-05-08" })
    viewModel.ensureInitialLoaded()
    advanceUntilIdle()
    viewModel.selectSeat("101")
    viewModel.submitReservation()
    advanceUntilIdle()
    assertEquals(false, viewModel.uiState.value.reservationSucceeded)
    assertEquals("101", viewModel.uiState.value.selectedSeatId)
    assertEquals(0, api.bookingsCalls)
    assertEquals("预约未成功", viewModel.uiState.value.actionMessage)
  }

  @Test
  fun `cancel booking refreshes bookings`() = runTest {
    setMainDispatcher(testScheduler)
    val api = FakeLibBookApi()
    val viewModel = LibBookViewModel(api, currentDateProvider = { "2026-05-08" })

    viewModel.loadBookings()
    advanceUntilIdle()
    viewModel.cancelBooking("b1")
    advanceUntilIdle()

    assertEquals(1, api.cancelCalls)
    assertEquals(2, api.bookingsCalls)
    assertTrue(viewModel.uiState.value.actionMessage?.contains("取消") == true)
  }

  @Test
  fun `cancel ended booking is blocked locally without api call`() = runTest {
    setMainDispatcher(testScheduler)
    val api =
        FakeLibBookApi(
            bookings =
                listOf(
                    LibBookBookingDto(
                        id = "b-ended",
                        nameMerge = "一层 / 002",
                        status = "8",
                        statusName = "已结束",
                    )
                )
        )
    val viewModel = LibBookViewModel(api, currentDateProvider = { "2026-05-08" })

    viewModel.loadBookings()
    advanceUntilIdle()
    viewModel.cancelBooking("b-ended")
    advanceUntilIdle()

    assertEquals(0, api.cancelCalls)
    assertEquals(1, api.bookingsCalls)
    assertEquals("该预约已结束或已取消，无需取消", viewModel.uiState.value.actionMessage)
  }

  private fun setMainDispatcher(scheduler: TestCoroutineScheduler) {
    Dispatchers.setMain(StandardTestDispatcher(scheduler))
  }
}

private class FakeLibBookApi(
    private val reservation: LibBookReserveResponse = LibBookReserveResponse(true, "预约成功"),
    private val bookings: List<LibBookBookingDto> =
        listOf(LibBookBookingDto(id = "b1", nameMerge = "一层 / 101", status = "2", statusName = "已预约", cancellationAllowed = true))
) : LibBookApi() {
  var librariesCalls = 0
  var areasCalls = 0
  var seatsCalls = 0
  var reserveCalls = 0
  var bookingsCalls = 0
  var cancelCalls = 0

  override suspend fun getLibraries(day: String): Result<List<LibBookLibraryDto>> {
    librariesCalls++
    return Result.success(
        listOf(
            LibBookLibraryDto(
                id = "9",
                name = "学院路校区图书馆",
                freeNum = 12,
                totalNum = 100,
                storeys = listOf(LibBookStoreyDto("10", "一层", 5, 30)),
            )
        )
    )
  }

  override suspend fun getAreas(
      premisesId: String,
      storeyId: String?,
      day: String,
  ): Result<List<LibBookAreaDto>> {
    areasCalls++
    return Result.success(
        listOf(
            LibBookAreaDto(
                id = "8",
                name = "一层西阅学空间",
                areaName = "学院路",
                freeNum = 2,
                totalNum = 10,
            )
        )
    )
  }

  override suspend fun getAreaDetail(areaId: String): Result<LibBookAreaDetailDto> =
      Result.success(
          LibBookAreaDetailDto(
              id = areaId,
              name = "一层西阅学空间",
              availableDates = listOf("2026-05-08"),
              timeSlots = listOf(LibBookTimeSlotDto("seg-1", "08:00", "23:00")),
          )
      )

  override suspend fun getSeats(
      areaId: String,
      day: String,
      startTime: String,
      endTime: String,
  ): Result<List<LibBookSeatDto>> {
    seatsCalls++
    return Result.success(
        listOf(
            LibBookSeatDto(id = "101", no = "101", status = "1", statusName = "空闲"),
            LibBookSeatDto(id = "102", no = "102", status = "2", statusName = "已占用"),
        )
    )
  }

  override suspend fun getSeatSnapshot(
      areaId: String,
      day: String,
      startTime: String,
      endTime: String,
  ): Result<app.bucc.model.dto.LibBookSeatSnapshotDto> =
      getSeats(areaId, day, startTime, endTime).map {
        app.bucc.model.dto.LibBookSeatSnapshotDto(it)
      }

  override suspend fun reserve(request: LibBookReserveRequest): Result<LibBookReserveResponse> {
    reserveCalls++
    return Result.success(reservation)
  }

  override suspend fun getBookings(page: Int, limit: Int): Result<LibBookBookingsResponse> {
    bookingsCalls++
    return Result.success(
        LibBookBookingsResponse(
            bookings = bookings,
            page = page,
            limit = limit,
            total = bookings.size,
        )
    )
  }

  override suspend fun cancelBooking(bookingId: String): Result<LibBookCancelResponse> {
    cancelCalls++
    return Result.success(LibBookCancelResponse(success = true, message = "取消成功"))
  }
}
