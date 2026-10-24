package app.bucc.model.dto

/** Merge duplicate upstream rows only when their class identity and known details agree. */
internal fun normalizeCourseClasses(
    courses: List<CourseClass>,
    sectionTimes: List<SectionTime> = emptyList(),
): List<CourseClass> {
  var remaining = courses.distinct().sortedBy(::courseSortKey)
  while (true) {
    val pairs =
        findMutualMergePairs(remaining, sectionTimes) { left, right ->
              hasTimingEvidence(left, sectionTimes) && hasTimingEvidence(right, sectionTimes)
            }
            .ifEmpty {
              findMutualMergePairs(remaining, sectionTimes) { left, right ->
                hasTimingEvidence(left, sectionTimes) != hasTimingEvidence(right, sectionTimes)
              }
            }
    if (pairs.isEmpty()) return remaining

    val pairsByFirst = pairs.associateBy { it.first }
    val consumed = mutableSetOf<Int>()
    val merged = mutableListOf<CourseClass>()
    for (index in remaining.indices) {
      if (index in consumed) continue
      val pair = pairsByFirst[index]
      if (pair != null) {
        merged += mergeCourses(remaining[pair.first], remaining[pair.second])
        consumed += index
        consumed += pair.second
      } else {
        merged += remaining[index]
        consumed += index
      }
    }
    val next = merged.distinct().sortedBy(::courseSortKey)
    if (next == remaining) return next
    remaining = next
  }
}

private fun findMutualMergePairs(
    courses: List<CourseClass>,
    sectionTimes: List<SectionTime>,
    phaseAllows: (CourseClass, CourseClass) -> Boolean,
): List<Pair<Int, Int>> {
  val partners =
      courses.indices.associateWith { index ->
        courses.indices.filter { other ->
          other != index &&
              phaseAllows(courses[index], courses[other]) &&
              canMergeCourses(courses[index], courses[other], sectionTimes)
        }
      }
  return courses.indices.mapNotNull { index ->
    val partner = partners.getValue(index).singleOrNull() ?: return@mapNotNull null
    if (index < partner && partners.getValue(partner) == listOf(index)) index to partner else null
  }
}

private fun canMergeCourses(
    left: CourseClass,
    right: CourseClass,
    sectionTimes: List<SectionTime>,
): Boolean {
  if (left.dayOfWeek == null || left.dayOfWeek != right.dayOfWeek) return false
  if (left.courseCode.isBlank() || left.courseCode != right.courseCode) return false
  if (knownConflict(left.courseName, right.courseName)) return false
  if (knownConflict(left.courseSerialNo, right.courseSerialNo)) return false
  if (knownConflict(left.teachingTarget, right.teachingTarget)) return false
  if (knownConflict(left.weeksAndTeachers, right.weeksAndTeachers)) return false

  val sameClass =
      (known(left.courseSerialNo) != null &&
          known(left.courseSerialNo) == known(right.courseSerialNo)) ||
          (known(left.teachingTarget) != null &&
              known(left.teachingTarget) == known(right.teachingTarget)) ||
          hasSameValidSectionRange(left, right)
  if (!sameClass) return false

  if (knownConflict(left.beginSection?.toString(), right.beginSection?.toString())) return false
  if (knownConflict(left.endSection?.toString(), right.endSection?.toString())) return false
  val leftStart = effectiveStart(left, sectionTimes)
  val rightStart = effectiveStart(right, sectionTimes)
  val leftEnd = effectiveEnd(left, sectionTimes)
  val rightEnd = effectiveEnd(right, sectionTimes)
  if (knownConflict(leftStart, rightStart) || knownConflict(leftEnd, rightEnd)) return false
  if (hasTimingEvidence(left, sectionTimes) && hasTimingEvidence(right, sectionTimes)) {
    val sharedBoundary =
        (leftStart != null && leftStart == rightStart) ||
            (leftEnd != null && leftEnd == rightEnd) ||
            (left.beginSection != null && left.beginSection == right.beginSection) ||
            (left.endSection != null && left.endSection == right.endSection)
    if (!sharedBoundary) return false
  }

  if (!compatiblePlaces(left.placeName, right.placeName)) return false
  return true
}

private fun hasSameValidSectionRange(left: CourseClass, right: CourseClass): Boolean {
  val leftStart = left.beginSection ?: return false
  val leftEnd = left.endSection ?: return false
  val rightStart = right.beginSection ?: return false
  val rightEnd = right.endSection ?: return false
  return leftStart > 0 && leftEnd >= leftStart && leftStart == rightStart && leftEnd == rightEnd
}

private fun hasTimingEvidence(course: CourseClass, sectionTimes: List<SectionTime>): Boolean =
    effectiveStart(course, sectionTimes) != null ||
        effectiveEnd(course, sectionTimes) != null ||
        course.beginSection != null ||
        course.endSection != null

private fun mergeCourses(left: CourseClass, right: CourseClass): CourseClass =
    left.copy(
        courseName = preferred(left.courseName, right.courseName) ?: left.courseName,
        courseSerialNo = preferred(left.courseSerialNo, right.courseSerialNo),
        credit = preferred(left.credit, right.credit),
        beginTime = preferred(left.beginTime, right.beginTime),
        endTime = preferred(left.endTime, right.endTime),
        beginSection = left.beginSection ?: right.beginSection,
        endSection = left.endSection ?: right.endSection,
        placeName = preferredPlace(left.placeName, right.placeName),
        weeksAndTeachers = preferred(left.weeksAndTeachers, right.weeksAndTeachers),
        teachingTarget = preferred(left.teachingTarget, right.teachingTarget),
        color = preferred(left.color, right.color),
    )

internal fun normalizeWeeklySchedule(schedule: WeeklySchedule): WeeklySchedule =
    schedule.copy(
        arrangedList = normalizeCourseClasses(schedule.arrangedList, schedule.sectionTimes)
    )

private fun effectiveStart(course: CourseClass, sectionTimes: List<SectionTime>): String? =
    known(course.beginTime)
        ?: course.beginSection?.let { section ->
          sectionTimes.firstOrNull { it.section == section }?.start
        }

private fun effectiveEnd(course: CourseClass, sectionTimes: List<SectionTime>): String? =
    known(course.endTime)
        ?: course.endSection?.let { section ->
          sectionTimes.firstOrNull { it.section == section }?.end
        }

private fun compatiblePlaces(left: String?, right: String?): Boolean {
  val leftPlace = known(left) ?: return true
  val rightPlace = known(right) ?: return true
  val a = placeInfo(leftPlace)
  val b = placeInfo(rightPlace)
  if (a == null || b == null) return normalizePlace(leftPlace) == normalizePlace(rightPlace)
  if (a.building != b.building) return false
  if (a.room != null && b.room != null && a.room != b.room) return false
  if (a.residence != null && b.residence != null && a.residence != b.residence) return false
  return a.otherDetails.isEmpty() || b.otherDetails.isEmpty() || a.otherDetails == b.otherDetails
}

private fun preferredPlace(left: String?, right: String?): String? {
  val a = known(left) ?: return known(right)
  val b = known(right) ?: return a
  val first = placeInfo(a)
  val second = placeInfo(b)
  val firstSpecificity = first?.specificity ?: 0
  val secondSpecificity = second?.specificity ?: 0
  return when {
    firstSpecificity > secondSpecificity -> a
    secondSpecificity > firstSpecificity -> b
    a.trim() <= b.trim() -> a
    else -> b
  }
}

private data class PlaceInfo(
    val building: String,
    val room: String?,
    val residence: String?,
    val otherDetails: String,
) {
  val specificity: Int
    get() =
        (if (room != null) 4 else 0) +
            (if (residence != null) 2 else 0) +
            (if (otherDetails.isNotEmpty()) 1 else 0)
}

private val roomPattern = Regex("""(?i)[A-Z]\d{0,2}-\d{2,}[A-Z]?|[A-Z]\d{2,}[A-Z]?|\d{3,}[A-Z]?""")
private val residencePattern = Regex("学生\\s*\\d+\\s*公寓")
private val bracketPattern = Regex("[（(【\\[\\]）)】]")
private val placeSeparatorPattern = Regex("[\\s,，;；:：|丨·。/\\\\_-]+")

private fun placeInfo(raw: String?): PlaceInfo? {
  val value = known(raw)?.replace('　', ' ') ?: return null
  val baseEnd =
      listOf('（', '(', '【', '[').map { value.indexOf(it) }.filter { it >= 0 }.minOrNull()
          ?: value.length
  val base = value.substring(0, baseEnd)
  val roomMatch =
      roomPattern.findAll(value).firstOrNull { match ->
        !isEmbeddedRoomToken(value, match.range.first, match.range.last)
      }
  val room = roomMatch?.value?.uppercase()
  val residence = residencePattern.find(value)?.value?.replace(" ", "")
  val building =
      normalizePlace(
          if (roomMatch != null && roomMatch.range.last < baseEnd)
              base.removeRange(roomMatch.range.first, roomMatch.range.last + 1)
          else base
      )
  if (building.isEmpty()) return null
  val details = value.substring(baseEnd)
  val detailsWithoutRoom =
      if (roomMatch != null && roomMatch.range.first >= baseEnd)
          details.removeRange(roomMatch.range.first - baseEnd, roomMatch.range.last + 1 - baseEnd)
      else details
  val otherDetails =
      normalizePlace(detailsWithoutRoom.replace(residencePattern, "").replace(bracketPattern, ""))
  return PlaceInfo(building, room, residence, otherDetails)
}

private fun isEmbeddedRoomToken(source: String, start: Int, end: Int): Boolean {
  val before = source.getOrNull(start - 1)
  val after = source.getOrNull(end + 1)
  return before.isAsciiAlphaNumeric() ||
      before == '-' ||
      after.isAsciiAlphaNumeric() ||
      after == '-'
}

private fun Char?.isAsciiAlphaNumeric(): Boolean =
    this != null && (this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9')

private fun normalizePlace(value: String): String =
    value.lowercase().replace(placeSeparatorPattern, "").trim()

private fun known(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)

private fun knownConflict(left: String?, right: String?): Boolean =
    known(left) != null && known(right) != null && known(left) != known(right)

private fun preferred(left: String?, right: String?): String? =
    when {
      known(left) == null -> known(right)
      known(right) == null -> known(left)
      known(left) == known(right) -> known(left)
      known(left)!! <= known(right)!! -> known(left)
      else -> known(right)
    }

private fun courseSortKey(course: CourseClass): String =
    listOf(
            course.dayOfWeek,
            course.beginTime,
            course.beginSection,
            course.courseCode,
            course.courseSerialNo,
            course.teachingTarget,
            course.weeksAndTeachers,
            course.endTime,
            course.endSection,
            course.placeName,
            course.courseName,
        )
        .joinToString("\u0000") { it?.toString().orEmpty() }
