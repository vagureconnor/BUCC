package app.bucc.homework

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class HomeworkSource(val label: String) {
  SPOC("SPOC"),
  JUDGE("Judge（希冀）"),
  ACCODING("Accoding"),
  PATPAT("Patpat"),
  BYXT("实验报告"),
}

@Serializable
enum class HomeworkSubmission {
  UNSUBMITTED,
  PARTIAL,
  SUBMITTED,
  UNKNOWN,
}

@Serializable
data class ReportUpload(
    val number: Int,
    val uploaded: Boolean,
    val filename: String? = null,
    val startAt: LocalDateTime? = null,
    val dueAt: LocalDateTime? = null,
)

@Serializable
data class HomeworkItem(
    val id: String,
    val source: HomeworkSource,
    val upstreamId: String,
    val title: String,
    val courseId: String = "",
    val courseName: String = "",
    val startAt: LocalDateTime? = null,
    val dueAt: LocalDateTime? = null,
    val makeupDueAt: LocalDateTime? = null,
    val submission: HomeworkSubmission = HomeworkSubmission.UNKNOWN,
    val submissionHint: String? = null,
    val reports: List<ReportUpload> = emptyList(),
    val experimentAt: LocalDateTime? = null,
    val url: String = "",
    val content: String = "",
    val canMarkComplete: Boolean = false,
    val locallyCompleted: Boolean = false,
) {
  val effectiveDueAt: LocalDateTime?
    get() = makeupDueAt ?: dueAt

  val hasUploadedReport: Boolean
    get() = source == HomeworkSource.BYXT && reports.any { it.uploaded }

  val completed: Boolean
    get() = locallyCompleted || submission == HomeworkSubmission.SUBMITTED || hasUploadedReport

  fun expired(now: LocalDateTime): Boolean =
      if (source == HomeworkSource.BYXT && completed) false
      else if (reports.isNotEmpty()) {
        reports
            .filterNot { it.uploaded }
            .let { missing ->
              missing.isNotEmpty() && missing.all { it.dueAt?.let { end -> end <= now } == true }
            }
      } else effectiveDueAt?.let { it <= now } == true

  fun overdue(now: LocalDateTime): Boolean =
      if (source == HomeworkSource.BYXT && completed) false
      else if (reports.isNotEmpty())
          reports.any { !it.uploaded && it.dueAt?.let { end -> end <= now } == true }
      else dueAt?.let { it <= now } == true

  fun pending(now: LocalDateTime): Boolean =
      !completed && !expired(now) && startAt?.let { it > now } != true

  fun showOnHome(now: LocalDateTime): Boolean =
      if (source == HomeworkSource.BYXT) !completed && !expired(now) else pending(now)
}

/** Compares title numeric chunks independently of their surrounding text. */
private val homeworkTitleNumberPattern = Regex("[0-9]+")

fun compareHomeworkTitlesNaturally(left: String, right: String): Int {
  fun chunks(title: String) =
      homeworkTitleNumberPattern
          .findAll(title)
          .map { it.value.trimStart('0').ifEmpty { "0" } }
          .toList()

  val leftChunks = chunks(left)
  val rightChunks = chunks(right)
  if (leftChunks.isEmpty() || rightChunks.isEmpty()) {
    return when {
      leftChunks.isEmpty() && rightChunks.isEmpty() -> 0
      leftChunks.isEmpty() -> 1
      else -> -1
    }
  }

  for (index in 0 until minOf(leftChunks.size, rightChunks.size)) {
    val leftNumber = leftChunks[index]
    val rightNumber = rightChunks[index]
    val order =
        leftNumber.length.compareTo(rightNumber.length).takeIf { it != 0 }
            ?: leftNumber.compareTo(rightNumber)
    if (order != 0) return order
  }
  return leftChunks.size.compareTo(rightChunks.size)
}

/** A partial source refresh must not discard previously loaded failed courses. */
internal class HomeworkPartialLoadException(
    val items: List<HomeworkItem>,
    val failedCourseIds: Set<String>,
    message: String,
) : IllegalStateException(message)

interface HomeworkAdapter {
  val source: HomeworkSource
  val requiresSeparateLogin: Boolean
    get() = false

  suspend fun load(): List<HomeworkItem>

  suspend fun detail(item: HomeworkItem): HomeworkItem = item

  suspend fun login(username: String, password: String) {
    error("此来源使用校园统一认证")
  }

  fun close() {}
}

fun parseHomeworkDate(raw: String?): LocalDateTime? {
  val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
  if (text.endsWith("Z") || Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(text)) {
    return runCatching {
          Instant.parse(text.replace(' ', 'T')).toLocalDateTime(TimeZone.of("+08:00"))
        }
        .getOrNull()
  }
  if (text.all { it.isDigit() } && text.length in listOf(10, 13)) {
    return runCatching {
          Instant.fromEpochMilliseconds(text.toLong() * if (text.length == 10) 1000 else 1)
              .toLocalDateTime(TimeZone.of("+08:00"))
        }
        .getOrNull()
  }
  val normalized = text.replace('/', '-').replace(' ', 'T').removeSuffix("Z")
  return runCatching { LocalDateTime.parse(normalized) }.getOrNull()
      ?: runCatching { LocalDateTime.parse(normalized.take(19)) }.getOrNull()
}
