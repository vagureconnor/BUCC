package app.bucc.repository

import app.bucc.api.feature.ScheduleApi
import app.bucc.api.feature.ScheduleApiBackend
import app.bucc.model.dto.*
import com.russhwolf.settings.MapSettings
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime

class UpcomingScheduleTest {
  private fun course(
      name: String,
      day: Int = 1,
      start: String = "08:00",
      end: String = "09:00",
      first: Int = 1,
      last: Int = 2,
  ) = CourseClass(name, name, null, null, start, end, first, last, "A101", null, null, null, day)

  private fun physics(
      start: String? = null,
      end: String? = null,
      first: Int? = null,
      last: Int? = null,
      place: String? = null,
      teacher: String? = "第1-8周 张老师",
      target: String? = "实验1班",
  ) =
      CourseClass(
          courseCode = "PHY101",
          courseName = "基础物理实验(1)",
          courseSerialNo = "PHY101-LAB-01",
          credit = "1",
          beginTime = start,
          endTime = end,
          beginSection = first,
          endSection = last,
          placeName = place,
          weeksAndTeachers = teacher,
          teachingTarget = target,
          color = "#123456",
          dayOfWeek = 1,
      )

  private fun week(n: Int, date: String) =
      Week(date, if (n == 1) "2026-10-11" else "2026-10-18", "20261", n == 1, n, "第${n}周")

  @Test
  fun `dated schedule combines a unique complementary time and room independent of input order`() {
    val waitingForTime = physics(place = "物理教学与实验中心（学生13公寓B1-046A）【学生13公寓】")
    val knownTime =
        physics(
            start = "08:50",
            end = "12:15",
            first = 2,
            last = 5,
            place = "物理教学与实验中心",
        )
    val monday = week(1, "2026-10-05")
    val now = LocalDateTime.parse("2026-10-05T07:00")

    fun result(courses: List<CourseClass>) =
        datedScheduleClasses(listOf(monday to WeeklySchedule(courses, "20261", "测试学期")), now)

    val forward = result(listOf(waitingForTime, knownTime))
    val reverse = result(listOf(knownTime, waitingForTime))
    assertEquals(1, forward.size)
    assertEquals(forward, reverse)
    assertEquals("08:50", forward.single().course.beginTime)
    assertEquals("12:15", forward.single().course.endTime)
    assertEquals(
        "物理教学与实验中心（学生13公寓B1-046A）【学生13公寓】",
        forward.single().course.placeName,
    )
    assertEquals(forward, result(listOf(forward.single().course, waitingForTime, knownTime)))
  }

  @Test
  fun `matching complete sections identify complementary rows when class identifiers are partial`() {
    val waitingForTime =
        CourseClass(
            courseCode = "SYNTH-COURSE-42",
            courseName = "合成课程",
            courseSerialNo = null,
            credit = null,
            beginTime = null,
            endTime = null,
            beginSection = 2,
            endSection = 5,
            placeName = "合成教学中心（合成宿舍A1-046A）【合成宿舍】",
            weeksAndTeachers = null,
            teachingTarget = null,
            color = null,
            dayOfWeek = 1,
        )
    val knownTime =
        waitingForTime.copy(
            courseSerialNo = "SYNTH-SECTION-7",
            beginTime = "08:50",
            endTime = "12:15",
            placeName = "合成教学中心",
            weeksAndTeachers = "合成周次 合成教师",
            teachingTarget = "合成教学班",
        )
    val monday = week(1, "2026-10-05")

    fun occurrences(courses: List<CourseClass>) =
        datedScheduleClasses(
            listOf(monday to WeeklySchedule(courses, "20261", "测试学期")),
            LocalDateTime.parse("2026-10-05T07:00"),
        )

    val forward = occurrences(listOf(waitingForTime, knownTime))
    val reverse = occurrences(listOf(knownTime, waitingForTime))

    assertEquals(1, forward.size)
    assertEquals(forward, reverse)
    assertEquals("08:50", forward.single().course.beginTime)
    assertEquals("12:15", forward.single().course.endTime)
    assertEquals("合成教学中心（合成宿舍A1-046A）【合成宿舍】", forward.single().course.placeName)
    assertEquals("SYNTH-SECTION-7", forward.single().course.courseSerialNo)
    assertEquals("合成教学班", forward.single().course.teachingTarget)
    assertEquals("合成周次 合成教师", forward.single().course.weeksAndTeachers)
  }

  @Test
  fun `different complete section ranges remain separate when class identifiers are absent`() {
    val shared =
        CourseClass(
            courseCode = "SYNTH-COURSE-42",
            courseName = "合成课程",
            courseSerialNo = null,
            credit = null,
            beginTime = null,
            endTime = null,
            beginSection = 2,
            endSection = 5,
            placeName = "合成教学中心",
            weeksAndTeachers = null,
            teachingTarget = null,
            color = null,
            dayOfWeek = 1,
        )
    val firstMeeting = shared.copy(beginTime = "08:50", endTime = "12:15")
    val secondMeeting =
        shared.copy(beginSection = 6, endSection = 7, beginTime = "14:00", endTime = "15:35")
    val result =
        datedScheduleClasses(
            listOf(
                week(1, "2026-10-05") to
                    WeeklySchedule(listOf(firstMeeting, secondMeeting), "20261", "测试学期")
            ),
            LocalDateTime.parse("2026-10-05T07:00"),
        )

    assertEquals(2, result.size)
    assertEquals(setOf("08:50", "14:00"), result.mapNotNull { it.course.beginTime }.toSet())
  }

  @Test
  fun `same course on different dates remains two dated occurrences`() {
    val mondayOne = week(1, "2026-10-05")
    val mondayTwo = week(2, "2026-10-12")
    val occurrences =
        datedScheduleClasses(
            listOf(
                mondayOne to
                    WeeklySchedule(
                        listOf(physics(start = "08:50", end = "12:15", first = 2, last = 5)),
                        "20261",
                        "学期",
                    ),
                mondayTwo to
                    WeeklySchedule(
                        listOf(physics(start = "08:50", end = "12:15", first = 2, last = 5)),
                        "20261",
                        "学期",
                    ),
            ),
            LocalDateTime.parse("2026-10-05T07:00"),
        )

    assertEquals(listOf("2026-10-05", "2026-10-12"), occurrences.map { it.date.toString() })
  }

  @Test
  fun `both unknown time rows and incompatible known details stay separate`() {
    val unknownTimeA = physics(place = "物理教学与实验中心")
    val unknownTimeB = physics(place = "另一教学楼A101")
    val knownTimeA =
        physics(start = "08:50", end = "12:15", first = 2, last = 5, place = "物理教学与实验中心")
    val knownTimeB =
        physics(start = "13:00", end = "14:00", first = 6, last = 7, place = "物理教学与实验中心")
    val roomConflict = knownTimeA.copy(placeName = "另一教学楼A101")
    val teacherConflict = knownTimeA.copy(weeksAndTeachers = "第1-8周 李老师")
    val classConflict = knownTimeA.copy(teachingTarget = "实验2班")
    val monday = week(1, "2026-10-05")

    fun occurrences(courses: List<CourseClass>) =
        datedScheduleClasses(
            listOf(monday to WeeklySchedule(courses, "20261", "测试学期")),
            LocalDateTime.parse("2026-10-05T07:00"),
        )

    assertEquals(2, occurrences(listOf(unknownTimeA, unknownTimeB)).size)
    assertEquals(2, occurrences(listOf(knownTimeA, knownTimeB)).size)
    assertEquals(2, occurrences(listOf(knownTimeA, roomConflict)).size)
    assertEquals(2, occurrences(listOf(knownTimeA, teacherConflict)).size)
    assertEquals(2, occurrences(listOf(knownTimeA, classConflict)).size)
    assertEquals(1, occurrences(listOf(unknownTimeA, unknownTimeA)).size)
  }

  @Test
  fun `unknown time is not attached when two known meeting candidates are possible`() {
    val genericPlace = "物理教学与实验中心"
    val detailedRoom = "物理教学与实验中心B1-046A"
    val unknown = physics(place = detailedRoom)
    val firstSlot =
        physics(start = "08:50", end = "10:35", first = 2, last = 3, place = genericPlace)
    val secondSlot =
        physics(start = "10:40", end = "12:15", first = 4, last = 5, place = genericPlace)
    val result =
        datedScheduleClasses(
            listOf(
                week(1, "2026-10-05") to
                    WeeklySchedule(listOf(unknown, firstSlot, secondSlot), "20261", "学期")
            ),
            LocalDateTime.parse("2026-10-05T07:00"),
        )

    assertEquals(3, result.size)
    assertEquals(2, result.count { it.start != null && it.end != null })
    assertEquals(1, result.count { it.start == null && it.end == null })
  }

  @Test
  fun `unrelated partial boundaries do not invent a complete meeting time`() {
    val startOnly = physics(start = "08:50", place = "物理教学与实验中心")
    val endOnly = physics(end = "12:15", place = "物理教学与实验中心")
    val result =
        datedScheduleClasses(
            listOf(
                week(1, "2026-10-05") to WeeklySchedule(listOf(startOnly, endOnly), "20261", "学期")
            ),
            LocalDateTime.parse("2026-10-05T07:00"),
        )
    assertEquals(2, result.size)
    assertTrue(result.none { it.start != null && it.end != null })
  }

  @Test
  fun `room code adjacent to chinese building name is recognized as specific detail`() {
    val buildingOnly =
        physics(
            start = "08:50",
            end = "12:15",
            first = 2,
            last = 5,
            place = "物理教学与实验中心",
        )
    val roomSpecific = buildingOnly.copy(placeName = "物理教学与实验中心B1-046A")
    val result =
        datedScheduleClasses(
            listOf(
                week(1, "2026-10-05") to
                    WeeklySchedule(listOf(buildingOnly, roomSpecific), "20261", "学期")
            ),
            LocalDateTime.parse("2026-10-05T07:00"),
        )

    assertEquals(1, result.size)
    assertEquals("物理教学与实验中心B1-046A", result.single().course.placeName)
  }

  @Test
  fun `today hides ended classes while another remains and switches after all end`() {
    val weeks =
        listOf(
            week(1, "2026-10-05") to
                WeeklySchedule(
                    listOf(
                        course("ended", 7, "08:00", "08:30"),
                        course("ongoing", 7, "09:00", "09:30"),
                        course("later", 7, "10:00", "10:30"),
                    ),
                    "",
                    "",
                ),
            week(2, "2026-10-12") to WeeklySchedule(listOf(course("next", 1)), "", ""),
        )
    val now = LocalDateTime.parse("2026-10-11T09:00")
    val result = upcomingClasses(weeks, now)
    assertEquals(listOf("ongoing", "later"), result.map { it.course.courseName })
    assertTrue(result[0].isInProgress(now))
    assertEquals(setOf("2026-10-11"), result.map { it.date.toString() }.toSet())
    val afterToday = upcomingClasses(weeks, LocalDateTime.parse("2026-10-11T10:30"))
    assertEquals(listOf("next"), afterToday.map { it.course.courseName })
    assertEquals("2026-10-12", afterToday.single().date.toString())
  }

  @Test
  fun `class ending exactly now is hidden and unknown end remains`() {
    val endsNow = course("ends-now", day = 7, start = "08:00", end = "09:00")
    val unknownEnd =
        course("unknown-end", day = 7, start = "08:00", end = "09:00").copy(endTime = null)
    val result =
        upcomingClasses(
            listOf(week(1, "2026-10-05") to WeeklySchedule(listOf(endsNow, unknownEnd), "", "")),
            LocalDateTime.parse("2026-10-11T09:00"),
        )
    assertEquals(listOf("unknown-end"), result.map { it.course.courseName })
    assertNull(result.single().end)
  }

  @Test
  fun `returns all classes on next date and keeps unknown end on today`() {
    val classes =
        (0..7)
            .map { index ->
              val hour = 8 + index * 2
              course(
                  "c$index",
                  day = 1,
                  start = "${hour.toString().padStart(2, '0')}:00",
                  end = "${(hour + 1).toString().padStart(2, '0')}:00",
              )
            }
            .reversed()
    val week2 = week(2, "2026-10-12") to WeeklySchedule(classes, "", "")
    val nextDate = upcomingClasses(listOf(week2), LocalDateTime.parse("2026-10-11T09:00"))
    assertEquals(8, nextDate.size)
    assertEquals((0..7).map { "c$it" }, nextDate.map { it.course.courseName })

    val unknownEnd =
        course("unknown-end", day = 7, start = "08:00", end = "09:00").copy(endTime = null)
    val schedule = WeeklySchedule(listOf(unknownEnd), "", "")
    val today =
        upcomingClasses(
            listOf(week(1, "2026-10-05") to schedule),
            LocalDateTime.parse("2026-10-11T12:00"),
        )
    assertEquals(listOf("unknown-end"), today.map { it.course.courseName })
    assertNull(today.single().end)
  }

  @Test
  fun `later arranged course wins overlaps and touching boundaries remain`() {
    val first = course("first", start = "08:00", end = "10:00")
    val later = course("later", start = "09:00", end = "11:00")
    val touching = course("touching", start = "11:00", end = "12:00")
    val withSectionTimes =
        course("section-time", start = "08:00", end = "09:00", first = 3, last = 3)
            .copy(beginTime = null, endTime = null)
    val result =
        filterScheduleConflicts(
            listOf(first, later, touching, withSectionTimes),
            listOf(SectionTime(3, "08:00", "09:00")),
        )
    assertEquals(listOf("later", "touching", "section-time"), result.map { it.courseName })
    val bySection =
        filterScheduleConflicts(
            listOf(
                course("section-first", first = 1, last = 2).copy(beginTime = null, endTime = null),
                course("section-later", first = 2, last = 3).copy(beginTime = null, endTime = null),
                course("section-touching", first = 4, last = 4)
                    .copy(beginTime = null, endTime = null),
            )
        )
    assertEquals(listOf("section-later", "section-touching"), bySection.map { it.courseName })

    val unknown = course("sections").copy(beginTime = null, endTime = null)
    val schedule =
        WeeklySchedule(
            listOf(unknown),
            "",
            "",
            listOf(SectionTime(1, "08:00", null), SectionTime(2, null, "09:35")),
        )
    assertTrue(
        upcomingClasses(
                listOf(week(2, "2026-10-12") to schedule),
                LocalDateTime.parse("2026-10-12T09:35"),
            )
            .isEmpty()
    )
  }

  @Test
  fun `upcoming occurrences retain overlapping classes`() {
    val classes =
        listOf(
            course("first", day = 1, start = "08:00", end = "10:00"),
            course("later", day = 1, start = "09:00", end = "11:00"),
        )
    val occurrences =
        upcomingClasses(
            listOf(week(2, "2026-10-12") to WeeklySchedule(classes, "", "")),
            LocalDateTime.parse("2026-10-11T12:00"),
        )
    assertEquals(listOf("first", "later"), occurrences.map { it.course.courseName })
  }

  @Test
  fun `transitive overlaps share lanes and touching or separate days do not`() {
    val a = course("a", start = "08:00", end = "10:00")
    val b = course("b", start = "09:00", end = "11:00")
    val c = course("c", start = "10:00", end = "12:00")
    val d = course("d", start = "12:00", end = "13:00")
    val result =
        courseLanes(listOf(a, b, c, d, course("other", day = 2))).associateBy {
          it.course.courseName
        }
    assertEquals(2, result.getValue("a").laneCount)
    assertNotEquals(result.getValue("a").lane, result.getValue("b").lane)
    assertEquals(result.getValue("a").lane, result.getValue("c").lane)
    assertEquals(1, result.getValue("d").laneCount)
    assertEquals(1, result.getValue("other").laneCount)
  }

  @Test
  fun `repository crosses week boundary and reuses fetched weekly schedules`() = runTest {
    ScheduleStore.settings = MapSettings()
    ScheduleStore.useAccount("A")
    val weekRequests = mutableListOf<Int>()
    val backend =
        object : ScheduleApiBackend {
          override suspend fun getTerms() = Result.success(listOf(Term("20261", "当前学期", true, 0)))

          override suspend fun getWeeks(termCode: String) =
              Result.success(listOf(week(1, "2026-10-05"), week(2, "2026-10-12")))

          override suspend fun getWeeklySchedule(
              termCode: String,
              week: Int,
          ): Result<WeeklySchedule> {
            weekRequests += week
            val courses =
                if (week == 1) listOf(course("ended", day = 7, start = "08:00", end = "09:00"))
                else
                    (0..7).map { index ->
                      val hour = 8 + index * 2
                      course(
                          "next$index",
                          day = 1,
                          start = "${hour.toString().padStart(2, '0')}:00",
                          end = "${(hour + 1).toString().padStart(2, '0')}:00",
                      )
                    }
            return Result.success(WeeklySchedule(courses, "", ""))
          }

          override suspend fun getTodaySchedule() = Result.success(emptyList<TodayClass>())

          override suspend fun getExamArrangement(termCode: String) =
              Result.failure<ExamArrangementData>(Exception("unused"))
        }
    val repo = ScheduleRepository(ScheduleApi { backend })
    val now = LocalDateTime.parse("2026-10-11T10:00")
    val result = repo.loadUpcomingClasses(now).getOrThrow()
    assertEquals((0..7).map { "next$it" }, result.map { it.course.courseName })
    assertEquals(listOf("ended"), repo.todayProgressOccurrences.map { it.course.courseName })
    assertEquals(listOf(1, 2), weekRequests)
    val cachedResult = repo.loadUpcomingClasses(now).getOrThrow()
    assertEquals(result, cachedResult)
    assertEquals(listOf(1, 2), weekRequests)
  }

  @Test
  fun `query snapshot survives restart does not mark complete import and is account scoped`() =
      runTest {
        ScheduleStore.settings = MapSettings()
        ScheduleStore.useAccount("A")
        var calls = 0
        var failing = false
        val backend =
            object : ScheduleApiBackend {
              override suspend fun getTerms(): Result<List<Term>> {
                calls++
                return if (failing) Result.failure(Exception("offline"))
                else Result.success(listOf(Term("20261", "2026秋", true, 0)))
              }

              override suspend fun getWeeks(termCode: String) =
                  Result.success(listOf(week(1, "2026-10-05"), week(2, "2026-10-12")))

              override suspend fun getWeeklySchedule(
                  termCode: String,
                  week: Int,
              ): Result<WeeklySchedule> {
                calls++
                return Result.success(
                    WeeklySchedule(if (week == 1) emptyList() else listOf(course("future")), "", "")
                )
              }

              override suspend fun getTodaySchedule() = Result.success(emptyList<TodayClass>())

              override suspend fun getExamArrangement(
                  termCode: String
              ): Result<ExamArrangementData> = Result.failure(Exception())
            }
        val now = LocalDateTime.parse("2026-10-11T10:00")
        val repo = ScheduleRepository(ScheduleApi { backend })
        assertEquals(
            "future",
            repo.loadUpcomingClasses(now).getOrThrow().single().course.courseName,
        )
        val prior = calls
        repo.loadWeekly("20261", 2).getOrThrow()
        assertEquals(prior, calls)
        assertFalse(ScheduleStore.hasSavedSchedule())
        failing = true
        val restored = ScheduleRepository(ScheduleApi { backend })
        assertEquals(
            "future",
            restored
                .loadUpcomingClasses(now, offlineOnly = true)
                .getOrThrow()
                .single()
                .course
                .courseName,
        )
        assertEquals(
            "future",
            restored.loadUpcomingClasses(now).getOrThrow().single().course.courseName,
        )
        ScheduleStore.useAccount("B")
        assertTrue(restored.loadUpcomingClasses(now, offlineOnly = true).getOrThrow().isEmpty())
      }
}
