package app.bucc.ui

import app.bucc.api.feature.LibBookSeminarApi
import app.bucc.model.dto.*
import app.bucc.ui.screens.libbook.LibBookSeminarViewModel
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class LibBookSeminarViewModelTest {
  @AfterTest
  fun clean() {
    Dispatchers.resetMain()
  }

  private open class Fake : LibBookSeminarApi() {
    var occupied = false
    var submits = 0
    var fails = false
    var requiresAttachment = false

    override suspend fun dates() = Result.success(listOf("2026-10-17"))

    override suspend fun filters(date: String) =
        Result.success(
            LibBookSeminarFilters(
                listOf(
                    LibBookSeminarCampus(
                        "campus-a",
                        "学院路校区",
                        listOf(
                            LibBookSeminarBuilding(
                                "building-a",
                                "图书馆 A",
                                listOf(
                                    LibBookSeminarFloor("floor-a1", "一层"),
                                    LibBookSeminarFloor("floor-a2", "二层"),
                                ),
                            ),
                            LibBookSeminarBuilding(
                                "building-a2",
                                "图书馆 A2",
                                listOf(LibBookSeminarFloor("floor-a21", "一层")),
                            ),
                        ),
                    ),
                    LibBookSeminarCampus(
                        "campus-b",
                        "沙河校区",
                        listOf(
                            LibBookSeminarBuilding(
                                "building-b",
                                "图书馆 B",
                                listOf(LibBookSeminarFloor("floor-b1", "一层")),
                            )
                        ),
                    ),
                )
            )
        )

    override suspend fun rooms(date: String, buildingId: String, floorId: String?) = rooms(date)

    override suspend fun rooms(date: String) =
        Result.success(listOf(LibBookSeminarRoom("156", "测试")))

    override suspend fun detail(id: String, date: String) =
        Result.success(
            LibBookSeminarDetail(
                LibBookSeminarRoom(id, "测试"),
                listOf(
                    LibBookSeminarDay(
                        date,
                        480,
                        1080,
                        30,
                        60,
                        180,
                        if (occupied) listOf(LibBookSeminarInterval(540, 600)) else emptyList(),
                    )
                ),
                "0",
                requiresAttachment = requiresAttachment,
            )
        )

    override suspend fun uploadAttachment(fileName: String, mimeType: String, bytes: ByteArray) =
        Result.success(buildJsonObject { put("file_name", fileName) })

    override suspend fun submit(
        detail: LibBookSeminarDetail,
        request: LibBookSeminarRequest,
    ): Result<LibBookSeminarResult> {
      submits++
      return if (fails) Result.failure(IllegalStateException("网络中断"))
      else Result.success(LibBookSeminarResult(true, "成功"))
    }
  }

  @Test
  fun refreshBeforeConfirmationRefusesConflict() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = Fake()
    val vm = LibBookSeminarViewModel(api) { "2026-10-17" to 400 }
    vm.ensureLoaded()
    advanceUntilIdle()
    vm.openRoom("156")
    advanceUntilIdle()
    vm.selectBegin(540)
    vm.selectFinish(600)
    vm.editTitle("测试")
    vm.editContent("讨论")
    vm.editMobile("13000000000")
    api.occupied = true
    vm.prepareConfirmation()
    advanceUntilIdle()
    assertNull(vm.uiState.value.confirmation)
    assertEquals(0, api.submits)
    assertNotNull(vm.uiState.value.message)
  }

  @Test
  fun requiresExplicitConfirmationAndDoesNotReplayFailedSubmit() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = Fake()
    val vm = LibBookSeminarViewModel(api) { "2026-10-17" to 400 }
    vm.ensureLoaded()
    advanceUntilIdle()
    vm.openRoom("156")
    advanceUntilIdle()
    vm.selectBegin(540)
    vm.selectFinish(600)
    vm.editTitle("测试")
    vm.editContent("讨论")
    vm.editMobile("13000000000")
    vm.prepareConfirmation()
    advanceUntilIdle()
    assertNotNull(vm.uiState.value.confirmation)
    assertEquals(0, api.submits)
    api.fails = true
    vm.submitConfirmed()
    vm.submitConfirmed()
    advanceUntilIdle()
    vm.refresh()
    advanceUntilIdle()
    assertEquals(1, api.submits)
    assertNull(vm.uiState.value.confirmation)
    assertTrue(vm.uiState.value.message.orEmpty().contains("不会自动重试"))
  }

  @Test
  fun uploadRequiredBeforeConfirmationAndTransientBytesAreWiped() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val api = Fake().apply { requiresAttachment = true }
    val vm = LibBookSeminarViewModel(api) { "2026-10-17" to 400 }
    vm.ensureLoaded()
    advanceUntilIdle()
    vm.openRoom("156")
    advanceUntilIdle()
    vm.selectBegin(540)
    vm.selectFinish(600)
    vm.editTitle("测试")
    vm.editContent("讨论")
    vm.editMobile("13000000000")
    vm.prepareConfirmation()
    advanceUntilIdle()
    assertNull(vm.uiState.value.confirmation)
    val bytes = byteArrayOf(1, 2, 3, 4)
    vm.uploadAttachment("fixture.pdf", "application/pdf", bytes)
    advanceUntilIdle()
    assertTrue(bytes.all { it == 0.toByte() })
    assertNotNull(vm.uiState.value.attachment)
    vm.prepareConfirmation()
    advanceUntilIdle()
    assertNotNull(vm.uiState.value.confirmation)
    assertEquals(0, api.submits)
  }

  @Test
  fun locationChangesClearInvalidChildrenRoomsAndSelection() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val vm = LibBookSeminarViewModel(Fake()) { "2026-10-17" to 400 }
    vm.ensureLoaded()
    advanceUntilIdle()
    assertEquals("campus-a", vm.uiState.value.campusId)
    assertEquals("building-a", vm.uiState.value.buildingId)
    assertEquals("floor-a1", vm.uiState.value.floorId)
    vm.openRoom("156")
    advanceUntilIdle()
    vm.selectBegin(540)
    vm.selectFinish(600)
    vm.selectCampus("campus-b")
    assertTrue(vm.uiState.value.rooms.isEmpty())
    assertNull(vm.uiState.value.detail)
    assertNull(vm.uiState.value.begin)
    assertEquals("building-b", vm.uiState.value.buildingId)
    assertEquals("floor-b1", vm.uiState.value.floorId)
    advanceUntilIdle()
    vm.selectFloor("floor-a2")
    assertEquals("floor-b1", vm.uiState.value.floorId)
    vm.selectCampus("campus-a")
    advanceUntilIdle()
    vm.selectBuilding("building-a2")
    advanceUntilIdle()
    assertEquals("floor-a21", vm.uiState.value.floorId)
  }

  @Test
  fun lateOldFloorResponseCannotOverwriteCurrentRooms() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val old = CompletableDeferred<Result<List<LibBookSeminarRoom>>>()
    var delayOld = false
    val calls = mutableListOf<Pair<String, String?>>()
    val api =
        object : Fake() {
          override suspend fun rooms(
              date: String,
              buildingId: String,
              floorId: String?,
          ): Result<List<LibBookSeminarRoom>> {
            calls += buildingId to floorId
            return if (delayOld && floorId == "floor-a1") old.await()
            else Result.success(listOf(LibBookSeminarRoom(floorId.orEmpty(), "当前楼层")))
          }
        }
    val vm = LibBookSeminarViewModel(api) { "2026-10-17" to 400 }
    vm.ensureLoaded()
    advanceUntilIdle()
    delayOld = true
    vm.refresh()
    runCurrent()
    vm.selectFloor("floor-a2")
    runCurrent()
    assertEquals("floor-a2", vm.uiState.value.rooms.single().id)
    old.complete(Result.success(listOf(LibBookSeminarRoom("old", "旧楼层"))))
    advanceUntilIdle()
    assertEquals("floor-a2", vm.uiState.value.rooms.single().id)
    assertTrue(calls.contains("building-a" to "floor-a2"))
  }

  @Test
  fun roomRefreshHidesOldRoomsUntilTheFreshListArrives() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val freshRooms = CompletableDeferred<Result<List<LibBookSeminarRoom>>>()
    var blockRefresh = false
    val api =
        object : Fake() {
          override suspend fun rooms(
              date: String,
              buildingId: String,
              floorId: String?,
          ): Result<List<LibBookSeminarRoom>> =
              if (blockRefresh) freshRooms.await()
              else Result.success(listOf(LibBookSeminarRoom("156", "旧结果")))
        }
    val vm = LibBookSeminarViewModel(api) { "2026-10-17" to 400 }
    vm.ensureLoaded()
    advanceUntilIdle()
    assertEquals("旧结果", vm.uiState.value.rooms.single().name)

    blockRefresh = true
    vm.refresh()
    runCurrent()
    assertTrue(vm.uiState.value.loading)
    assertTrue(vm.uiState.value.rooms.isEmpty())

    freshRooms.complete(Result.success(listOf(LibBookSeminarRoom("157", "新结果"))))
    advanceUntilIdle()
    assertEquals("新结果", vm.uiState.value.rooms.single().name)
  }
}
