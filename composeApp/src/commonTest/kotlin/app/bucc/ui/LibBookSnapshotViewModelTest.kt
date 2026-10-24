package app.bucc.ui

import app.bucc.api.feature.LibBookApi
import app.bucc.model.dto.LibBookAreaDetailDto
import app.bucc.model.dto.LibBookAreaDto
import app.bucc.model.dto.LibBookLibraryDto
import app.bucc.model.dto.LibBookMapImagesDto
import app.bucc.model.dto.LibBookQrTarget
import app.bucc.model.dto.LibBookReserveRequest
import app.bucc.model.dto.LibBookReserveResponse
import app.bucc.model.dto.LibBookSeatDto
import app.bucc.model.dto.LibBookSeatSnapshotDto
import app.bucc.model.dto.LibBookStoreyDto
import app.bucc.model.dto.LibBookTimeSlotDto
import app.bucc.ui.screens.libbook.LibBookViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LibBookSnapshotViewModelTest {
  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `day selection uses that days own time slots`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = SnapshotFake()
    val vm = LibBookViewModel(api, currentDateProvider = { DAY_ONE })

    vm.ensureInitialLoaded()
    advanceUntilIdle()
    assertEquals("morning", vm.uiState.value.selectedSlotId)

    vm.selectDay(DAY_TWO)
    advanceUntilIdle()

    assertEquals("evening", vm.uiState.value.selectedSlotId)
    assertEquals("18:00", api.snapshotCalls.last().start)
    assertEquals("21:00", api.snapshotCalls.last().end)
  }

  @Test
  fun `late snapshots cannot replace newer area and day`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = SnapshotFake().apply { holdSnapshots = true }
    val vm = LibBookViewModel(api, currentDateProvider = { DAY_ONE })

    vm.ensureInitialLoaded()
    runCurrent()
    assertEquals(1, api.snapshotGates.size) // Area A, day one.

    vm.selectArea("B")
    runCurrent()
    assertEquals(2, api.snapshotGates.size) // Area B, day one.
    vm.selectDay(DAY_TWO)
    runCurrent()
    assertEquals(3, api.snapshotGates.size) // Area B, day two.

    api.snapshotGates[2].complete(Result.success(snapshot("new-day-seat")))
    runCurrent()
    api.snapshotGates[1].complete(Result.success(snapshot("old-area-seat")))
    api.snapshotGates[0].complete(Result.success(snapshot("old-day-seat")))
    runCurrent()

    assertEquals("B", vm.uiState.value.selectedAreaId)
    assertEquals(DAY_TWO, vm.uiState.value.selectedDay)
    assertEquals(listOf("new-day-seat"), vm.uiState.value.seats.map { it.id })
  }

  @Test
  fun `background refresh keeps visible seats until failed request finishes`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = SnapshotFake()
    val vm = LibBookViewModel(api, currentDateProvider = { DAY_ONE })
    vm.ensureInitialLoaded()
    advanceUntilIdle()
    val visibleBefore = vm.uiState.value.seats
    api.holdSnapshots = true

    vm.refreshSelectedSeats()
    runCurrent()

    assertEquals(visibleBefore, vm.uiState.value.seats)
    assertTrue(vm.uiState.value.isRefreshingSeats)
    assertFalse(vm.uiState.value.isSeatsLoading)

    api.snapshotGates.single().complete(Result.failure(IllegalStateException("offline")))
    runCurrent()
    assertEquals(visibleBefore, vm.uiState.value.seats)
    assertTrue(vm.uiState.value.seatStatusStale)
  }

  @Test
  fun `valid QR target selects seat but does not reserve before explicit submit`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = SnapshotFake()
    val vm = LibBookViewModel(api, currentDateProvider = { DAY_ONE })

    vm.openQrTarget(LibBookQrTarget("A", "seat-7", DAY_TWO))
    advanceUntilIdle()

    assertEquals("seat-7", vm.uiState.value.selectedSeatId)
    assertEquals(0, api.reserveCalls)

    vm.submitReservation()
    advanceUntilIdle()
    assertEquals(1, api.reserveCalls)
  }

  @Test
  fun `QR target with unavailable explicit day stops before seat lookup`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = SnapshotFake()
    val vm = LibBookViewModel(api, currentDateProvider = { DAY_ONE })

    vm.openQrTarget(LibBookQrTarget("A", "seat-7", "2026-10-19"))
    advanceUntilIdle()

    assertEquals("2026-10-19", vm.uiState.value.selectedDay)
    assertTrue(vm.uiState.value.seatsError.orEmpty().contains("指定日期"))
    assertTrue(api.snapshotCalls.isEmpty())
    assertEquals(0, api.reserveCalls)
  }

  @Test
  fun `pre-submit snapshot blocks a seat that became occupied`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = SnapshotFake()
    val vm = LibBookViewModel(api, currentDateProvider = { DAY_ONE })
    vm.ensureInitialLoaded()
    advanceUntilIdle()
    vm.selectSeat("seat-7")
    api.nextSnapshot = snapshot("seat-7", available = false)

    vm.submitReservation()
    advanceUntilIdle()

    assertEquals(0, api.reserveCalls)
    assertNull(vm.uiState.value.selectedSeatId)
    assertTrue(vm.uiState.value.actionMessage.orEmpty().contains("不可预约"))
  }

  @Test
  fun `repeated submit while snapshot is pending sends at most one reservation`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = SnapshotFake()
    val vm = LibBookViewModel(api, currentDateProvider = { DAY_ONE })
    vm.ensureInitialLoaded()
    advanceUntilIdle()
    vm.selectSeat("seat-7")
    api.holdSnapshots = true

    vm.submitReservation()
    vm.submitReservation()
    runCurrent()
    assertEquals(1, api.snapshotGates.size)
    assertEquals(0, api.reserveCalls)

    api.snapshotGates.single().complete(Result.success(snapshot("seat-7")))
    advanceUntilIdle()

    assertEquals(1, api.reserveCalls)
  }

  @Test
  fun `reset during pre-submit refresh prevents stale reservation`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = SnapshotFake()
    val vm = LibBookViewModel(api, currentDateProvider = { DAY_ONE })
    vm.ensureInitialLoaded()
    advanceUntilIdle()
    vm.selectSeat("seat-7")
    api.holdSnapshots = true
    vm.submitReservation()
    runCurrent()
    vm.resetLoadedState()
    api.snapshotGates.single().complete(Result.success(snapshot("seat-7")))
    advanceUntilIdle()
    assertEquals(0, api.reserveCalls)
    assertNull(vm.uiState.value.selectedAreaId)
    assertNull(vm.uiState.value.actionMessage)
  }

  private class SnapshotFake : LibBookApi() {
    data class SnapshotCall(val areaId: String, val day: String, val start: String, val end: String)

    val snapshotCalls = mutableListOf<SnapshotCall>()
    val snapshotGates = mutableListOf<CompletableDeferred<Result<LibBookSeatSnapshotDto>>>()
    var holdSnapshots = false
    var nextSnapshot: LibBookSeatSnapshotDto? = null
    var reserveCalls = 0

    override suspend fun getLibraries(day: String): Result<List<LibBookLibraryDto>> =
        Result.success(
            listOf(
                LibBookLibraryDto(
                    id = "lib",
                    name = "测试馆",
                    storeys = listOf(LibBookStoreyDto("floor", "一层")),
                )
            )
        )

    override suspend fun getAreas(
        premisesId: String,
        storeyId: String?,
        day: String,
    ): Result<List<LibBookAreaDto>> =
        Result.success(listOf(LibBookAreaDto("A", "甲区"), LibBookAreaDto("B", "乙区")))

    override suspend fun getAreaDetail(areaId: String): Result<LibBookAreaDetailDto> =
        Result.success(
            LibBookAreaDetailDto(
                id = areaId,
                name = areaId,
                availableDates = listOf(DAY_ONE, DAY_TWO),
                timeSlots = listOf(LibBookTimeSlotDto("morning", "08:00", "12:00")),
                timeSlotsByDay =
                    mapOf(
                        DAY_ONE to listOf(LibBookTimeSlotDto("morning", "08:00", "12:00")),
                        DAY_TWO to listOf(LibBookTimeSlotDto("evening", "18:00", "21:00")),
                    ),
                mapImages = LibBookMapImagesDto(free = "fixture-map"),
            )
        )

    override suspend fun getSeatSnapshot(
        areaId: String,
        day: String,
        startTime: String,
        endTime: String,
    ): Result<LibBookSeatSnapshotDto> {
      snapshotCalls += SnapshotCall(areaId, day, startTime, endTime)
      if (holdSnapshots) {
        val gate = CompletableDeferred<Result<LibBookSeatSnapshotDto>>()
        snapshotGates += gate
        return gate.await()
      }
      val snapshot = nextSnapshot.also { nextSnapshot = null } ?: snapshot("seat-7")
      return Result.success(snapshot)
    }

    override suspend fun reserve(request: LibBookReserveRequest): Result<LibBookReserveResponse> {
      reserveCalls++
      return Result.success(LibBookReserveResponse(true, "预约成功"))
    }
  }

  private companion object {
    const val DAY_ONE = "2026-10-17"
    const val DAY_TWO = "2026-10-18"

    fun snapshot(seatId: String, available: Boolean = true) =
        LibBookSeatSnapshotDto(
            seats =
                listOf(
                    LibBookSeatDto(
                        id = seatId,
                        no = seatId,
                        status = if (available) "1" else "2",
                        isAvailable = available,
                    )
                ),
            mapImages = LibBookMapImagesDto(free = "snapshot-$seatId"),
        )
  }
}
