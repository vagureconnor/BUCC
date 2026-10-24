package app.bucc.ui.screens.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.bucc.api.plane.PaperPlaneBackend
import app.bucc.api.plane.PaperPlaneCourseSession
import app.bucc.api.plane.PaperPlaneFeedItem
import app.bucc.api.plane.PaperPlaneFeedResponse
import app.bucc.api.plane.PaperPlaneOneClickSummary
import app.bucc.api.plane.PaperPlanePointInfo
import app.bucc.api.plane.PaperPlanePointTask
import app.bucc.api.plane.PaperPlanePointTasksResponse
import app.bucc.api.plane.PaperPlaneThrowPreparation
import app.bucc.api.plane.PaperPlaneThrowProgress
import app.bucc.api.plane.PaperPlaneThrowSummary
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class PaperPlaneFlowUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `screen shows only numeric personal points and one click control`() {
    val backend =
        FakePaperPlaneBackend(
            initialPoints =
                PaperPlanePointTasksResponse(
                    info = PaperPlanePointInfo(jf = " 42.5 "),
                    list =
                        listOf(
                            PaperPlanePointTask(gzmc = "投掷“课堂纸飞机”", num = "3"),
                            PaperPlanePointTask(gzmc = "浏览“课堂纸飞机”", num = "4"),
                        ),
                ),
        )
    setScreen(backend)

    compose.onNodeWithText("42.5").assertIsDisplayed()
    compose.onNodeWithText("积分").assertIsDisplayed()
    compose.onNodeWithContentDescription("积分").assertIsDisplayed()
    compose.onNodeWithText("今日投掷纸飞机次数：3").assertIsDisplayed()
    compose.onNodeWithText("今日查看纸飞机次数：4").assertIsDisplayed()
    listOf("SPOC 返回记录", "记录 1", "准备课程", "确认最近已结束课程", "任课教师", "查看纸飞机", "加油", "回复“好！”").forEach {
      compose.onAllNodesWithText(it).assertCountEquals(0)
    }
    assertEquals(1, backend.getTasksCalls)
    assertEquals(0, backend.courseCalls)
    assertEquals(0, backend.feedCalls)
  }

  @Test
  fun `missing paper plane task counts show dashes and a concise failure`() {
    val backend =
        FakePaperPlaneBackend(
            initialPoints =
                PaperPlanePointTasksResponse(
                    info = PaperPlanePointInfo(jf = "12"),
                    list = listOf(PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "bad")),
                ),
        )
    setScreen(backend)

    compose.onNodeWithText("今日投掷纸飞机次数：—").assertIsDisplayed()
    compose.onNodeWithText("今日查看纸飞机次数：—").assertIsDisplayed()
    compose.onNodeWithText("今日次数加载失败：纸飞机积分任务次数格式无效").assertIsDisplayed()
  }

  @Test
  fun `one click invokes backend once disables repeats and updates points`() {
    val completion = CompletableDeferred<Result<PaperPlaneOneClickSummary>>()
    val backend =
        FakePaperPlaneBackend(
            initialPoints = PaperPlanePointTasksResponse(info = PaperPlanePointInfo(jf = "8")),
            refreshedPoints =
                PaperPlanePointTasksResponse(
                    info = PaperPlanePointInfo(jf = "13"),
                    list =
                        listOf(
                            PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"),
                            PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "5"),
                        ),
                ),
            complete = { completion.await() },
        )
    setScreen(backend)

    compose.onNodeWithText("一键投掷").performClick()
    compose.onNodeWithText("一键投掷").assertIsNotEnabled()
    compose.onNodeWithText("处理中…").assertIsDisplayed()
    assertEquals(1, backend.completeCalls)
    assertEquals(0, backend.courseCalls)
    assertEquals(0, backend.feedCalls)

    completion.complete(
        Result.success(
            PaperPlaneOneClickSummary(
                sent = 5,
                browsed = 5,
                points = null,
                error = null,
            ),
        ),
    )
    compose.onNodeWithText("已完成：今日投掷 5/5，查看 5/5；本次新查看 5 次").assertIsDisplayed()
    compose.onNodeWithText("13").assertIsDisplayed()
    compose.onNodeWithText("今日投掷纸飞机次数：5").assertIsDisplayed()
    compose.onNodeWithText("今日查看纸飞机次数：5").assertIsDisplayed()
    compose.onNodeWithText("一键投掷").assertIsDisplayed()
    assertEquals(1, backend.completeCalls)
    assertEquals(2, backend.getTasksCalls)
    assertEquals(0, backend.otherActionCalls)
  }

  @Test
  fun `one click completion uses official daily totals and labels this batch views separately`() {
    val officialTasks =
        PaperPlanePointTasksResponse(
            info = PaperPlanePointInfo(jf = "13"),
            list =
                listOf(
                    PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"),
                    PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "5"),
                ),
        )
    val backend =
        FakePaperPlaneBackend(
            initialPoints = officialTasks,
            refreshedPoints = officialTasks,
            complete = {
              Result.success(
                  PaperPlaneOneClickSummary(
                      sent = 5,
                      browsed = 0,
                      points = officialTasks,
                      error = null,
                  ),
              )
            },
        )
    setScreen(backend)

    compose.onNodeWithText("一键投掷").performClick()

    compose.onNodeWithText("已完成：今日投掷 5/5，查看 5/5；本次新查看 0 次").assertIsDisplayed()
    compose.onAllNodesWithText("已完成：投掷五次，浏览 0 次").assertCountEquals(0)
    compose.onNodeWithText("今日投掷纸飞机次数：5").assertIsDisplayed()
    compose.onNodeWithText("今日查看纸飞机次数：5").assertIsDisplayed()
    assertEquals(1, backend.completeCalls)
  }

  @Test
  fun `partial error is shown with completed count`() {
    val backend =
        FakePaperPlaneBackend(
            initialPoints =
                PaperPlanePointTasksResponse(
                    info = PaperPlanePointInfo(jf = "8"),
                    list =
                        listOf(
                            PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "1"),
                            PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "2"),
                        ),
                ),
            refreshedPoints =
                PaperPlanePointTasksResponse(
                    info = PaperPlanePointInfo(jf = "9"),
                    list =
                        listOf(
                            PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "3"),
                            PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "4"),
                        ),
                ),
            complete = {
              Result.success(
                  PaperPlaneOneClickSummary(
                      sent = 2,
                      browsed = 3,
                      points = null,
                      error = "课程投掷失败",
                  ),
              )
            },
        )
    setScreen(backend)

    compose.onNodeWithText("一键投掷").performClick()
    compose.onNodeWithText("失败：课程投掷失败（投掷 2/5 次）").assertIsDisplayed()
    compose.onNodeWithText("一键投掷").assertIsDisplayed()
    assertEquals(1, backend.completeCalls)
    assertEquals(2, backend.getTasksCalls)
    compose.onNodeWithText("今日投掷纸飞机次数：3").assertIsDisplayed()
    compose.onNodeWithText("今日查看纸飞机次数：4").assertIsDisplayed()
  }

  @Test
  fun `backend failure shows a short error result`() {
    val backend =
        FakePaperPlaneBackend(
            complete = { Result.failure(IllegalStateException("服务暂不可用")) },
        )
    setScreen(backend)

    compose.onNodeWithText("一键投掷").performClick()
    compose.onNodeWithText("失败：服务暂不可用").assertIsDisplayed()
    assertEquals(1, backend.completeCalls)
    assertEquals(2, backend.getTasksCalls)
  }

  private fun setScreen(backend: FakePaperPlaneBackend) {
    compose.setContent {
      MaterialTheme { Box(Modifier.width(390.dp).height(840.dp)) { PaperPlaneScreen(backend) } }
    }
  }

  private class FakePaperPlaneBackend(
      private val initialPoints: PaperPlanePointTasksResponse = PaperPlanePointTasksResponse(),
      private val refreshedPoints: PaperPlanePointTasksResponse? = null,
      private val complete: suspend () -> Result<PaperPlaneOneClickSummary> = {
        Result.success(
            PaperPlaneOneClickSummary(sent = 5, browsed = 5, points = null, error = null)
        )
      },
  ) : PaperPlaneBackend {
    var getTasksCalls = 0
    var completeCalls = 0
    var courseCalls = 0
    var feedCalls = 0
    var otherActionCalls = 0

    override suspend fun getTasks(): Result<PaperPlanePointTasksResponse> {
      getTasksCalls++
      return Result.success(
          if (getTasksCalls > 1) refreshedPoints ?: initialPoints else initialPoints
      )
    }

    override suspend fun getCurrentTermCourses(): Result<List<PaperPlaneCourseSession>> {
      courseCalls++
      return Result.success(emptyList())
    }

    override suspend fun getFeed(
        course: PaperPlaneCourseSession,
        pageSize: Int,
        pageNum: Int,
    ): Result<PaperPlaneFeedResponse> {
      feedCalls++
      return Result.success(PaperPlaneFeedResponse(emptyList()))
    }

    override suspend fun markViewed(plane: PaperPlaneFeedItem): Result<Boolean> {
      otherActionCalls++
      return Result.success(false)
    }

    override suspend fun cheer(plane: PaperPlaneFeedItem): Result<Unit> {
      otherActionCalls++
      return Result.success(Unit)
    }

    override suspend fun reply(plane: PaperPlaneFeedItem): Result<Unit> {
      otherActionCalls++
      return Result.success(Unit)
    }

    override suspend fun prepareThrow(): Result<PaperPlaneThrowPreparation> {
      otherActionCalls++
      return Result.failure(UnsupportedOperationException())
    }

    override suspend fun throwFiveForLatestEndedClass(
        instructorId: String?,
        expectedClassId: String?,
        onProgress: (PaperPlaneThrowProgress) -> Unit,
    ): Result<PaperPlaneThrowSummary> {
      otherActionCalls++
      return Result.failure(UnsupportedOperationException())
    }

    override suspend fun completeOneClick(): Result<PaperPlaneOneClickSummary> {
      completeCalls++
      return complete()
    }
  }
}
