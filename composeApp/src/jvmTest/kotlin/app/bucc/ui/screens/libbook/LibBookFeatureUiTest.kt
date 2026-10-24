package app.bucc.ui.screens.libbook

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.bucc.api.feature.LibBookApi
import app.bucc.api.feature.LibBookSeminarApi
import app.bucc.model.dto.*
import app.bucc.ui.saveRenderedEvidence
import app.bucc.ui.theme.BUCCTheme
import app.bucc.ui.theme.ThemeMode
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class LibBookFeatureUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `seat map and existing list share selection and require explicit confirmation`() {
    val api = PreviewSeatApi()
    val vm = LibBookViewModel(api, currentDateProvider = { "2026-10-17" })
    compose.setContent {
      BUCCTheme(ThemeMode.LIGHT) {
        Surface(Modifier.width(390.dp).fillMaxHeight().testTag("library-preview")) {
          LibBookReserveScreen(vm, {})
        }
      }
    }
    compose.waitUntil(5000) { vm.uiState.value.seats.size == 2 }
    compose.onNodeWithText("地图模式").performScrollTo().assertIsDisplayed()
    compose.onNodeWithContentDescription("放大地图").assertExists()
    compose.runOnIdle { vm.selectSeat("101") }
    compose.waitForIdle()
    saveRenderedEvidence(
        "library-seat-map",
        compose.onNodeWithTag("library-preview").captureToImage(),
    )
    compose.runOnIdle { vm.selectSeat("101") }
    compose.onNodeWithText("列表模式").performClick()
    compose.onNodeWithText("001", substring = false).performScrollTo().performClick()
    assertEquals("101", vm.uiState.value.selectedSeatId)
    compose.onNodeWithText("确认预约").performScrollTo().performClick()
    compose.onNodeWithText("确认座位预约").assertIsDisplayed()
    assertEquals(0, api.submits)
    saveRenderedEvidence(
        "library-seat-confirmation",
        compose.onNodeWithTag("library-preview").captureToImage(),
    )
  }

  @Test
  fun `seminar room shows availability timeline and start end options`() {
    val api = PreviewSeminarApi()
    val vm = LibBookSeminarViewModel(api, now = { "2026-10-17" to 7 * 60 })
    compose.setContent {
      BUCCTheme(ThemeMode.LIGHT) {
        Surface(Modifier.width(390.dp).fillMaxHeight().testTag("seminar-preview")) {
          LibBookSeminarScreen(vm)
        }
      }
    }
    compose.waitUntil(5000) { vm.uiState.value.rooms.isNotEmpty() }
    compose.onNodeWithText("模拟校区").assertExists()
    compose.onNodeWithText("一层").assertExists()
    saveRenderedEvidence(
        "library-seminar-filters",
        compose.onNodeWithTag("seminar-preview").captureToImage(),
    )
    compose.onNodeWithText("模拟图书馆研讨室").performScrollTo().performClick()
    compose.waitUntil(5000) { vm.uiState.value.detail != null }
    compose.runOnIdle {
      vm.selectBegin(9 * 60)
      vm.selectFinish(10 * 60)
    }
    compose.onNodeWithText("全天可用时间", substring = true).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("开始时间").assertExists()
    compose.onNodeWithText("结束时间", substring = true).assertExists()
    assertEquals(0, api.submits)
    saveRenderedEvidence(
        "library-seminar-timeline",
        compose.onNodeWithTag("seminar-preview").captureToImage(),
    )
  }
}

private class PreviewSeatApi : LibBookApi() {
  var submits = 0

  override suspend fun getLibraries(day: String) =
      Result.success(
          listOf(LibBookLibraryDto("9", "模拟图书馆", storeys = listOf(LibBookStoreyDto("10", "一层"))))
      )

  override suspend fun getAreas(premisesId: String, storeyId: String?, day: String) =
      Result.success(listOf(LibBookAreaDto("8", "一层西阅学空间")))

  override suspend fun getAreaDetail(areaId: String) =
      Result.success(
          LibBookAreaDetailDto(
              "8",
              "一层西阅学空间",
              listOf("2026-10-17"),
              listOf(LibBookTimeSlotDto("morning", "08:00", "12:00")),
          )
      )

  override suspend fun getSeatSnapshot(
      areaId: String,
      day: String,
      startTime: String,
      endTime: String,
  ) =
      Result.success(
          LibBookSeatSnapshotDto(
              listOf(
                  LibBookSeatDto(
                      "101",
                      no = "001",
                      status = "1",
                      pointX = 307.0 / 1921 * 100,
                      pointY = 141.0 / 1080 * 100,
                      width = 29.0 / 1921 * 100,
                      height = 28.0 / 1080 * 100,
                  ),
                  LibBookSeatDto(
                      "102",
                      no = "002",
                      status = "2",
                      pointX = 307.0 / 1921 * 100,
                      pointY = 172.0 / 1080 * 100,
                      width = 29.0 / 1921 * 100,
                      height = 28.0 / 1080 * 100,
                  ),
              )
          )
      )

  override suspend fun reserve(request: LibBookReserveRequest): Result<LibBookReserveResponse> {
    submits++
    return Result.success(LibBookReserveResponse(true, "模拟预约成功"))
  }
}

private class PreviewSeminarApi : LibBookSeminarApi() {
  var submits = 0
  private val room = LibBookSeminarRoom("156", "模拟图书馆研讨室", "6", "脱敏页面验证")

  override suspend fun dates() = Result.success(listOf("2026-10-17", "2026-10-18"))

  override suspend fun filters(date: String) =
      Result.success(
          LibBookSeminarFilters(
              listOf(
                  LibBookSeminarCampus(
                      "campus",
                      "模拟校区",
                      listOf(
                          LibBookSeminarBuilding(
                              "building",
                              "模拟图书馆",
                              listOf(LibBookSeminarFloor("floor", "一层")),
                          )
                      ),
                  )
              )
          )
      )

  override suspend fun rooms(date: String, buildingId: String, floorId: String?) =
      Result.success(listOf(room))

  override suspend fun detail(id: String, date: String) =
      Result.success(
          LibBookSeminarDetail(
              room,
              listOf(
                  LibBookSeminarDay(
                      date,
                      8 * 60,
                      22 * 60,
                      30,
                      60,
                      240,
                      listOf(LibBookSeminarInterval(12 * 60, 14 * 60)),
                  )
              ),
              "0",
              maxPeople = 6,
              rules = "请遵守图书馆使用规则，预约结束后及时离场。",
          )
      )

  override suspend fun submit(
      detail: LibBookSeminarDetail,
      request: LibBookSeminarRequest,
  ): Result<LibBookSeminarResult> {
    submits++
    return Result.success(LibBookSeminarResult(true, "模拟提交成功"))
  }
}
