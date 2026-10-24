package app.bucc.ui

import app.bucc.api.feature.ClassroomApi
import app.bucc.model.dto.ClassroomData
import app.bucc.model.dto.ClassroomInfo
import app.bucc.model.dto.ClassroomQueryResponse
import app.bucc.ui.screens.classroom.ClassroomFloor
import app.bucc.ui.screens.classroom.ClassroomSortOrder
import app.bucc.ui.screens.classroom.ClassroomViewModel
import app.bucc.ui.screens.classroom.analyzeBuildingFloorIds
import app.bucc.ui.screens.classroom.classroomFloor
import app.bucc.ui.screens.classroom.classroomFloorOption
import app.bucc.ui.screens.classroom.sortBuildings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
class ClassroomViewModelTest {
  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `query defaults to all buildings with no selection`() = runTest {
    setMainDispatcher(testScheduler)
    val viewModel = ClassroomViewModel(fakeApi())

    viewModel.query()
    advanceUntilIdle()

    assertNull(viewModel.selectedBuilding.value)
    assertEquals(listOf("教学楼A", "教学楼B"), viewModel.availableBuildings.value)
    assertEquals(listOf("教学楼A", "教学楼B"), viewModel.filteredData.value.keys.toList())
  }

  @Test
  fun `buildings are ordered by floorid string when each building has one unique floorid`() {
    val ordered =
        sortBuildings(
            linkedMapOf(
                "三号教学楼" to
                    listOf(
                        ClassroomInfo(id = "1", floorid = "2J03", name = "J3-101", kxsds = "1,2")
                    ),
                "教零楼" to
                    listOf(
                        ClassroomInfo(id = "2", floorid = "2J00", name = "J0-101", kxsds = "1,2")
                    ),
                "一号教学楼" to
                    listOf(
                        ClassroomInfo(id = "3", floorid = "2J01", name = "J1-101", kxsds = "1,2")
                    ),
                "沙河校区二号楼" to
                    listOf(
                        ClassroomInfo(id = "4", floorid = "2Z02", name = "SH2-101", kxsds = "1,2")
                    ),
                "沙河校区三号楼" to
                    listOf(
                        ClassroomInfo(id = "5", floorid = "2Z03", name = "SH3-101", kxsds = "1,2")
                    ),
            )
        )

    assertEquals(
        listOf("教零楼", "一号教学楼", "三号教学楼", "沙河校区二号楼", "沙河校区三号楼"),
        ordered,
    )
  }

  @Test
  fun `falls back to natural ordering when a building has multiple floorids`() {
    val ordered =
        sortBuildings(
            linkedMapOf(
                "10号楼" to
                    listOf(
                        ClassroomInfo(id = "1", floorid = "10", name = "10-101", kxsds = "1,2"),
                        ClassroomInfo(id = "2", floorid = "11", name = "10-102", kxsds = "1,2"),
                    ),
                "2号楼" to
                    listOf(ClassroomInfo(id = "3", floorid = "2", name = "2-101", kxsds = "1,2")),
                "教零楼" to
                    listOf(ClassroomInfo(id = "4", floorid = "0", name = "J0-101", kxsds = "1,2")),
            )
        )

    assertEquals(listOf("2号楼", "10号楼", "教零楼"), ordered)
  }

  @Test
  fun `falls back when duplicate floorids exist across buildings`() {
    val analysis =
        analyzeBuildingFloorIds(
            linkedMapOf(
                "一号楼" to
                    listOf(
                        ClassroomInfo(id = "1", floorid = "2J01", name = "1-101", kxsds = "1,2")
                    ),
                "二号楼" to
                    listOf(
                        ClassroomInfo(id = "2", floorid = "2J01", name = "2-101", kxsds = "1,2")
                    ),
            )
        )

    assertTrue(!analysis.canUseFloorIdOrdering)
  }

  @Test
  fun `toggle building filters and second tap clears selection`() = runTest {
    setMainDispatcher(testScheduler)
    val viewModel = ClassroomViewModel(fakeApi())

    viewModel.query()
    advanceUntilIdle()
    viewModel.toggleBuilding("教学楼A")
    advanceUntilIdle()

    assertEquals("教学楼A", viewModel.selectedBuilding.value)
    assertEquals(listOf("教学楼A"), viewModel.filteredData.value.keys.toList())

    viewModel.toggleBuilding("教学楼A")
    advanceUntilIdle()

    assertNull(viewModel.selectedBuilding.value)
    assertEquals(listOf("教学楼A", "教学楼B"), viewModel.filteredData.value.keys.toList())
  }

  @Test
  fun `search query and building filter work together`() = runTest {
    setMainDispatcher(testScheduler)
    val viewModel = ClassroomViewModel(fakeApi())

    viewModel.query()
    advanceUntilIdle()
    viewModel.toggleBuilding("教学楼A")
    viewModel.setSearchQuery("102")
    advanceUntilIdle()

    val result = viewModel.filteredData.value
    assertEquals(listOf("教学楼A"), result.keys.toList())
    assertEquals(listOf("A-102"), result.getValue("教学楼A").map { it.name })
  }

  @Test
  fun `floor filter applies across all buildings and explicit all clears it`() = runTest {
    setMainDispatcher(testScheduler)
    val viewModel = ClassroomViewModel(fakeApi())

    viewModel.query()
    advanceUntilIdle()
    assertEquals(listOf(ClassroomFloor(1), ClassroomFloor(2)), viewModel.availableFloors.value)

    viewModel.setFloor(ClassroomFloor(1))
    advanceUntilIdle()
    assertEquals(
        mapOf("教学楼A" to listOf("A-102", "A-101")),
        viewModel.filteredData.value.mapValues { (_, rooms) -> rooms.map { it.name } },
    )

    viewModel.setFloor(null)
    advanceUntilIdle()
    assertEquals(listOf("教学楼A", "教学楼B"), viewModel.filteredData.value.keys.toList())
  }

  @Test
  fun `rooms sort by free period count then natural name and by name`() = runTest {
    setMainDispatcher(testScheduler)
    val viewModel = ClassroomViewModel(fakeApi())

    viewModel.query()
    advanceUntilIdle()
    viewModel.setBuilding("教学楼A")
    viewModel.setSortOrder(ClassroomSortOrder.FREE_PERIODS)
    advanceUntilIdle()
    assertEquals(
        listOf("A-102", "A-101"),
        viewModel.filteredData.value.getValue("教学楼A").map { it.name },
    )

    viewModel.setSortOrder(ClassroomSortOrder.NAME)
    advanceUntilIdle()
    assertEquals(
        listOf("A-101", "A-102"),
        viewModel.filteredData.value.getValue("教学楼A").map { it.name },
    )
  }

  @Test
  fun `classroom floor is inferred from first digit and unparseable names are unknown`() {
    assertEquals(1, classroomFloor("A-101"))
    assertEquals(2, classroomFloor("B-2001"))
    assertNull(classroomFloor("报告厅"))
    assertEquals(ClassroomFloor(null), classroomFloorOption("报告厅"))
  }

  @Test
  fun `changing campus resets building selection and refreshes available buildings`() = runTest {
    setMainDispatcher(testScheduler)
    val viewModel = ClassroomViewModel(fakeApi())

    viewModel.query()
    advanceUntilIdle()
    viewModel.toggleBuilding("教学楼A")

    viewModel.setXqid(2)
    advanceUntilIdle()

    assertNull(viewModel.selectedBuilding.value)
    assertEquals(listOf("沙河楼C"), viewModel.availableBuildings.value)
    assertEquals(listOf("沙河楼C"), viewModel.filteredData.value.keys.toList())
  }

  @Test
  fun `changing date resets building selection`() = runTest {
    setMainDispatcher(testScheduler)
    val viewModel = ClassroomViewModel(fakeApi())

    viewModel.query()
    advanceUntilIdle()
    viewModel.toggleBuilding("教学楼A")

    viewModel.setDate("2026-03-20")
    advanceUntilIdle()

    assertNull(viewModel.selectedBuilding.value)
    assertEquals(listOf("教学楼A", "教学楼B"), viewModel.filteredData.value.keys.toList())
  }

  private fun setMainDispatcher(testScheduler: TestCoroutineScheduler) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  }

  private fun fakeApi(): ClassroomApi {
    return object : ClassroomApi() {
      override suspend fun queryClassrooms(
          xqid: Int,
          date: String,
      ): Result<ClassroomQueryResponse> {
        val response =
            when (xqid) {
              2 ->
                  classroomResponse(
                      mapOf(
                          "沙河楼C" to
                              listOf(
                                  ClassroomInfo(
                                      id = "3",
                                      floorid = "c1",
                                      name = "C-201",
                                      kxsds = "1,2,3",
                                  )
                              )
                      )
                  )
              else ->
                  classroomResponse(
                      mapOf(
                          "教学楼A" to
                              listOf(
                                  ClassroomInfo(
                                      id = "1",
                                      floorid = "a1",
                                      name = "A-101",
                                      kxsds = "1,2,3",
                                  ),
                                  ClassroomInfo(
                                      id = "2",
                                      floorid = "a1",
                                      name = "A-102",
                                      kxsds = "4,5,6,7",
                                  ),
                              ),
                          "教学楼B" to
                              listOf(
                                  ClassroomInfo(
                                      id = "4",
                                      floorid = "b1",
                                      name = "B-201",
                                      kxsds = "1,2,3",
                                  )
                              ),
                      )
                  )
            }
        return Result.success(response)
      }
    }
  }

  private fun classroomResponse(
      buildings: Map<String, List<ClassroomInfo>>
  ): ClassroomQueryResponse {
    return ClassroomQueryResponse(
        e = 0,
        m = "ok",
        d = ClassroomData(list = buildings),
    )
  }
}
