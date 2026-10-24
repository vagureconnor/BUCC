package app.bucc.api.plane

import app.bucc.api.local.LocalSpocAuthenticationException
import app.bucc.api.local.LocalSpocException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class PaperPlaneApiTest {
  @Test
  fun `SPOC expiry requests main-session validation without replaying an operation`() = runTest {
    val remote =
        FakePaperPlaneRemote().apply {
          tasksFailure = LocalSpocAuthenticationException("synthetic expired")
        }
    val event =
        async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
          kotlinx.coroutines.withTimeout(1000) {
            app.bucc.api.auth.LocalSessionExpiryEvents.events.first()
          }
        }
    val backend = LocalPaperPlaneBackend(studentIdProvider = { "synthetic" }, remote = remote)
    assertTrue(backend.getTasks().isFailure)
    event.await()
    assertEquals(1, remote.taskReads)
  }

  @Test
  fun `one click selects first official teacher throws five then records five views`() = runTest {
    val remote =
        FakePaperPlaneRemote().apply {
          dailyTasks =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷“课堂纸飞机”", num = "0"),
                          PaperPlanePointTask(gzmc = "浏览“课堂纸飞机”", num = "0"),
                      ),
              )
          feedItems = (1..6).map { PaperPlaneFeedItem(id = "mock-view-$it") }
        }
    val throwing =
        FakeThrowRemote().apply {
          teacherRows =
              listOf(PaperPlaneInstructor("first", "1"), PaperPlaneInstructor("second", "1"))
          feedItems = remote.feedItems
          pointsResponse =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"),
                          PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "0"),
                      ),
              )
          onThrow = { remote.throwWrites++ }
        }
    val backend =
        LocalPaperPlaneBackend(
            { "mock-student" },
            remote = remote,
            throwRemote = throwing,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )
    val result = backend.completeOneClick().getOrThrow()
    assertEquals(5, result.sent)
    assertEquals(5, result.browsed)
    assertEquals(null, result.error)
    assertEquals(List(5) { "first" }, throwing.throwRecipients)
    assertEquals(5, remote.viewWrites)
    assertEquals(1, throwing.currentTermScheduleCalls)
    assertEquals(1, throwing.instructorCalls)
    assertEquals(1, throwing.evaluationOptionsCalls)
    assertEquals(1, throwing.pointsCalls)
    assertEquals(1, throwing.classFeedCalls)
    assertEquals(1, remote.taskReads)
    assertEquals(0, remote.feedCalls)
    // A subsequent explicit click still sends a five-throw batch; completed views are not replayed.
    throwing.pointsResponse =
        throwing.pointsResponse.copy(
            list =
                throwing.pointsResponse.list.map {
                  if (normalizedPaperPlaneTaskName(it.gzmc) == "浏览课堂纸飞机") it.copy(num = "5") else it
                }
        )
    assertEquals(5, backend.completeOneClick().getOrThrow().sent)
    assertEquals(10, remote.throwWrites)
    assertEquals(5, remote.viewWrites)
  }

  @Test
  fun `one click stops after a failed view and never replays that partial batch`() = runTest {
    val remote =
        FakePaperPlaneRemote().apply {
          dailyTasks =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷“课堂纸飞机”", num = "0"),
                          PaperPlanePointTask(gzmc = "浏览“课堂纸飞机”", num = "0"),
                      ),
              )
          failViewOnAttempt = 2
        }
    val throwing =
        FakeThrowRemote().apply {
          feedItems = (1..6).map { PaperPlaneFeedItem(id = "partial-view-$it") }
          pointsResponse =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"),
                          PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "0"),
                      ),
              )
        }
    val backend =
        LocalPaperPlaneBackend(
            { "mock-student" },
            remote = remote,
            throwRemote = throwing,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )

    val failed = backend.completeOneClick().getOrThrow()
    assertEquals(1, failed.browsed)
    assertEquals("查看记录失败", failed.error)
    assertEquals(1, remote.viewWrites)
    assertEquals(2, remote.viewAttempts)
    assertEquals(1, remote.taskReads)

    assertTrue(backend.completeOneClick().isFailure)
    assertEquals(5, throwing.throwMessages.size)
    assertEquals(2, remote.viewAttempts)
    assertEquals(1, remote.taskReads)
  }

  @Test
  fun `one click only browses the remaining official view count`() = runTest {
    val remote =
        FakePaperPlaneRemote().apply {
          startingViews = 4
          dailyTasks =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷“课堂纸飞机”", num = "0"),
                          PaperPlanePointTask(gzmc = "浏览“课堂纸飞机”", num = "4"),
                      ),
              )
        }
    val throwing =
        FakeThrowRemote().apply {
          feedItems = (1..6).map { PaperPlaneFeedItem(id = "remaining-view-$it") }
          pointsResponse =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"),
                          PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "4"),
                      ),
              )
        }
    val backend =
        LocalPaperPlaneBackend(
            { "mock-student" },
            remote = remote,
            throwRemote = throwing,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )

    val result = backend.completeOneClick().getOrThrow()

    assertEquals(1, result.browsed)
    assertEquals(1, remote.viewWrites)
    assertEquals(1, remote.taskReads)
    assertEquals(0, remote.feedCalls)
  }

  @Test
  fun `one click exposes zero new views separately from completed official daily views`() =
      runTest {
        val remote =
            FakePaperPlaneRemote().apply {
              startingViews = 5
              dailyTasks =
                  PaperPlanePointTasksResponse(
                      info = PaperPlanePointInfo("10"),
                      list =
                          listOf(
                              PaperPlanePointTask(gzmc = "投掷“课堂纸飞机”", num = "0"),
                              PaperPlanePointTask(gzmc = "浏览“课堂纸飞机”", num = "5"),
                          ),
                  )
            }
        val throwing =
            FakeThrowRemote().apply {
              pointsResponse =
                  PaperPlanePointTasksResponse(
                      info = PaperPlanePointInfo("10"),
                      list =
                          listOf(
                              PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"),
                              PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "5"),
                          ),
                  )
              onThrow = { remote.throwWrites++ }
            }
        val backend =
            LocalPaperPlaneBackend(
                { "mock-student" },
                remote = remote,
                throwRemote = throwing,
                nowMillis = ::testNowMillis,
                timeZone = { TimeZone.UTC },
            )

        val result = backend.completeOneClick().getOrThrow()

        assertEquals(5, result.sent)
        assertEquals(0, result.browsed)
        assertEquals(
            "5",
            result.points?.list?.single { normalizedPaperPlaneTaskName(it.gzmc) == "浏览课堂纸飞机" }?.num,
        )
        assertEquals(null, result.error)
        assertEquals(0, remote.viewWrites)
      }

  @Test
  fun `one click fetches feed when the throw batch feed refresh failed`() = runTest {
    val remote =
        FakePaperPlaneRemote().apply {
          dailyTasks =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷“课堂纸飞机”", num = "0"),
                          PaperPlanePointTask(gzmc = "浏览“课堂纸飞机”", num = "0"),
                      ),
              )
          feedItems = (1..6).map { PaperPlaneFeedItem(id = "feed-after-refresh-failure-$it") }
        }
    val throwing =
        FakeThrowRemote().apply {
          feedItems = remote.feedItems
          classFeedFailure = LocalSpocException("feed refresh failed")
          onThrow = { remote.throwWrites++ }
          pointsResponse =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"),
                          PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "0"),
                      ),
              )
        }
    val backend =
        LocalPaperPlaneBackend(
            { "mock-student" },
            remote = remote,
            throwRemote = throwing,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )

    val result = backend.completeOneClick().getOrThrow()

    assertEquals(5, result.sent)
    assertEquals(5, result.browsed)
    assertEquals(null, result.error)
    assertEquals(1, remote.feedCalls)
    assertEquals(5, remote.viewWrites)
  }

  @Test
  fun `one click checks page two after an already viewed full first page`() = runTest {
    val firstPage = (1..20).map { PaperPlaneFeedItem(id = "viewed-page-one-$it") }
    val secondPage = (1..5).map { PaperPlaneFeedItem(id = "unviewed-page-two-$it") }
    val remote =
        FakePaperPlaneRemote().apply {
          dailyTasks =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷“课堂纸飞机”", num = "0"),
                          PaperPlanePointTask(gzmc = "浏览“课堂纸飞机”", num = "0"),
                      ),
              )
          feedPages[2] = secondPage
        }
    firstPage.forEach { remote.markViewed(it.id!!, "mock-student") }
    val throwing =
        FakeThrowRemote().apply {
          feedItems = firstPage
          pointsResponse =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"),
                          PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "0"),
                      ),
              )
        }
    val backend =
        LocalPaperPlaneBackend(
            { "mock-student" },
            remote = remote,
            throwRemote = throwing,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )

    val result = backend.completeOneClick().getOrThrow()

    assertEquals(5, result.browsed)
    assertEquals(25, remote.viewWrites)
    assertEquals(listOf(20 to 2), remote.feedPageRequests)
    assertEquals(1, throwing.currentTermScheduleCalls)
    assertEquals(1, remote.taskReads)
  }

  @Test
  fun `one click reports a safe status refresh error after successful views`() = runTest {
    val remote =
        FakePaperPlaneRemote().apply {
          dailyTasks =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷“课堂纸飞机”", num = "0"),
                          PaperPlanePointTask(gzmc = "浏览“课堂纸飞机”", num = "0"),
                      ),
              )
          tasksFailure = LocalSpocException("SPOC 响应解析失败")
        }
    val throwing =
        FakeThrowRemote().apply {
          feedItems = (1..6).map { PaperPlaneFeedItem(id = "refresh-view-$it") }
          pointsResponse =
              PaperPlanePointTasksResponse(
                  info = PaperPlanePointInfo("10"),
                  list =
                      listOf(
                          PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"),
                          PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "0"),
                      ),
              )
        }
    val backend =
        LocalPaperPlaneBackend(
            { "mock-student" },
            remote = remote,
            throwRemote = throwing,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )

    val result = backend.completeOneClick().getOrThrow()

    assertEquals(5, result.browsed)
    assertEquals("查看状态查询失败", result.error)
    assertEquals(5, remote.viewWrites)
    assertEquals(1, remote.taskReads)
  }

  @Test
  fun `missing daily counts do not block the previously supported five throw flow`() = runTest {
    val throwing = FakeThrowRemote()
    val remote = FakePaperPlaneRemote()
    val backend =
        LocalPaperPlaneBackend(
            { "mock-student" },
            remote = remote,
            throwRemote = throwing,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )
    val summary = backend.completeOneClick().getOrThrow()
    assertEquals(5, summary.sent)
    assertEquals("查看状态查询失败", summary.error)
    assertEquals(0, remote.viewWrites)
  }

  @Test
  fun `keyword throws do not substitute a fixed free text message`() {
    val message =
        PaperPlaneThrowMessage(
            id = "mock-plane",
            xsxh = "mock-student",
            kcid = "mock-course",
            kcmc = "模拟课程",
            hdktid = "mock-class",
            jsr = "mock-teacher",
            plgjc = "mock-keyword",
        )
    assertEquals("", message.nr)
    assertEquals("1", message.sfnm)
    assertEquals("mock-keyword", message.plgjc)
  }

  @Test
  fun `latest ended class with missing identifiers does not fall back to an older class`() {
    val selected =
        selectLatestEligibleEndedClass(
            "current",
            PaperPlaneSendConfig("0", "10", "2"),
            listOf(
                course("older", "2026-10-13T08:00:00", "2026-10-13T09:00:00"),
                course("latest", "2026-10-13T10:00:00", "2026-10-13T11:00:00").copy(kcid = null),
            ),
            testNowMillis(),
            TimeZone.UTC,
        )
    assertEquals(null, selected)
  }

  @Test
  fun `unrecognized permission and invalid course time stop selection`() {
    val sessions = listOf(course("invalid", "2026-10-13T11:00:00", "2026-10-13T09:00:00"))
    assertEquals(
        null,
        selectLatestEligibleEndedClass(
            "current",
            PaperPlaneSendConfig("0", "10", "2"),
            sessions,
            testNowMillis(),
            TimeZone.UTC,
        ),
    )
    assertEquals(
        null,
        selectLatestEligibleEndedClass(
            "current",
            PaperPlaneSendConfig("unknown", "10", "2"),
            listOf(course("valid", "2026-10-13T08:00:00", "2026-10-13T09:00:00")),
            testNowMillis(),
            TimeZone.UTC,
        ),
    )
  }

  @Test
  fun `point totals and task fields accept JSON numbers or strings`() {
    val json = Json { ignoreUnknownKeys = true }

    val numeric = json.decodeFromString<PaperPlanePointInfo>("""{"jf":42}""")
    val string = json.decodeFromString<PaperPlanePointInfo>("""{"jf":"42"}""")
    val feed =
        json.decodeFromString<PaperPlaneFeedItem>("""{"id":123,"sfdz":"0","dzsl":2,"sfyjl":"1"}""")
    val week =
        json.decodeFromString<PaperPlaneWeek>(
            """{"zc":1,"zksrq":"2026-10-12","zjsrq":"2026-10-18"}"""
        )

    assertEquals("42", numeric.jf)
    assertEquals("42", string.jf)
    assertEquals("123", feed.id)
    assertEquals(0, feed.liked)
    assertEquals(2, feed.likes)
    assertEquals(1, feed.viewed)
    assertEquals("1", week.zc)
  }

  @Test
  fun `paper plane reads expose only a fixed safe failure message`() = runTest {
    val remote = FakePaperPlaneRemote().apply { tasksFailure = LocalSpocException("SPOC 响应解析失败") }
    val backend = LocalPaperPlaneBackend(studentIdProvider = { "student-1" }, remote = remote)

    val error = backend.getTasks().exceptionOrNull()

    assertEquals("无法加载纸飞机积分任务", error?.message)
  }

  @Test
  fun `paper plane authentication failures keep causes behind a fixed login message`() = runTest {
    val remote =
        FakePaperPlaneRemote().apply {
          tasksFailure = LocalSpocAuthenticationException("未能在 SPOC 登录跳转链中获取 token")
        }
    val backend = LocalPaperPlaneBackend(studentIdProvider = { "student-1" }, remote = remote)

    val error = backend.getTasks().exceptionOrNull()

    assertEquals("SPOC 登录状态异常，请重新登录后重试", error?.message)
  }

  @Test
  fun `paper plane authentication details redact credential values`() = runTest {
    val remote =
        FakePaperPlaneRemote().apply {
          tasksFailure = LocalSpocAuthenticationException("SPOC token=temporary-secret")
        }
    val backend = LocalPaperPlaneBackend(studentIdProvider = { "student-1" }, remote = remote)

    val error = backend.getTasks().exceptionOrNull()?.message.orEmpty()

    assertEquals("SPOC 登录状态异常，请重新登录后重试", error)
    assertFalse(error.contains("temporary-secret"))
  }

  @Test
  fun `current term feed choices require valid course and classroom identifiers and are deduplicated`() =
      runTest {
        val remote =
            FakeThrowRemote().apply {
              sessions =
                  listOf(
                      course("class-1", "2026-10-13T08:00:00", "2026-10-13T09:00:00"),
                      course("class-1", "2026-10-13T10:00:00", "2026-10-13T11:00:00"),
                      PaperPlaneCourseSession(kcid = null, hdktid = "missing-course"),
                      PaperPlaneCourseSession(kcid = "course-2", hdktid = null),
                  )
            }
        val backend =
            LocalPaperPlaneBackend(
                studentIdProvider = { "student-1" },
                throwRemote = remote,
            )

        val classes = backend.getCurrentTermCourses().getOrThrow()

        assertEquals(listOf("class-1"), classes.map { it.hdktid })
      }

  @Test
  fun `unknown official send rules stop the throw flow before any write`() = runTest {
    val remote = FakeThrowRemote().apply { sendConfig = null }
    val backend =
        LocalPaperPlaneBackend(
            studentIdProvider = { "student-1" },
            throwRemote = remote,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )

    val result = backend.throwFiveForLatestEndedClass()

    assertTrue(result.isFailure)
    assertTrue(remote.throwMessages.isEmpty())
    assertFalse(remote.feedLoaded)
    assertFalse(remote.pointsLoaded)
  }

  @Test
  fun `view history is written once per plane`() = runTest {
    val remote = FakePaperPlaneRemote()
    val backend = LocalPaperPlaneBackend(studentIdProvider = { "student-1" }, remote = remote)
    val item = PaperPlaneFeedItem(id = "plane-1")

    assertTrue(backend.markViewed(item).getOrThrow())
    assertFalse(backend.markViewed(item).getOrThrow())
    assertEquals(1, remote.viewWrites)
  }

  @Test
  fun `cheer is rejected for an already liked plane`() = runTest {
    val remote = FakePaperPlaneRemote()
    val backend = LocalPaperPlaneBackend(studentIdProvider = { "student-1" }, remote = remote)

    assertTrue(backend.cheer(PaperPlaneFeedItem(id = "plane-1", liked = 1)).isFailure)
    assertEquals(0, remote.cheers)
    assertTrue(
        backend.cheer(PaperPlaneFeedItem(id = "plane-2", xh = "plane-author", liked = 0)).isSuccess
    )
    assertTrue(
        backend.cheer(PaperPlaneFeedItem(id = "plane-2", xh = "plane-author", liked = 0)).isFailure
    )
    assertEquals(1, remote.cheers)
  }

  @Test
  fun `reply checks permission and always sends the fixed message`() = runTest {
    val remote = FakePaperPlaneRemote()
    val backend = LocalPaperPlaneBackend(studentIdProvider = { "student-1" }, remote = remote)
    remote.canReply = "0"
    assertTrue(backend.reply(PaperPlaneFeedItem(id = "plane-1")).isFailure)
    assertEquals(0, remote.replies)

    remote.canReply = null
    assertTrue(backend.reply(PaperPlaneFeedItem(id = "plane-unknown")).isFailure)
    remote.canReply = "unexpected"
    assertTrue(backend.reply(PaperPlaneFeedItem(id = "plane-unrecognized")).isFailure)
    assertEquals(0, remote.replies)

    remote.canReply = "1"
    assertTrue(backend.reply(PaperPlaneFeedItem(id = "plane-1", xh = "plane-author")).isSuccess)
    assertEquals(1, remote.replies)
    assertEquals("好！", remote.replyMessage)
    assertEquals("plane-author", remote.replyAuthor)
    assertTrue(backend.reply(PaperPlaneFeedItem(id = "plane-1")).isFailure)
    assertEquals(1, remote.replies)
  }

  @Test
  fun `throw flow sends exactly five in sequence and refreshes class feed and points`() = runTest {
    val remote = FakeThrowRemote()
    val backend =
        LocalPaperPlaneBackend(
            studentIdProvider = { "student-1" },
            throwRemote = remote,
            nowMillis = { testNowMillis() },
            timeZone = { TimeZone.UTC },
        )

    val summary = backend.throwFiveForLatestEndedClass().getOrThrow()

    assertEquals(PAPER_PLANE_THROW_COUNT, summary.attempted)
    assertEquals(PAPER_PLANE_THROW_COUNT, summary.sent)
    assertFalse(summary.stoppedOnFailure)
    assertEquals(listOf("teacher-1"), remote.throwRecipients.distinct())
    assertEquals(listOf("mock-keyword"), remote.throwMessages.distinct())
    assertEquals(listOf("feeling") + List(5) { "throw" }, remote.events)
    assertEquals(1, remote.maxConcurrentSends)
    assertTrue(remote.feedLoaded)
    assertTrue(remote.pointsLoaded)
  }

  @Test
  fun `throw flow stops on first failed send and still reloads class feed and points`() = runTest {
    val remote = FakeThrowRemote().apply { failOnAttempt = 3 }
    val backend =
        LocalPaperPlaneBackend(
            studentIdProvider = { "student-1" },
            throwRemote = remote,
            nowMillis = { testNowMillis() },
            timeZone = { TimeZone.UTC },
        )

    val summary = backend.throwFiveForLatestEndedClass().getOrThrow()

    assertEquals(3, summary.attempted)
    assertEquals(2, summary.sent)
    assertTrue(summary.stoppedOnFailure)
    assertTrue(!summary.sendError.isNullOrBlank())
    assertEquals(3, remote.throwMessages.size)
    assertTrue(remote.feedLoaded)
    assertTrue(remote.pointsLoaded)
    assertTrue(backend.throwFiveForLatestEndedClass().isFailure)
    assertEquals(3, remote.throwMessages.size)
  }

  @Test
  fun `throw flow requires teacher choice and verified keyword before any send`() = runTest {
    val remote =
        FakeThrowRemote().apply {
          teacherRows = listOf(PaperPlaneInstructor("a", "1"), PaperPlaneInstructor("b", "1"))
        }
    val backend =
        LocalPaperPlaneBackend(
            studentIdProvider = { "student-1" },
            throwRemote = remote,
            nowMillis = { testNowMillis() },
            timeZone = { TimeZone.UTC },
        )
    assertTrue(backend.throwFiveForLatestEndedClass().isFailure)
    assertEquals(0, remote.throwMessages.size)

    remote.teacherRows = listOf(PaperPlaneInstructor("teacher-1", "1"))
    remote.keywordAvailable = false
    assertTrue(backend.throwFiveForLatestEndedClass().isFailure)
    assertEquals(0, remote.throwMessages.size)
  }

  @Test
  fun `existing feeling is skipped and unknown feeling blocks all writes`() = runTest {
    val remote = FakeThrowRemote().apply { feelingRecorded = true }
    val backend =
        LocalPaperPlaneBackend(
            studentIdProvider = { "mock-student" },
            throwRemote = remote,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )
    assertEquals(5, backend.throwFiveForLatestEndedClass().getOrThrow().sent)
    assertEquals(List(5) { "throw" }, remote.events)

    val unknown = FakeThrowRemote().apply { feelingRecorded = null }
    val blocked =
        LocalPaperPlaneBackend(
            studentIdProvider = { "mock-student" },
            throwRemote = unknown,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )
    assertTrue(blocked.throwFiveForLatestEndedClass().isFailure)
    assertTrue(unknown.events.isEmpty())
  }

  @Test
  fun `selected teacher and prepared classroom are checked before five throws`() = runTest {
    val remote =
        FakeThrowRemote().apply {
          teacherRows = listOf(PaperPlaneInstructor("a", "1"), PaperPlaneInstructor("b", "1"))
        }
    val backend =
        LocalPaperPlaneBackend(
            studentIdProvider = { "mock-student" },
            throwRemote = remote,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )
    assertEquals(2, backend.prepareThrow().getOrThrow().instructors.size)
    assertTrue(backend.throwFiveForLatestEndedClass("b", "different-class").isFailure)
    assertTrue(remote.events.isEmpty())
    assertEquals(5, backend.throwFiveForLatestEndedClass("b", "recent").getOrThrow().sent)
    assertEquals(listOf("b"), remote.throwRecipients.distinct())
    assertTrue(backend.throwFiveForLatestEndedClass("b", "recent").isFailure)
    assertEquals(5, remote.throwMessages.size)
  }

  @Test
  fun `expired token stops batch without retry and progress shows accepted sends`() = runTest {
    val remote =
        FakeThrowRemote().apply {
          failOnAttempt = 2
          sendFailure = LocalSpocAuthenticationException("会话已失效")
        }
    val backend =
        LocalPaperPlaneBackend(
            studentIdProvider = { "mock-student" },
            throwRemote = remote,
            nowMillis = ::testNowMillis,
            timeZone = { TimeZone.UTC },
        )
    val progress = mutableListOf<PaperPlaneThrowProgress>()
    val result = backend.throwFiveForLatestEndedClass(onProgress = { progress += it }).getOrThrow()
    assertEquals(1, result.sent)
    assertEquals(2, result.attempted)
    assertTrue(result.stoppedOnFailure)
    assertEquals("SPOC 登录状态异常，请重新登录后重试", result.sendError)
    assertEquals(1, progress.maxOf { it.sent })
    assertTrue(backend.throwFiveForLatestEndedClass().isFailure)
    assertEquals(2, remote.throwMessages.size)
  }

  @Test
  fun `missing end future course and tied latest courses cannot be guessed`() {
    val config = PaperPlaneSendConfig("0", "10", "2")
    assertEquals(
        null,
        selectLatestEligibleEndedClass(
            "current",
            config,
            listOf(course("future", "2026-10-13T13:00:00", "2026-10-13T14:00:00")),
            testNowMillis(),
            TimeZone.UTC,
        ),
    )
    assertEquals(
        null,
        selectLatestEligibleEndedClass(
            "current",
            config,
            listOf(
                course("unknown", "2026-10-13T08:00:00", "2026-10-13T09:00:00").copy(skjssj = null)
            ),
            testNowMillis(),
            TimeZone.UTC,
        ),
    )
    assertEquals(
        null,
        selectLatestEligibleEndedClass(
            "current",
            config,
            listOf(
                course("one", "2026-10-13T08:00:00", "2026-10-13T09:00:00"),
                course("two", "2026-10-13T08:00:00", "2026-10-13T09:00:00"),
            ),
            testNowMillis(),
            TimeZone.UTC,
        ),
    )
  }

  @Test
  fun `latest ended course selection respects the official current-term timing window`() {
    val now = testNowMillis()
    val selected =
        selectLatestEligibleEndedClass(
            currentTermCode = "2026-2027-1",
            config = PaperPlaneSendConfig(yxskfskg = "0", fzsrk = "10", tssrk = "2"),
            sessions =
                listOf(
                    course("older", "2026-10-12T10:00:00", "2026-10-12T11:00:00"),
                    course("latest", "2026-10-13T08:00:00", "2026-10-13T11:00:00"),
                    course("not-ended", "2026-10-13T11:00:00", "2026-10-13T13:00:00"),
                ),
            nowMillis = now,
            timeZone = TimeZone.UTC,
        )
    assertEquals("latest", selected?.hdktid)

    assertEquals(
        null,
        selectLatestEligibleEndedClass(
            currentTermCode = "2026-2027-1",
            config = PaperPlaneSendConfig(yxskfskg = "0", fzsrk = "10", tssrk = "0"),
            sessions = listOf(course("expired", "2026-10-10T08:00:00", "2026-10-10T09:00:00")),
            nowMillis = now,
            timeZone = TimeZone.UTC,
        ),
    )
  }

  private class FakePaperPlaneRemote : PaperPlaneRemote {
    var dailyTasks: PaperPlanePointTasksResponse = PaperPlanePointTasksResponse()
    var throwWrites = 0
    var feedItems: List<PaperPlaneFeedItem> = emptyList()
    var canReply: String? = "0"
    var viewWrites = 0
    var cheers = 0
    var replies = 0
    var replyMessage: String? = null
    var replyAuthor: String? = null
    var tasksFailure: Exception? = null
    var taskReads = 0
    var feedCalls = 0
    val feedPages = mutableMapOf<Int, List<PaperPlaneFeedItem>>()
    val feedPageRequests = mutableListOf<Pair<Int, Int>>()
    var viewAttempts = 0
    var failViewOnAttempt: Int? = null
    var startingViews = 0
    private val viewed = mutableSetOf<String>()

    override suspend fun tasks(studentId: String): PaperPlanePointTasksResponse {
      taskReads++
      tasksFailure?.let { throw it }
      return dailyTasks.copy(
          list =
              dailyTasks.list.map {
                when (it.gzmc) {
                  "浏览课堂纸飞机",
                  "浏览“课堂纸飞机”" -> it.copy(num = (startingViews + viewWrites).toString())
                  "投掷课堂纸飞机",
                  "投掷“课堂纸飞机”" -> it.copy(num = throwWrites.toString())
                  else -> it
                }
              }
      )
    }

    override suspend fun feed(
        course: PaperPlaneCourseSession,
        studentId: String,
        pageSize: Int,
        pageNum: Int,
    ): PaperPlaneFeedResponse {
      feedCalls++
      feedPageRequests += pageSize to pageNum
      return PaperPlaneFeedResponse(feedPages[pageNum] ?: feedItems)
    }

    override suspend fun hasViewed(planeId: String, studentId: String) = planeId in viewed

    override suspend fun markViewed(planeId: String, studentId: String) {
      viewAttempts++
      if (viewAttempts == failViewOnAttempt) error("mock view failure")
      viewWrites++
      viewed += planeId
    }

    override suspend fun cheer(planeId: String, studentId: String, planeAuthorId: String) {
      cheers++
    }

    override suspend fun replyPermission(planeId: String) =
        PaperPlaneReplyPermission(canReply = canReply)

    override suspend fun reply(
        planeId: String,
        studentId: String,
        planeAuthorId: String,
        replyId: String,
        message: String,
    ) {
      replies++
      replyMessage = message
      replyAuthor = planeAuthorId
    }
  }

  private fun course(id: String, start: String, end: String) =
      PaperPlaneCourseSession(
          kcid = "course-1",
          kcmc = "模拟课程",
          hdktid = id,
          skkssj = start.replace('T', ' '),
          skjssj = end.replace('T', ' '),
      )

  private fun testNowMillis() =
      LocalDateTime.parse("2026-10-13T12:00:00").toInstant(TimeZone.UTC).toEpochMilliseconds()

  private inner class FakeThrowRemote : PaperPlaneThrowRemote {
    var sessions = listOf(course("recent", "2026-10-13T08:00:00", "2026-10-13T11:00:00"))
    var sendConfig: PaperPlaneSendConfig? =
        PaperPlaneSendConfig(yxskfskg = "0", fzsrk = "10", tssrk = "2")
    var currentTermScheduleCalls = 0
    var instructorCalls = 0
    var evaluationOptionsCalls = 0
    var pointsCalls = 0
    var classFeedCalls = 0
    var feedItems: List<PaperPlaneFeedItem> = emptyList()
    var pointsResponse: PaperPlanePointTasksResponse =
        PaperPlanePointTasksResponse(info = PaperPlanePointInfo("10"))
    var pointsFailure: Exception? = null

    override suspend fun currentTermSchedule(): PaperPlaneCurrentTermSchedule {
      currentTermScheduleCalls++
      return PaperPlaneCurrentTermSchedule(
          currentTermCode = "2026-2027-1",
          sendConfig = sendConfig,
          sessions = sessions,
      )
    }

    var teacherRows = listOf(PaperPlaneInstructor("teacher-1", "1"))
    var keywordAvailable = true
    var feelingRecorded: Boolean? = false
    var onThrow: () -> Unit = {}
    val events = mutableListOf<String>()
    var failOnAttempt: Int? = null
    var sendFailure: Exception = IllegalStateException("mock send failure")
    val throwRecipients = mutableListOf<String>()
    val throwMessages = mutableListOf<String>()
    var feedLoaded = false
    var pointsLoaded = false
    var classFeedFailure: Exception? = null
    var activeSends = 0
    var maxConcurrentSends = 0

    override suspend fun instructors(course: PaperPlaneCourseSession): List<PaperPlaneInstructor> {
      instructorCalls++
      return teacherRows
    }

    override suspend fun evaluationOptions(
        course: PaperPlaneCourseSession,
        studentId: String,
    ): PaperPlaneEvaluationOptions {
      evaluationOptionsCalls++
      return PaperPlaneEvaluationOptions(
          feelingRecorded,
          listOf(
              PaperPlaneFeeling(
                  "mock-feeling",
                  "收获满满",
                  "mock-icon",
                  kotlinx.serialization.json.JsonPrimitive(5),
              )
          ),
          if (keywordAvailable) listOf(PaperPlaneKeyword("mock-keyword", "案例教学")) else emptyList(),
      )
    }

    override suspend fun recordFeeling(
        course: PaperPlaneCourseSession,
        feeling: PaperPlaneFeeling,
        studentId: String,
    ) {
      events += "feeling"
      feelingRecorded = true
    }

    override suspend fun throwOne(
        course: PaperPlaneCourseSession,
        instructorId: String,
        studentId: String,
        keywordId: String,
    ) {
      events += "throw"
      activeSends++
      maxConcurrentSends = maxOf(maxConcurrentSends, activeSends)
      val attempt = throwMessages.size + 1
      throwRecipients += instructorId
      throwMessages += keywordId
      activeSends--
      if (attempt == failOnAttempt) throw sendFailure
      onThrow()
    }

    override suspend fun classFeed(
        course: PaperPlaneCourseSession,
        studentId: String,
    ): PaperPlaneFeedResponse {
      feedLoaded = true
      classFeedCalls++
      classFeedFailure?.let { throw it }
      return PaperPlaneFeedResponse(feedItems)
    }

    override suspend fun points(studentId: String): PaperPlanePointTasksResponse {
      pointsLoaded = true
      pointsCalls++
      pointsFailure?.let { throw it }
      return pointsResponse
    }
  }
}
