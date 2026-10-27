package app.bucc.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.bucc.model.dto.CourseClass
import app.bucc.model.dto.SigninClassDto
import app.bucc.repository.UpcomingClass
import app.bucc.ui.saveRenderedEvidence
import app.bucc.ui.screens.grade.GradeScoreUpdateNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.Rule

class HomeTodoViewportTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `active signin appears only on the uniquely matching schedule course`() {
    var signedCourseId: String? = null
    var openedCourseName: String? = null
    val date = LocalDate.parse("2026-10-14")
    val matchingCourse = scheduleOccurrence("course-1", date, "大学物理", "08:00", "08:45")
    val sameNameDifferentTime = scheduleOccurrence("course-2", date, "大学物理", "09:00", "09:45")

    compose.setContent {
      MaterialTheme {
        Box(
            Modifier.width(390.dp).height(850.dp).background(MaterialTheme.colorScheme.background)
        ) {
          HomeScreen(
              upcomingClasses = listOf(matchingCourse, sameNameDifferentTime),
              signinClasses =
                  listOf(
                      SigninClassDto("signin-1", "大学物理", "08:00", "08:45", signStatus = 0),
                  ),
              now = LocalDateTime.parse("2026-10-14T07:55:00"),
              updatedAt = null,
              isLoading = false,
              isRefreshing = false,
              error = null,
              todayProgressOccurrences = emptyList(),
              scheduleHasLoaded = true,
              todoItems = emptyList(),
              todoLoading = false,
              todoLoadingSources = emptyList(),
              todoFailedSources = emptyList(),
              todoSourceErrors = emptyMap(),
              signingTodoId = null,
              scoreUpdateNotice = null as GradeScoreUpdateNotice?,
              onRetrySchedule = {},
              onRefresh = {},
              onOpenScoresClick = {},
              onDismissScoreNotice = {},
              onTodoClick = {},
              onSigninTodoClick = { signedCourseId = it },
              featureSections = emptyList(),
              openSectionId = null,
              onOpenSectionChange = {},
              onFeatureClick = {},
              onOpenSchedule = {},
              onOpenTodos = {},
              onFeatureOrderChange = { _, _ -> },
              onCourseClick = { openedCourseName = it.courseName },
          )
        }
      }
    }

    compose.onNodeWithTag("home-course-signin-signin-1").assertIsDisplayed().performClick()
    compose.onNodeWithTag("home-course-card-course-2").assertIsDisplayed()
    compose.onAllNodesWithTag("home-course-signin-signin-1").assertCountEquals(1)
    assertEquals("signin-1", signedCourseId)
    assertEquals(null, openedCourseName)
    compose.onNodeWithTag("home-screen-scroll").performScrollToNode(hasText("当前没有待办"))
    compose.onNodeWithText("当前没有待办").assertIsDisplayed()
  }

  @Test
  fun `todays active signin is not attached to same course on a future schedule date`() {
    val tomorrowCourse =
        scheduleOccurrence(
            "tomorrow-course",
            LocalDate.parse("2026-10-15"),
            "大学物理",
            "08:00",
            "08:45",
        )

    compose.setContent {
      MaterialTheme {
        Box(
            Modifier.width(390.dp).height(850.dp).background(MaterialTheme.colorScheme.background)
        ) {
          HomeScreen(
              upcomingClasses = listOf(tomorrowCourse),
              signinClasses =
                  listOf(SigninClassDto("signin-today", "大学物理", "08:00", "08:45", signStatus = 0)),
              now = LocalDateTime.parse("2026-10-14T07:55:00"),
              updatedAt = null,
              isLoading = false,
              isRefreshing = false,
              error = null,
              todayProgressOccurrences = emptyList(),
              scheduleHasLoaded = true,
              todoItems = emptyList(),
              todoLoading = false,
              todoLoadingSources = emptyList(),
              todoFailedSources = emptyList(),
              todoSourceErrors = emptyMap(),
              signingTodoId = null,
              scoreUpdateNotice = null as GradeScoreUpdateNotice?,
              onRetrySchedule = {},
              onRefresh = {},
              onOpenScoresClick = {},
              onDismissScoreNotice = {},
              onTodoClick = {},
              onSigninTodoClick = {},
              featureSections = emptyList(),
              openSectionId = null,
              onOpenSectionChange = {},
              onFeatureClick = {},
              onOpenSchedule = {},
              onOpenTodos = {},
              onFeatureOrderChange = { _, _ -> },
              onCourseClick = {},
          )
        }
      }
    }

    compose.onNodeWithTag("home-course-card-tomorrow-course").assertIsDisplayed()
    compose.onAllNodesWithTag("home-course-signin-signin-today").assertCountEquals(0)
  }

  @Test
  fun `home todo preview shows at most three cards without an inner scroll`() {
    for (count in 0..4) {
      val todos =
          (1..count).map { index ->
            HomeTodoItem(
                id = "todo-$index",
                source = HomeTodoSource.SPOC,
                title = "待办 $index",
                subtitle = "测试课程",
                statusLabel = "待提交",
                timeLabel = "截止时间未知",
                sortTime = null,
                action = HomeTodoAction.OpenSpocAssignment("assignment-$index"),
            )
          }
      setHomeTodos(todos)
      compose
          .onNodeWithTag("home-screen-scroll")
          .performScrollToNode(hasTestTag("home-todo-viewport"))
      when (count) {
        0 -> {
          val heightDp =
              with(compose.density) {
                compose
                    .onNodeWithTag("home-todo-viewport")
                    .fetchSemanticsNode()
                    .size
                    .height
                    .toDp()
                    .value
              }
          assertTrue(heightDp < 168f)
          compose.onNodeWithText("当前没有待办").assertIsDisplayed()
        }
        1 -> compose.onNodeWithTag("home-todo-viewport").assertHeightIsEqualTo(168.dp)
        2 -> compose.onNodeWithTag("home-todo-viewport").assertHeightIsEqualTo(348.dp)
        else -> compose.onNodeWithTag("home-todo-viewport").assertHeightIsEqualTo(528.dp)
      }
      (1..minOf(count, 3)).forEach {
        compose.onNodeWithTag("home-todo-card-todo-$it").assertIsDisplayed()
      }
      if (count > 3) {
        compose.onNodeWithTag("home-todo-viewport").performTouchInput {
          val start = center.copy(y = center.y + 80f)
          down(start)
          moveTo(start.copy(y = start.y - 500f))
          up()
        }
        compose.onAllNodesWithTag("home-todo-card-todo-4").assertCountEquals(0)
      }
      app.bucc.ui.saveRenderedEvidence(
          "home-todos-$count",
          compose.onNodeWithTag("home-screen-scroll").captureToImage(),
      )
    }

    compose.onNodeWithTag("home-screen-scroll").performScrollToNode(hasText("查看所有待办"))
    compose.onNodeWithText("查看所有待办").assertIsDisplayed()
  }

  @Test
  fun `pull refresh indicator stays hidden during ordinary scroll and appears while refreshing`() {
    val refreshing = mutableStateOf(false)
    var refreshCount = 0

    compose.setContent {
      MaterialTheme {
        Box(
            Modifier.width(390.dp).height(850.dp).background(MaterialTheme.colorScheme.background)
        ) {
          HomeScreen(
              upcomingClasses = emptyList(),
              signinClasses = emptyList(),
              now = LocalDateTime.parse("2026-10-14T08:00:00"),
              updatedAt = null,
              isLoading = false,
              isRefreshing = refreshing.value,
              error = null,
              todayProgressOccurrences = emptyList(),
              scheduleHasLoaded = false,
              todoItems = emptyList(),
              todoLoading = false,
              todoLoadingSources = emptyList(),
              todoFailedSources = emptyList(),
              todoSourceErrors = emptyMap(),
              signingTodoId = null,
              scoreUpdateNotice = null as GradeScoreUpdateNotice?,
              onRetrySchedule = {},
              onRefresh = {
                refreshCount++
                refreshing.value = true
              },
              onOpenScoresClick = {},
              onDismissScoreNotice = {},
              onTodoClick = {},
              onSigninTodoClick = {},
              featureSections = emptyList(),
              openSectionId = null,
              onOpenSectionChange = {},
              onFeatureClick = {},
              onOpenSchedule = {},
              onOpenTodos = {},
              onFeatureOrderChange = { _, _ -> },
              onCourseClick = {},
          )
        }
      }
    }

    compose.onAllNodesWithTag("home-pull-refresh-indicator").assertCountEquals(0)
    compose.onNodeWithTag("home-screen-scroll").performTouchInput {
      val start = center.copy(y = center.y + 150f)
      down(start)
      moveTo(start.copy(y = start.y - 400f), delayMillis = 400)
      up()
    }
    compose.onAllNodesWithTag("home-pull-refresh-indicator").assertCountEquals(0)
    compose.runOnIdle { assertEquals(0, refreshCount) }

    compose.onNodeWithTag("home-screen-scroll").performTouchInput {
      val start = center.copy(y = center.y - 250f)
      down(start)
      moveTo(start.copy(y = start.y + 500f), delayMillis = 500)
      up()
    }
    compose.waitUntil(3_000) { refreshCount == 1 }
    compose.onNodeWithTag("home-pull-refresh-indicator").assertIsDisplayed()
    saveRenderedEvidence(
        "home-pull-refresh-active",
        compose.onNodeWithTag("home-pull-refresh-indicator").captureToImage(),
    )
  }

  private fun setHomeTodos(todos: List<HomeTodoItem>) {
    compose.setContent {
      MaterialTheme {
        Box(
            Modifier.width(390.dp).height(850.dp).background(MaterialTheme.colorScheme.background)
        ) {
          HomeScreen(
              upcomingClasses = emptyList(),
              signinClasses = emptyList(),
              now = LocalDateTime.parse("2026-10-14T08:00:00"),
              updatedAt = null,
              isLoading = false,
              isRefreshing = false,
              error = null,
              todayProgressOccurrences = emptyList(),
              scheduleHasLoaded = false,
              todoItems = todos,
              todoLoading = false,
              todoLoadingSources = emptyList(),
              todoFailedSources = emptyList(),
              todoSourceErrors = emptyMap(),
              signingTodoId = null,
              scoreUpdateNotice = null as GradeScoreUpdateNotice?,
              onRetrySchedule = {},
              onRefresh = {},
              onOpenScoresClick = {},
              onDismissScoreNotice = {},
              onTodoClick = {},
              onSigninTodoClick = {},
              featureSections = emptyList(),
              openSectionId = null,
              onOpenSectionChange = {},
              onFeatureClick = {},
              onOpenSchedule = {},
              onOpenTodos = {},
              onFeatureOrderChange = { _, _ -> },
              onCourseClick = {},
          )
        }
      }
    }
  }

  private fun assertTodoViewportNaturalHeight() {
    val heightDp =
        with(compose.density) {
          compose.onNodeWithTag("home-todo-viewport").fetchSemanticsNode().size.height.toDp().value
        }
    assertTrue(
        heightDp < 168f,
        "Expected an empty todo viewport to use the empty/loading card height, got $heightDp dp",
    )
  }

  private fun scheduleOccurrence(
      id: String,
      date: LocalDate,
      name: String,
      start: String,
      end: String,
  ) =
      UpcomingClass(
          id = id,
          date = date,
          course =
              CourseClass(
                  courseCode = id,
                  courseName = name,
                  courseSerialNo = null,
                  credit = null,
                  beginTime = start,
                  endTime = end,
                  beginSection = null,
                  endSection = null,
                  placeName = "教室",
                  weeksAndTeachers = null,
                  teachingTarget = null,
                  color = null,
                  dayOfWeek = 3,
              ),
          start = LocalDateTime.parse("${date}T$start:00"),
          end = LocalDateTime.parse("${date}T$end:00"),
      )

  @Test
  fun `mobile home sizes empty todo message naturally and exposes all todos entry`() {
    var openedTodos = 0

    compose.setContent {
      MaterialTheme {
        Box(
            Modifier.width(390.dp).height(850.dp).background(MaterialTheme.colorScheme.background)
        ) {
          HomeScreen(
              upcomingClasses = emptyList(),
              signinClasses = emptyList(),
              now = LocalDateTime.parse("2026-10-14T08:00:00"),
              updatedAt = null,
              isLoading = false,
              isRefreshing = false,
              error = null,
              todayProgressOccurrences = emptyList(),
              scheduleHasLoaded = false,
              todoItems = emptyList(),
              todoLoading = false,
              todoLoadingSources = emptyList(),
              todoFailedSources = emptyList(),
              todoSourceErrors = emptyMap(),
              signingTodoId = null,
              scoreUpdateNotice = null as GradeScoreUpdateNotice?,
              onRetrySchedule = {},
              onRefresh = {},
              onOpenScoresClick = {},
              onDismissScoreNotice = {},
              onTodoClick = {},
              onSigninTodoClick = {},
              featureSections = emptyList(),
              openSectionId = null,
              onOpenSectionChange = {},
              onFeatureClick = {},
              onOpenSchedule = {},
              onOpenTodos = { openedTodos++ },
              onFeatureOrderChange = { _, _ -> },
              onCourseClick = {},
          )
        }
      }
    }

    compose
        .onNodeWithTag("home-screen-scroll")
        .performScrollToNode(hasTestTag("home-todo-viewport"))
    assertTodoViewportNaturalHeight()
    compose.onNodeWithText("当前没有待办").assertIsDisplayed()

    compose.onNodeWithTag("home-screen-scroll").performScrollToNode(hasText("查看所有待办"))
    compose.onNodeWithText("查看所有待办").assertIsDisplayed().performClick()
    assertEquals(1, openedTodos)
  }

  @Test
  fun `mobile home sizes loading and source error states without empty three-card space`() {
    compose.setContent {
      MaterialTheme {
        Box(
            Modifier.width(390.dp).height(850.dp).background(MaterialTheme.colorScheme.background)
        ) {
          HomeScreen(
              upcomingClasses = emptyList(),
              signinClasses = emptyList(),
              now = LocalDateTime.parse("2026-10-14T08:00:00"),
              updatedAt = null,
              isLoading = false,
              isRefreshing = false,
              error = null,
              todayProgressOccurrences = emptyList(),
              scheduleHasLoaded = false,
              todoItems = emptyList(),
              todoLoading = true,
              todoLoadingSources = emptyList(),
              todoFailedSources = emptyList(),
              todoSourceErrors = emptyMap(),
              signingTodoId = null,
              scoreUpdateNotice = null as GradeScoreUpdateNotice?,
              onRetrySchedule = {},
              onRefresh = {},
              onOpenScoresClick = {},
              onDismissScoreNotice = {},
              onTodoClick = {},
              onSigninTodoClick = {},
              featureSections = emptyList(),
              openSectionId = null,
              onOpenSectionChange = {},
              onFeatureClick = {},
              onOpenSchedule = {},
              onOpenTodos = {},
              onFeatureOrderChange = { _, _ -> },
              onCourseClick = {},
          )
        }
      }
    }

    compose
        .onNodeWithTag("home-screen-scroll")
        .performScrollToNode(hasTestTag("home-todo-viewport"))
    assertTodoViewportNaturalHeight()
    compose.onNodeWithText("正在同步待办").assertIsDisplayed()

    compose.setContent {
      MaterialTheme {
        Box(
            Modifier.width(390.dp).height(850.dp).background(MaterialTheme.colorScheme.background)
        ) {
          HomeScreen(
              upcomingClasses = emptyList(),
              signinClasses = emptyList(),
              now = LocalDateTime.parse("2026-10-14T08:00:00"),
              updatedAt = null,
              isLoading = false,
              isRefreshing = false,
              error = null,
              todayProgressOccurrences = emptyList(),
              scheduleHasLoaded = false,
              todoItems = emptyList(),
              todoLoading = false,
              todoLoadingSources = emptyList(),
              todoFailedSources = listOf(HomeTodoSource.SPOC),
              todoSourceErrors = mapOf(HomeTodoSource.SPOC to "暂时不可用"),
              signingTodoId = null,
              scoreUpdateNotice = null as GradeScoreUpdateNotice?,
              onRetrySchedule = {},
              onRefresh = {},
              onOpenScoresClick = {},
              onDismissScoreNotice = {},
              onTodoClick = {},
              onSigninTodoClick = {},
              featureSections = emptyList(),
              openSectionId = null,
              onOpenSectionChange = {},
              onFeatureClick = {},
              onOpenSchedule = {},
              onOpenTodos = {},
              onFeatureOrderChange = { _, _ -> },
              onCourseClick = {},
          )
        }
      }
    }
    compose
        .onNodeWithTag("home-screen-scroll")
        .performScrollToNode(hasTestTag("home-todo-viewport"))
    assertTodoViewportNaturalHeight()
    compose.onNodeWithText("当前没有待办").assertIsDisplayed()
    compose.onNodeWithTag("home-screen-scroll").performScrollToNode(hasText("部分来源暂未更新：SPOC：暂时不可用"))
    compose.onNodeWithText("部分来源暂未更新：SPOC：暂时不可用").assertIsDisplayed()
  }
}
