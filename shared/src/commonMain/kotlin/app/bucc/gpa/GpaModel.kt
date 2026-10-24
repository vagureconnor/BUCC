package app.bucc.gpa

import app.bucc.model.dto.Grade
import kotlin.math.pow
import kotlin.math.round

/** Calculation and matching rules ported from the user-provided BUAA GPA project. */
data class CurriculumCourse(
    val code: String,
    val name: String,
    val credit: Double,
    val module: String,
    val group: String?,
    val basicGroup: String?,
    val passOnly: Boolean = false,
    val elective: Boolean = false,
)

data class GpaCourse(
    val key: String,
    val grade: Grade,
    val credit: Double,
    val points: Double?,
    val numericScore: Double?,
    val curriculum: CurriculumCourse? = null,
)

data class GpaSummary(
    val credits: Double,
    val count: Int,
    val gpa: Double?,
    val arithmeticScore: Double?,
    val weightedScore: Double?,
)

data class GpaTermPoint(val termCode: String, val label: String, val summary: GpaSummary)

private fun normalizeName(raw: String): String = buildString {
  raw.forEach { c ->
    val normalized = if (c in '\uFF01'..'\uFF5E') (c.code - 0xFEE0).toChar() else c
    if (!normalized.isWhitespace() && normalized !in "()（）·・") append(normalized.lowercaseChar())
  }
}

private val englishParts = Regex("英语.*(阅读|写作|口语)|(阅读|写作|口语).*英语")

fun percentageGpa(score: Double): Double =
    when {
      score < 60 -> 0.0
      score > 100 -> 4.0
      else -> 4 - 3 * (100 - score).pow(2) / 1600
    }

private fun scores(text: String): Pair<Double?, Double?> {
  text
      .toDoubleOrNull()
      ?.takeIf { it.isFinite() }
      ?.let {
        return percentageGpa(it) to it.coerceIn(0.0, 100.0)
      }
  return when (text.trim()) {
    "优",
    "优秀" -> 4.0 to 90.0
    "良",
    "良好" -> 3.5 to 80.0
    "中",
    "中等" -> 2.8 to 70.0
    "及格" -> 1.7 to 60.0
    "不及格" -> 0.0 to 0.0
    else -> null to null
  }
}

fun gpaCourses(grades: List<Grade>): List<GpaCourse> =
    grades.mapNotNull { grade ->
      val name = grade.courseName?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val score = grade.score?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val normalized = normalizeName(name)
      val english = englishParts.containsMatchIn(normalized)
      val curriculum =
          if (english) null
          else
              softwareCurriculum.firstOrNull { it.code == grade.courseCode && it.code.isNotBlank() }
                  ?: softwareCurriculum.firstOrNull {
                    normalizeName(it.name) == normalized && it.group != "F"
                  }
      val key =
          if (english) "${grade.termCode}:$normalized"
          else grade.courseCode?.takeIf { it.isNotBlank() } ?: normalized
      val (points, numeric) = scores(score)
      GpaCourse(
          key,
          grade,
          grade.credit?.takeIf { it > 0 } ?: curriculum?.credit ?: 0.0,
          points,
          numeric,
          curriculum,
      )
    }

/** Ordinary retakes use the latest semester; English parts remain independent per semester. */
fun recommendationCourses(grades: List<Grade>, plan: String = "A"): List<GpaCourse> =
    gpaCourses(grades)
        .sortedByDescending { it.grade.termCode.orEmpty() }
        .distinctBy { it.key }
        .map { row ->
          val normalized = normalizeName(row.grade.courseName.orEmpty())
          val curriculum =
              softwareCurriculum
                  .filter {
                    it.basicGroup == null || it.basicGroup == "AB" || it.basicGroup == plan
                  }
                  .firstOrNull { it.code == row.grade.courseCode && it.code.isNotBlank() }
                  ?: softwareCurriculum.firstOrNull {
                    (it.basicGroup == null || it.basicGroup == "AB" || it.basicGroup == plan) &&
                        it.group != "F" &&
                        normalizeName(it.name) == normalized
                  }
          row.copy(
              credit = curriculum?.credit ?: row.credit,
              points = if (curriculum?.passOnly == true) null else row.points,
              numericScore = if (curriculum?.passOnly == true) null else row.numericScore,
              curriculum = curriculum,
          )
        }

fun isSoftwareRecommendation(row: GpaCourse): Boolean =
    row.curriculum != null ||
        englishParts.containsMatchIn(normalizeName(row.grade.courseName.orEmpty()))

fun summarizeGpa(rows: List<GpaCourse>): GpaSummary {
  val graded = rows.filter { it.points != null && it.credit.isFinite() && it.credit > 0 }
  val credits = graded.sumOf { it.credit }
  val scored = graded.filter { it.numericScore != null }
  val scoreCredits = scored.sumOf { it.credit }
  fun rounded(value: Double) = round(value * 1000) / 1000
  return GpaSummary(
      credits,
      graded.size,
      if (credits > 0) rounded(graded.sumOf { it.points!! * it.credit } / credits) else null,
      if (scored.isNotEmpty()) rounded(scored.sumOf { it.numericScore!! } / scored.size) else null,
      if (scoreCredits > 0) rounded(scored.sumOf { it.numericScore!! * it.credit } / scoreCredits)
      else null,
  )
}

fun cumulativeGpa(rows: List<GpaCourse>): List<GpaTermPoint> =
    rows
        .mapNotNull { it.grade.termCode }
        .distinct()
        .sorted()
        .map { code ->
          val first = rows.first { it.grade.termCode == code }
          GpaTermPoint(
              code,
              first.grade.termName ?: code,
              summarizeGpa(rows.filter { it.grade.termCode.orEmpty() <= code }),
          )
        }
