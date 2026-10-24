package app.bucc.repository

import app.bucc.model.dto.CourseClass
import app.bucc.model.dto.SectionTime
import app.bucc.model.dto.Week
import app.bucc.model.dto.WeeklySchedule
import app.bucc.model.dto.normalizeCourseClasses
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** A dated occurrence, rather than an undated weekly course. */
data class UpcomingClass(
    val id: String,
    val date: LocalDate,
    val course: CourseClass,
    val start: LocalDateTime?,
    val end: LocalDateTime?,
) {
  fun isInProgress(now: LocalDateTime): Boolean =
      start != null && end != null && now >= start && now < end
}

fun datedScheduleClasses(
    weeks: List<Pair<Week, WeeklySchedule>>,
    now: LocalDateTime,
): List<UpcomingClass> =
    normalizeDatedWeekSchedules(weeks)
        .flatMap { (week, schedule) ->
          val monday = LocalDate.parse(week.startDate.substringBefore(' ').substringBefore('T'))
          schedule.arrangedList.mapNotNull { course ->
            val day = course.dayOfWeek ?: return@mapNotNull null
            if (day !in 1..7) return@mapNotNull null
            val date = monday.plus(day - 1, DateTimeUnit.DAY)
            val startTime =
                course.beginTime
                    ?: schedule.sectionTimes
                        .firstOrNull { it.section == course.beginSection }
                        ?.start
            val endTime =
                course.endTime
                    ?: schedule.sectionTimes.firstOrNull { it.section == course.endSection }?.end
            val start =
                startTime?.let { parseCourseTime(it)?.let { time -> LocalDateTime(date, time) } }
            val end =
                endTime?.let { parseCourseTime(it)?.let { time -> LocalDateTime(date, time) } }
            if (
                date > LocalDate.parse(week.endDate.substringBefore(' ').substringBefore('T')) ||
                    date < now.date
            )
                return@mapNotNull null
            UpcomingClass(
                "${week.term}/${week.serialNumber}/${course.courseCode}/${course.courseSerialNo}/${course.teachingTarget}/${course.weeksAndTeachers}/${course.courseName}/$date/$startTime/$endTime/${course.beginSection}/${course.endSection}/${course.placeName}/${course.credit}/${course.color}",
                date,
                course,
                start,
                end,
            )
          }
        }
        .distinct()
        .sortedWith(
            compareBy<UpcomingClass> { it.date }.thenBy { it.start?.time ?: LocalTime(23, 59) }
        )

private data class DatedWeekKey(
    val term: String,
    val serialNumber: Int,
    val startDate: String,
    val endDate: String,
)

private fun normalizeDatedWeekSchedules(
    weeks: List<Pair<Week, WeeklySchedule>>,
): List<Pair<Week, WeeklySchedule>> =
    weeks
        .groupBy { (week, _) ->
          DatedWeekKey(week.term, week.serialNumber, week.startDate, week.endDate)
        }
        .map { (_, entries) ->
          val ordered =
              entries.sortedWith(
                  compareBy(
                      { it.first.term },
                      { it.first.serialNumber },
                      { it.second.code },
                      { it.second.name },
                  )
              )
          val week = ordered.first().first
          val schedules = ordered.map { it.second }
          val sectionTimes =
              schedules
                  .flatMap { it.sectionTimes }
                  .distinct()
                  .sortedWith(
                      compareBy<SectionTime> { it.section }
                          .thenBy { it.start.orEmpty() }
                          .thenBy { it.end.orEmpty() }
                  )
          week to
              WeeklySchedule(
                  arrangedList =
                      normalizeCourseClasses(schedules.flatMap { it.arrangedList }, sectionTimes),
                  code = schedules.map { it.code }.minOrNull().orEmpty(),
                  name = schedules.map { it.name }.minOrNull().orEmpty(),
                  sectionTimes = sectionTimes,
              )
        }
        .sortedWith(compareBy({ it.first.startDate }, { it.first.term }, { it.first.serialNumber }))

fun upcomingClasses(
    weeks: List<Pair<Week, WeeklySchedule>>,
    now: LocalDateTime,
): List<UpcomingClass> {
  val remaining =
      datedScheduleClasses(weeks, now).filterNot { occurrence ->
        occurrence.date == now.date && occurrence.end != null && occurrence.end <= now
      }
  val nextClassDate = remaining.firstOrNull()?.date
  return remaining.filter { it.date == nextClassDate }
}

/** Removes a course when a later item in the source order overlaps it. */
fun filterScheduleConflicts(
    courses: List<CourseClass>,
    sectionTimes: List<SectionTime> = emptyList(),
): List<CourseClass> {
  val ranges =
      courses.map { course ->
        val start =
            course.beginTime
                ?: sectionTimes.firstOrNull { it.section == course.beginSection }?.start
        val end =
            course.endTime ?: sectionTimes.firstOrNull { it.section == course.endSection }?.end
        val parsedStart = start?.let(::parseCourseTime)
        val parsedEnd = end?.let(::parseCourseTime)
        if (parsedStart != null && parsedEnd != null && parsedStart < parsedEnd) {
          CourseInterval(
              parsedStart.hour * 60 + parsedStart.minute,
              parsedEnd.hour * 60 + parsedEnd.minute,
              byTime = true,
          )
        } else {
          val first = course.beginSection
          val last = course.endSection ?: first
          if (first != null && last != null && first <= last)
              CourseInterval(first, last + 1, byTime = false)
          else null
        }
      }
  return courses.filterIndexed { index, course ->
    val range = ranges[index]
    range == null ||
        courses.indices.none { laterIndex ->
          val later = courses[laterIndex]
          val laterRange = ranges[laterIndex]
          laterIndex > index &&
              course.dayOfWeek != null &&
              course.dayOfWeek == later.dayOfWeek &&
              laterRange != null &&
              range.byTime == laterRange.byTime &&
              range.start < laterRange.end &&
              laterRange.start < range.end
        }
  }
}

private data class CourseInterval(val start: Int, val end: Int, val byTime: Boolean)

private fun parseCourseTime(raw: String): LocalTime? =
    runCatching { LocalTime.parse(raw.trim().let { if (it.length == 4) "0$it" else it }) }
        .getOrNull()

/** Connected overlap groups share a width; touching boundaries do not conflict. */
data class CourseLane(val course: CourseClass, val lane: Int, val laneCount: Int)

fun courseLanes(courses: List<CourseClass>): List<CourseLane> {
  fun start(course: CourseClass): Int =
      parseCourseTime(course.beginTime.orEmpty())?.let { it.hour * 60 + it.minute }
          ?: (course.beginSection ?: 1) * 60
  fun end(course: CourseClass): Int =
      parseCourseTime(course.endTime.orEmpty())?.let { it.hour * 60 + it.minute }
          ?: ((course.endSection ?: course.beginSection ?: 1) + 1) * 60
  return courses
      .groupBy { it.dayOfWeek }
      .values
      .flatMap { day ->
        val groups = mutableListOf<MutableList<CourseClass>>()
        var groupEnd = -1
        day.sortedWith(compareBy<CourseClass> { start(it) }.thenBy { end(it) }).forEach { course ->
          if (groups.isEmpty() || start(course) >= groupEnd) {
            groups.add(mutableListOf())
            groupEnd = end(course)
          } else groupEnd = maxOf(groupEnd, end(course))
          groups.last().add(course)
        }
        groups.flatMap { group ->
          val ends = mutableListOf<Int>()
          val lanes =
              group.map { course ->
                val free = ends.indexOfFirst { it <= start(course) }
                val lane =
                    if (free < 0) ends.size.also { ends.add(end(course)) }
                    else free.also { ends[it] = end(course) }
                course to lane
              }
          lanes.map { (course, lane) -> CourseLane(course, lane, ends.size) }
        }
      }
}
