package app.bucc.ui

import app.bucc.api.plane.PaperPlanePointTask
import app.bucc.api.plane.PaperPlanePointTasksResponse
import app.bucc.homework.HomeworkItem
import app.bucc.homework.HomeworkSource
import app.bucc.homework.HomeworkSubmission
import app.bucc.homework.ReportUpload
import app.bucc.model.dto.BykcChosenCourseDto
import app.bucc.model.dto.CgyyOrderDto
import app.bucc.model.dto.JudgeAssignmentSummaryDto
import app.bucc.model.dto.JudgeSubmissionStatus
import app.bucc.model.dto.SigninClassDto
import app.bucc.model.dto.SpocAssignmentSummaryDto
import app.bucc.model.dto.SpocSubmissionStatus
import app.bucc.model.dto.Week
import app.bucc.model.dto.YgdkOverviewResponse
import app.bucc.model.dto.YgdkTermSummaryDto
import app.bucc.ui.screens.menu.HomeTodoAction
import app.bucc.ui.screens.menu.HomeTodoSource
import app.bucc.ui.screens.menu.buildHomeTodoItems
import app.bucc.ui.screens.menu.buildPaperPlaneTodoItem
import app.bucc.ui.screens.menu.buildSigninTodoItems
import app.bucc.ui.screens.menu.buildTdTodoItem
import app.bucc.ui.screens.menu.findSigninCourse
import app.bucc.ui.screens.menu.formatHomeTodoFailure
import app.bucc.ui.screens.menu.paperPlanePointTasksError
import app.bucc.ui.screens.menu.parseHomeDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

class HomeTodoTest {
  @Test
  fun `daily paper plane and TD reminders precede longer lived homework`() {
    val items =
        buildHomeTodoItems(
            bykcCourses = emptyList(),
            spocAssignments = emptyList(),
            judgeAssignments = emptyList(),
            cgyyOrders = emptyList(),
            homeworkItems =
                listOf(
                    HomeworkItem(
                        id = "assignment",
                        source = HomeworkSource.SPOC,
                        upstreamId = "assignment",
                        title = "课程作业",
                        dueAt = LocalDateTime.parse("2026-03-24T20:00:00"),
                        submission = HomeworkSubmission.UNSUBMITTED,
                    )
                ),
            tdSemesterCount = 1,
            tdTerm = "2025-2026-2",
            paperPlaneTasks =
                PaperPlanePointTasksResponse(
                    list =
                        listOf(
                            PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "0"),
                            PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = "0"),
                        )
                ),
            now = NOW,
        )
    assertEquals(
        listOf(HomeTodoSource.TD, HomeTodoSource.PAPER_PLANE, HomeTodoSource.SPOC),
        items.map { it.source },
    )
  }

  @Test
  fun `official quoted task labels drive the daily todo without a missing task error`() {
    val tasks =
        PaperPlanePointTasksResponse(
            list =
                listOf(
                    PaperPlanePointTask(gzmc = "投掷“课堂纸飞机”", num = "5"),
                    PaperPlanePointTask(gzmc = "浏览“课堂纸飞机”", num = "4"),
                )
        )
    assertNull(paperPlanePointTasksError(tasks))
    assertNotNull(
        paperPlanePointTasksError(
            tasks.copy(list = tasks.list + PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = "5"))
        )
    )
    val todo =
        assertNotNull(
            buildPaperPlaneTodoItem(tasks, true, false, LocalDateTime(2026, 10, 14, 10, 0))
        )
    assertEquals("浏览 4 / 5 次", todo.subtitle)
    assertNull(
        buildPaperPlaneTodoItem(
            tasks.copy(list = tasks.list.map { it.copy(num = "5") }),
            true,
            false,
            LocalDateTime(2026, 10, 14, 10, 0),
        )
    )
  }

  @Test
  fun `all todos keep distinct pending BYXT rounds with similar titles across sources`() {
    fun byxt(
        id: String,
        due: String?,
        uploaded: Boolean = false,
        submission: HomeworkSubmission = HomeworkSubmission.UNSUBMITTED,
    ) =
        HomeworkItem(
            id = id,
            source = HomeworkSource.BYXT,
            upstreamId = id,
            title = "物理实验报告",
            dueAt = due?.let(LocalDateTime::parse),
            submission = submission,
            reports = listOf(ReportUpload(1, uploaded, dueAt = due?.let(LocalDateTime::parse))),
        )
    val input =
        listOf(
            byxt("byxt-later", "2026-03-28T12:00:00"),
            byxt("byxt-nearest", "2026-03-26T12:00:00"),
            byxt("byxt-nearest-tie", "2026-03-26T12:00:00"),
            HomeworkItem(
                id = "byxt:term:physics:round-partial",
                source = HomeworkSource.BYXT,
                upstreamId = "round-partial",
                title = "物理实验报告",
                dueAt = LocalDateTime.parse("2026-03-23T12:00:00"),
                submission = HomeworkSubmission.PARTIAL,
                reports =
                    listOf(
                        ReportUpload(
                            1,
                            uploaded = false,
                            dueAt = LocalDateTime.parse("2026-03-23T12:00:00"),
                        ),
                        ReportUpload(
                            2,
                            uploaded = false,
                            dueAt = LocalDateTime.parse("2026-03-27T12:00:00"),
                        ),
                    ),
            ),
            byxt("byxt-unknown", null),
            byxt("byxt-expired", "2026-03-23T12:00:00"),
            byxt(
                "byxt-submitted",
                "2026-03-25T12:00:00",
                uploaded = true,
                submission = HomeworkSubmission.SUBMITTED,
            ),
            HomeworkItem(
                id = "accoding-one",
                source = HomeworkSource.ACCODING,
                upstreamId = "a1",
                title = "物理实验报告",
                dueAt = LocalDateTime.parse("2026-03-25T12:00:00"),
                submission = HomeworkSubmission.UNSUBMITTED,
            ),
            HomeworkItem(
                id = "accoding-two",
                source = HomeworkSource.ACCODING,
                upstreamId = "a2",
                title = "普通作业二",
                dueAt = null,
                submission = HomeworkSubmission.UNSUBMITTED,
            ),
        )

    val items =
        buildHomeTodoItems(
            bykcCourses = emptyList(),
            spocAssignments = emptyList(),
            judgeAssignments = emptyList(),
            cgyyOrders = emptyList(),
            homeworkItems = input + input.first(),
            now = NOW,
        )

    assertEquals(
        listOf(
            "accoding-one",
            "byxt-nearest",
            "byxt-nearest-tie",
            "byxt:term:physics:round-partial",
            "byxt-later",
            "byxt-unknown",
            "accoding-two",
        ),
        items.map { it.id },
    )
    assertEquals(7, items.size)
    assertEquals(5, items.count { it.source == HomeTodoSource.BYXT })
    assertEquals(6, items.count { it.title == "物理实验报告" })
    assertEquals("截止 3月26日 12:00", items.first { it.id == "byxt-nearest" }.timeLabel)
  }

  @Test
  fun `unknown BYXT deadline sorts last and keeps stable source order`() {
    val unknownByxt =
        HomeworkItem(
            id = "byxt-unknown-first",
            source = HomeworkSource.BYXT,
            upstreamId = "unknown",
            title = "未知截止实验",
            reports = listOf(ReportUpload(1, uploaded = false)),
            submission = HomeworkSubmission.UNSUBMITTED,
        )
    val otherKnown =
        HomeworkItem(
            id = "accoding-known",
            source = HomeworkSource.ACCODING,
            upstreamId = "known",
            title = "有截止普通作业",
            dueAt = LocalDateTime.parse("2026-03-25T12:00:00"),
            submission = HomeworkSubmission.UNSUBMITTED,
        )
    val otherUnknown =
        HomeworkItem(
            id = "accoding-unknown-second",
            source = HomeworkSource.ACCODING,
            upstreamId = "unknown",
            title = "未知截止普通作业",
            submission = HomeworkSubmission.UNSUBMITTED,
        )

    val items =
        buildHomeTodoItems(
            bykcCourses = emptyList(),
            spocAssignments = emptyList(),
            judgeAssignments = emptyList(),
            cgyyOrders = emptyList(),
            homeworkItems = listOf(unknownByxt, otherKnown, otherUnknown),
            now = NOW,
        )

    assertEquals(
        listOf("accoding-known", "byxt-unknown-first", "accoding-unknown-second"),
        items.map { it.id },
    )
  }

  @Test
  fun `home todo failure message includes source errors`() {
    assertEquals(
        "部分来源暂未更新：TD 健康云：健康云会话不可用；阳光打卡",
        formatHomeTodoFailure(
            listOf(HomeTodoSource.TD, HomeTodoSource.YGDK),
            mapOf(HomeTodoSource.TD to "健康云会话不可用"),
        ),
    )
  }

  @Test
  fun `buildHomeTodoItems filters invalid items and sorts by mixed time`() {
    val items =
        buildHomeTodoItems(
            bykcCourses =
                listOf(
                    BykcChosenCourseDto(
                        id = 1,
                        courseId = 101,
                        courseName = "博雅进行中",
                        courseTeacher = "王老师",
                        coursePosition = "学院路校区",
                        courseStartDate = LocalDateTime.parse("2026-03-24T11:00:00"),
                        courseEndDate = LocalDateTime.parse("2026-03-24T13:00:00"),
                    ),
                    BykcChosenCourseDto(
                        id = 2,
                        courseId = 102,
                        courseName = "博雅已结束",
                        courseStartDate = LocalDateTime.parse("2026-03-24T08:00:00"),
                        courseEndDate = LocalDateTime.parse("2026-03-24T09:00:00"),
                    ),
                ),
            spocAssignments =
                listOf(
                    SpocAssignmentSummaryDto(
                        assignmentId = "spoc-1",
                        courseId = "course-1",
                        courseName = "算法设计",
                        title = "第一次作业",
                        dueTime = "2026-03-24 12:30:00",
                        submissionStatus = SpocSubmissionStatus.UNSUBMITTED,
                        submissionStatusText = "未提交",
                    ),
                    SpocAssignmentSummaryDto(
                        assignmentId = "spoc-2",
                        courseId = "course-2",
                        courseName = "编译原理",
                        title = "已交作业",
                        dueTime = "2026-03-24 12:10:00",
                        submissionStatus = SpocSubmissionStatus.SUBMITTED,
                        submissionStatusText = "已提交",
                    ),
                ),
            judgeAssignments =
                listOf(
                    JudgeAssignmentSummaryDto(
                        courseId = "judge-course-1",
                        courseName = "希冀课程",
                        assignmentId = "judge-1",
                        title = "希冀未完成作业",
                        dueTime = "2026-03-24 12:20:00",
                        maxScore = "100",
                        totalProblems = 2,
                        submittedCount = 1,
                        submissionStatus = JudgeSubmissionStatus.PARTIAL,
                        submissionStatusText = "进行中(1/2)",
                    ),
                    JudgeAssignmentSummaryDto(
                        courseId = "judge-course-2",
                        courseName = "希冀课程",
                        assignmentId = "judge-2",
                        title = "希冀已完成作业",
                        dueTime = "2026-03-24 12:15:00",
                        maxScore = "10",
                        myScore = "10",
                        totalProblems = 1,
                        submittedCount = 1,
                        submissionStatus = JudgeSubmissionStatus.SUBMITTED,
                        submissionStatusText = "已完成 10/10",
                    ),
                ),
            cgyyOrders =
                listOf(
                    CgyyOrderDto(
                        id = 201,
                        venueName = "老主楼研讨室",
                        venueSpaceName = "A201",
                        theme = "小组讨论",
                        reservationStartDate = "2026-03-24 13:30:00",
                        reservationEndDate = "2026-03-24 15:00:00",
                        orderStatus = 1,
                        checkStatus = 2,
                    ),
                    CgyyOrderDto(
                        id = 202,
                        venueName = "新主楼研讨室",
                        venueSpaceName = "B301",
                        reservationStartDate = "2026-03-24 10:00:00",
                        reservationEndDate = "2026-03-24 11:00:00",
                        orderStatus = 2,
                    ),
                ),
            now = NOW,
        )

    assertEquals(
        listOf(
            "bykc:101",
            "judge:judge-course-1:judge-1",
            "spoc:spoc-1",
            "cgyy:201",
        ),
        items.map { it.id },
    )
    assertEquals("进行中", items.first().statusLabel)
  }

  @Test
  fun `active signin course keeps its direct sign action outside the todo list`() {
    val items =
        buildSigninTodoItems(
            classes =
                listOf(
                    SigninClassDto(
                        courseId = "signin-1",
                        courseName = "大学物理",
                        classBeginTime = "12:08",
                        classEndTime = "13:00",
                        signStatus = 0,
                    ),
                ),
            now = NOW,
            timeZone = TimeZone.UTC,
        )

    val item = items.single()
    val action = assertIs<HomeTodoAction.SigninCourse>(item.action)
    assertEquals("signin-1", action.courseId)
    assertEquals("签到", item.actionLabel)
    assertEquals("即将签到", item.statusLabel)
  }

  @Test
  fun `signin matching rejects ambiguous same-name same-time courses`() {
    val course =
        app.bucc.model.dto.CourseClass(
            courseCode = "phys-101",
            courseName = "大学物理",
            courseSerialNo = null,
            credit = null,
            beginTime = "12:08",
            endTime = "13:00",
            beginSection = null,
            endSection = null,
            placeName = null,
            weeksAndTeachers = null,
            teachingTarget = null,
            color = null,
            dayOfWeek = 3,
        )
    val candidates =
        listOf(
            SigninClassDto("signin-a", "大学物理", "12:08", "13:00", 0),
            SigninClassDto("signin-b", "大学物理", "12:08", "13:00", 0),
        )

    assertNull(findSigninCourse(course, candidates))
  }

  @Test
  fun `signin matching normalizes clock seconds and date time values`() {
    val course =
        app.bucc.model.dto.CourseClass(
            courseCode = "phys-101",
            courseName = " 大学物理 ",
            courseSerialNo = null,
            credit = null,
            beginTime = "12:08:00",
            endTime = "13:00",
            beginSection = null,
            endSection = null,
            placeName = null,
            weeksAndTeachers = null,
            teachingTarget = null,
            color = null,
            dayOfWeek = 3,
        )

    val match =
        findSigninCourse(
            course,
            listOf(
                SigninClassDto("signin-1", "大学物理", "2026-04-22 12:08", "2026-04-22T13:00:00", 0)
            ),
        )

    assertEquals("signin-1", match?.courseId)
  }

  @Test
  fun `signin matching does not fall back to same course name`() {
    val course =
        app.bucc.model.dto.CourseClass(
            courseCode = "phys-101",
            courseName = "大学物理",
            courseSerialNo = null,
            credit = null,
            beginTime = "12:08",
            endTime = "13:00",
            beginSection = null,
            endSection = null,
            placeName = null,
            weeksAndTeachers = null,
            teachingTarget = null,
            color = null,
            dayOfWeek = 3,
        )

    assertNull(
        findSigninCourse(course, listOf(SigninClassDto("other", "大学物理", "12:09", "13:00", 0)))
    )
  }

  @Test
  fun `ordinary homework remains in todo list`() {
    val ordinaryHomework =
        HomeworkItem(
            id = "homework-1",
            source = HomeworkSource.SPOC,
            upstreamId = "assignment-1",
            title = "普通作业",
            dueAt = LocalDateTime.parse("2026-03-25T12:00:00"),
            submission = HomeworkSubmission.UNSUBMITTED,
        )
    val items =
        buildHomeTodoItems(
            bykcCourses = emptyList(),
            spocAssignments = emptyList(),
            judgeAssignments = emptyList(),
            cgyyOrders = emptyList(),
            homeworkItems = listOf(ordinaryHomework),
            now = NOW,
        )

    assertEquals(listOf("homework-1"), items.map { it.id })
  }

  @Test
  fun `judge todo item opens assignment detail with course and assignment ids`() {
    val items =
        buildHomeTodoItems(
            bykcCourses = emptyList(),
            spocAssignments = emptyList(),
            judgeAssignments =
                listOf(
                    JudgeAssignmentSummaryDto(
                        courseId = "course-1",
                        courseName = "希冀课程",
                        assignmentId = "assignment-1",
                        title = "待完成希冀作业",
                        dueTime = "2026-03-24 13:00:00",
                        maxScore = "100",
                        totalProblems = 2,
                        submittedCount = 0,
                        submissionStatus = JudgeSubmissionStatus.UNSUBMITTED,
                        submissionStatusText = "未提交",
                    )
                ),
            cgyyOrders = emptyList(),
            now = NOW,
        )

    val item = items.single()
    val action = assertIs<HomeTodoAction.OpenJudgeAssignment>(item.action)
    assertEquals("course-1", action.courseId)
    assertEquals("assignment-1", action.assignmentId)
    assertEquals("待提交", item.statusLabel)
  }

  @Test
  fun `spoc todo items hide assignments that have not started`() {
    val items =
        buildHomeTodoItems(
            bykcCourses = emptyList(),
            spocAssignments =
                listOf(
                    SpocAssignmentSummaryDto(
                        assignmentId = "spoc-started",
                        courseId = "course-1",
                        courseName = "算法设计",
                        title = "已开始作业",
                        startTime = "2026-03-24 11:00:00",
                        dueTime = "2026-03-24 13:00:00",
                        submissionStatus = SpocSubmissionStatus.UNSUBMITTED,
                        submissionStatusText = "未提交",
                    ),
                    SpocAssignmentSummaryDto(
                        assignmentId = "spoc-future",
                        courseId = "course-2",
                        courseName = "编译原理",
                        title = "未开始作业",
                        startTime = "2026-03-24 12:30:00",
                        dueTime = "2026-03-24 14:00:00",
                        submissionStatus = SpocSubmissionStatus.UNSUBMITTED,
                        submissionStatusText = "未提交",
                    ),
                ),
            judgeAssignments = emptyList(),
            cgyyOrders = emptyList(),
            now = NOW,
        )

    assertEquals(listOf("spoc:spoc-started"), items.map { it.id })
  }

  @Test
  fun `ygdk todo item appears during final reminder weeks when counts are below targets`() {
    val items =
        buildHomeTodoItems(
            bykcCourses = emptyList(),
            spocAssignments = emptyList(),
            judgeAssignments = emptyList(),
            cgyyOrders = emptyList(),
            ygdkOverview = ygdkOverview(weekCount = 3, termCount = 15),
            currentWeek = week(serialNumber = 11),
            now = NOW,
        )

    val item = assertNotNull(items.singleOrNull())
    assertEquals("ygdk:2025-2026-2:11", item.id)
    assertEquals(HomeTodoSource.YGDK, item.source)
    assertEquals("本周阳光打卡未达标", item.title)
    assertEquals("本周已打卡 3 / 4 次", item.subtitle)
    assertEquals("待打卡", item.statusLabel)
    assertEquals("截止 3月29日 23:59", item.timeLabel)
    assertEquals(LocalDateTime.parse("2026-03-29T23:59:59"), item.sortTime)
    assertIs<HomeTodoAction.OpenYgdkHome>(item.action)
  }

  @Test
  fun `ygdk todo item hides outside final reminder weeks`() {
    val items =
        ygdkTodoItems(
            overview = ygdkOverview(weekCount = 3, termCount = 15),
            currentWeek = week(serialNumber = 10),
        )

    assertEquals(emptyList(), items.map { it.id })
    assertEquals(
        listOf("ygdk:${week(serialNumber = 11).term}:11"),
        ygdkTodoItems(
                overview = ygdkOverview(weekCount = 0, termCount = 0),
                currentWeek = week(serialNumber = 11),
            )
            .map { it.id },
    )
    assertEquals(
        listOf("ygdk:${week(serialNumber = 14).term}:14"),
        ygdkTodoItems(
                overview = ygdkOverview(weekCount = 0, termCount = 0),
                currentWeek = week(serialNumber = 14),
            )
            .map { it.id },
    )
  }

  @Test
  fun `ygdk todo item hides after weekly or term target is reached`() {
    assertEquals(
        emptyList(),
        ygdkTodoItems(
                overview = ygdkOverview(weekCount = 4, termCount = 15),
                currentWeek = week(serialNumber = 12),
            )
            .map { it.id },
    )
    assertEquals(
        emptyList(),
        ygdkTodoItems(
                overview = ygdkOverview(weekCount = 3, termCount = 16),
                currentWeek = week(serialNumber = 12),
            )
            .map { it.id },
    )
  }

  @Test
  fun `ygdk does not treat a missing term count as zero`() {
    assertEquals(
        emptyList(),
        ygdkTodoItems(
                overview = ygdkOverview(weekCount = 3, termCount = null),
                currentWeek = week(serialNumber = 12),
            )
            .map { it.id },
    )
  }

  @Test
  fun `ygdk query failure suppresses reminders based on stale overview`() {
    assertEquals(
        emptyList(),
        ygdkTodoItems(
                overview = ygdkOverview(weekCount = 0, termCount = 0),
                currentWeek = week(serialNumber = 12),
                ygdkQueryFailed = true,
            )
            .map { it.id },
    )
  }

  @Test
  fun `TD todo uses semester target of 32 scoped to active term`() {
    val item =
        assertNotNull(
            buildTdTodoItem(semesterCount = 31, term = "2025-2026-2", visible = true, now = NOW)
        )
    assertEquals(HomeTodoSource.TD, item.source)
    assertEquals("td:2025-2026-2", item.id)
    assertEquals("本学期有效次数 31 / 32 次", item.subtitle)
    assertEquals("本学期目标", item.timeLabel)
    assertIs<HomeTodoAction.OpenTd>(item.action)
    assertNull(buildTdTodoItem(semesterCount = 32, term = "2025-2026-2", visible = true, now = NOW))
    assertNull(
        buildTdTodoItem(semesterCount = null, term = "2025-2026-2", visible = true, now = NOW)
    )
    assertNull(buildTdTodoItem(semesterCount = 0, term = null, visible = true, now = NOW))
    assertNull(buildTdTodoItem(semesterCount = 0, term = "2025-2026-2", visible = false, now = NOW))
    assertNull(
        buildTdTodoItem(
            semesterCount = 31,
            term = "2025-2026-2",
            visible = true,
            now = NOW,
            queryFailed = true,
        )
    )
  }

  @Test
  fun `paper plane todo appears for a known task below five and hides only when both targets are met`() {
    val incomplete = paperPlaneTasks(throwCount = 4, browseCount = 5)
    val todo = buildPaperPlaneTodoItem(incomplete, visible = true, queryFailed = false, now = NOW)
    assertNotNull(todo)
    assertEquals(HomeTodoSource.PAPER_PLANE, todo.source)
    assertEquals("paper-plane:2026-03-24", todo.id)
    assertEquals("投掷课堂纸飞机", todo.title)
    assertIs<HomeTodoAction.OpenPaperPlane>(todo.action)

    assertNull(buildPaperPlaneTodoItem(paperPlaneTasks(5, 5), true, false, NOW))
    assertNotNull(buildPaperPlaneTodoItem(paperPlaneTasks(5, 4), true, false, NOW))
    assertNull(buildPaperPlaneTodoItem(paperPlaneTasks(4, 5), false, false, NOW))
    assertNull(buildPaperPlaneTodoItem(paperPlaneTasks(4, 5), true, true, NOW))
  }

  @Test
  fun `paper plane task contract rejects missing invalid and duplicate target rows`() {
    val valid = paperPlaneTasks(4, 5)
    assertNull(paperPlanePointTasksError(valid))
    val malformed =
        listOf(
            PaperPlanePointTasksResponse(list = valid.list.take(1)),
            PaperPlanePointTasksResponse(list = valid.list + valid.list.first()),
            PaperPlanePointTasksResponse(
                list = valid.list.map { if (it.gzmc == "投掷课堂纸飞机") it.copy(num = "未知") else it },
            ),
        )
    malformed.forEach { response ->
      assertNotNull(paperPlanePointTasksError(response))
      assertNull(buildPaperPlaneTodoItem(response, visible = true, queryFailed = false, now = NOW))
    }
  }

  @Test
  fun `future BYXT reports stay visible as not yet open without changing other homework sources`() {
    val futureLab =
        HomeworkItem(
            id = "byxt:test:1",
            source = HomeworkSource.BYXT,
            upstreamId = "1",
            title = "未来实验报告",
            courseName = "虚构实验课",
            startAt = LocalDateTime.parse("2026-03-25T08:00:00"),
            dueAt = LocalDateTime.parse("2026-03-30T23:59:00"),
            submission = HomeworkSubmission.UNSUBMITTED,
            experimentAt = LocalDateTime.parse("2026-03-26T08:00:00"),
        )
    val futureOther =
        futureLab.copy(
            id = "accoding:test:1",
            source = HomeworkSource.ACCODING,
            title = "未来普通作业",
        )
    val items =
        buildHomeTodoItems(
            bykcCourses = emptyList(),
            spocAssignments = emptyList(),
            judgeAssignments = emptyList(),
            cgyyOrders = emptyList(),
            homeworkItems = listOf(futureLab, futureOther),
            now = NOW,
        )

    assertEquals(listOf("byxt:test:1"), items.map { it.id })
    assertEquals("尚未开放", items.single().statusLabel)
  }

  @Test
  fun `YGDK visibility independently hides the reminder`() {
    assertEquals(
        emptyList(),
        buildHomeTodoItems(
                bykcCourses = emptyList(),
                spocAssignments = emptyList(),
                judgeAssignments = emptyList(),
                cgyyOrders = emptyList(),
                ygdkOverview = ygdkOverview(weekCount = 3, termCount = 15),
                currentWeek = week(serialNumber = 12),
                ygdkVisible = false,
                now = NOW,
            )
            .map { it.id },
    )
  }

  @Test
  fun `ygdk todo item hides when reminder is disabled or locally completed`() {
    assertEquals(
        emptyList(),
        ygdkTodoItems(
                overview = ygdkOverview(weekCount = 3, termCount = 15),
                currentWeek = week(serialNumber = 13),
                ygdkReminderEnabled = false,
            )
            .map { it.id },
    )
    assertEquals(
        emptyList(),
        ygdkTodoItems(
                overview = ygdkOverview(weekCount = 3, termCount = 15),
                currentWeek = week(serialNumber = 13),
                ygdkWeekDone = true,
            )
            .map { it.id },
    )
    assertEquals(
        emptyList(),
        ygdkTodoItems(
                overview = ygdkOverview(weekCount = 3, termCount = 15),
                currentWeek = week(serialNumber = 13),
                ygdkTermDone = true,
            )
            .map { it.id },
    )
  }

  @Test
  fun `parseHomeDateTime supports full datetime and time only`() {
    assertEquals(
        LocalDateTime.parse("2026-03-24T14:30:00"),
        parseHomeDateTime("2026-03-24 14:30:00"),
    )
    assertEquals(
        LocalDateTime.parse("2026-03-24T09:15:00"),
        parseHomeDateTime("09:15", LocalDateTime.parse("2026-03-24T12:00:00").date),
    )
    assertNull(parseHomeDateTime("not-a-time"))
  }

  companion object {
    private val NOW = LocalDateTime.parse("2026-03-24T12:00:00")

    private fun paperPlaneTasks(throwCount: Int, browseCount: Int) =
        PaperPlanePointTasksResponse(
            list =
                listOf(
                    PaperPlanePointTask(gzmc = "投掷课堂纸飞机", num = throwCount.toString()),
                    PaperPlanePointTask(gzmc = "浏览课堂纸飞机", num = browseCount.toString()),
                ),
        )

    private fun ygdkTodoItems(
        overview: YgdkOverviewResponse?,
        currentWeek: Week?,
        ygdkReminderEnabled: Boolean = true,
        ygdkWeekDone: Boolean = false,
        ygdkTermDone: Boolean = false,
        ygdkQueryFailed: Boolean = false,
    ) =
        buildHomeTodoItems(
            bykcCourses = emptyList(),
            spocAssignments = emptyList(),
            judgeAssignments = emptyList(),
            cgyyOrders = emptyList(),
            ygdkOverview = overview,
            currentWeek = currentWeek,
            ygdkReminderEnabled = ygdkReminderEnabled,
            ygdkWeekDone = ygdkWeekDone,
            ygdkTermDone = ygdkTermDone,
            ygdkQueryFailed = ygdkQueryFailed,
            now = NOW,
        )

    private fun ygdkOverview(
        weekCount: Int?,
        termCount: Int?,
    ) =
        YgdkOverviewResponse(
            summary = YgdkTermSummaryDto(termCount = termCount, weekCount = weekCount),
            classifyId = 3,
            classifyName = "阳光体育",
        )

    private fun week(serialNumber: Int) =
        Week(
            startDate = "2026-06-15",
            endDate = "2026-06-21",
            term = "2025-2026-2",
            curWeek = true,
            serialNumber = serialNumber,
            name = "第${serialNumber}周",
        )
  }
}
