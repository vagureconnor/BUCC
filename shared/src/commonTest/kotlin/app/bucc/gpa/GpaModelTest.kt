package app.bucc.gpa

import app.bucc.model.dto.Grade
import com.russhwolf.settings.MapSettings
import kotlin.test.*

class GpaModelTest {
  private fun grade(
      name: String,
      score: String,
      credit: Double = 2.0,
      term: String = "2026-2027-1",
      code: String? = null,
  ) = Grade(courseName = name, score = score, credit = credit, termCode = term, courseCode = code)

  @Test
  fun formulaBoundariesAndPassFailOmission() {
    assertEquals(0.0, percentageGpa(59.0))
    assertEquals(1.0, percentageGpa(60.0))
    assertEquals(4.0, percentageGpa(100.0))
    assertEquals(4.0, percentageGpa(110.0))
    val result =
        summarizeGpa(gpaCourses(listOf(grade("一", "优秀"), grade("二", "80"), grade("三", "通过"))))
    assertEquals(2, result.count)
    assertEquals(4.0, result.credits)
    assertEquals(3.625, result.gpa)
    assertEquals(85.0, result.weightedScore)
  }

  @Test
  fun latestRetakeAndEnglishPartsRemainIndependent() {
    val courses =
        recommendationCourses(
            listOf(
                grade("课", "70", term = "2025-2026-1", code = "x"),
                grade("课", "90", code = "x"),
                grade("英语阅读（3）", "80", term = "2025-2026-2"),
                grade("英语阅读（3）", "90"),
            )
        )
    assertEquals(3, courses.size)
    assertEquals("90", courses.first { it.key == "x" }.grade.score)
    assertEquals(2, courses.count { it.grade.courseName!!.startsWith("英语") })
  }

  @Test
  fun softwarePresetMatchesCodeAndCanBeOverriddenPerCollegeAndAccount() {
    val course =
        recommendationCourses(listOf(grade("概率统计A", "90", 9.0, code = "B090011018"))).single()
    assertEquals(3.0, course.credit)
    assertTrue(GpaCollegeProfile("软件学院").includes(course))
    assertFalse(GpaCollegeProfile("计算机学院").includes(course))
    val custom = GpaCollegeProfile("计算机学院", overrides = mapOf(course.key to true))
    assertTrue(custom.includes(course))
    val settings = MapSettings()
    val value = GpaCollegeSettings("计算机学院", listOf(GpaCollegeProfile("软件学院"), custom))
    GpaCollegeStore("A", settings).write(value)
    assertEquals(value, GpaCollegeStore("A", settings).read())
    assertEquals("软件学院", GpaCollegeStore("B", settings).read().selected)
  }
}
