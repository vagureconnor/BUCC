package app.bucc.ui.screens.grade

import androidx.compose.runtime.Composable
import app.bucc.model.dto.Grade

@Composable fun GradeScreen(viewModel: GradeViewModel) = GpaDashboard(viewModel)

internal data class GradeDetailRow(val label: String, val value: String)

internal fun gradeDetailRows(grade: Grade): List<GradeDetailRow> =
    listOf(
            "课程号" to grade.courseCode,
            "学分" to grade.credit?.let(::formatNumber),
            "课程属性" to grade.courseAttribute,
            "课程类别" to (grade.courseCategory ?: grade.courseGroup),
            "考试性质" to grade.examType,
            "考试类型" to grade.examAttempt,
            "成绩类型" to grade.recognitionType,
        )
        .mapNotNull { (label, value) ->
          value?.takeIf { it.isNotBlank() }?.let { GradeDetailRow(label, it) }
        }

private fun formatNumber(value: Double): String {
  val rounded = kotlin.math.round(value * 100.0) / 100.0
  return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

internal fun formatGradePoint(value: Double): String {
  val rounded = kotlin.math.round(value * 100.0).toLong()
  val integerPart = rounded / 100
  val decimalPart = kotlin.math.abs(rounded % 100)
  return "$integerPart.${decimalPart.toString().padStart(2, '0')}"
}

internal data class GradeStatistics(
    val courseCount: Int,
    val totalCredits: Double,
    val gpa: Double?,
    val weightedAverage: Double?,
    val arithmeticAverage: Double?,
)

internal fun calculateGradeStatistics(grades: List<Grade>): GradeStatistics {
  var gpaWeightedTotal = 0.0
  var gpaCreditTotal = 0.0
  var scoreWeightedTotal = 0.0
  var scoreCreditTotal = 0.0
  var arithmeticScoreTotal = 0.0
  var arithmeticScoreCount = 0

  grades.forEach { grade ->
    val credit = grade.credit?.takeIf { it > 0.0 } ?: return@forEach
    val score = grade.score?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
    val points = score.toGradePoint() ?: return@forEach
    val numericScore = score.toWeightedAverageScore() ?: return@forEach

    gpaWeightedTotal += points * credit
    gpaCreditTotal += credit
    scoreWeightedTotal += numericScore * credit
    scoreCreditTotal += credit
    arithmeticScoreTotal += numericScore
    arithmeticScoreCount += 1
  }

  return GradeStatistics(
      courseCount = grades.size,
      totalCredits = grades.mapNotNull { it.credit?.takeIf { credit -> credit > 0.0 } }.sum(),
      gpa = weightedValue(gpaWeightedTotal, gpaCreditTotal),
      weightedAverage = weightedValue(scoreWeightedTotal, scoreCreditTotal),
      arithmeticAverage = averageValue(arithmeticScoreTotal, arithmeticScoreCount),
  )
}

private fun weightedValue(total: Double, credits: Double): Double? =
    if (credits > 0.0) kotlin.math.round((total / credits) * 100.0) / 100.0 else null

private fun averageValue(total: Double, count: Int): Double? =
    if (count > 0) kotlin.math.round((total / count) * 100.0) / 100.0 else null

private fun String.toGradePoint(): Double? {
  val normalizedScore = normalizedLevelScore()
  return when (normalizedScore) {
    "优" -> 4.0
    "良" -> 3.5
    "中" -> 2.8
    "及格" -> 1.7
    "不及格" -> 0.0
    "通过",
    "不通过" -> null
    else -> {
      val numericScore = normalizedScore.toDoubleOrNull() ?: return null
      if (numericScore < 60.0) 0.0
      else 4.0 - (3.0 * (100.0 - numericScore) * (100.0 - numericScore) / 1600.0)
    }
  }
}

private fun String.toWeightedAverageScore(): Double? {
  return when (normalizedLevelScore()) {
    "优" -> 90.0
    "良" -> 80.0
    "中" -> 70.0
    "及格" -> 60.0
    "不及格" -> 0.0
    "通过",
    "不通过" -> null
    else -> toDoubleOrNull()
  }
}

private fun String.normalizedLevelScore(): String =
    when (this) {
      "优秀" -> "优"
      "良好" -> "良"
      "中等" -> "中"
      else -> this
    }
