package app.bucc.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.RateReview
import app.bucc.ui.icons.FeatureIcon
import app.bucc.ui.screens.menu.WorkbenchOrderStore
import app.bucc.ui.screens.menu.workbenchFeatureSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkbenchFeaturesTest {
  @Test
  fun `工作台保留iHome并提供TD与作业入口`() {
    val sections = workbenchFeatureSections()

    assertEquals(listOf("学习", "服务"), sections.map { it.title })
    assertEquals(15, sections.sumOf { it.features.size })
    assertEquals(
        listOf("schedule", "exam", "grade", "bykc", "attendance", "homework", "evaluation"),
        sections.first().features.map { it.id },
    )
    assertEquals(
        listOf("classroom", "libbook", "td", "cgyy", "ygdk", "ihome", "paper-plane", "finance"),
        sections.last().features.map { it.id },
    )
  }

  @Test
  fun `工作台入口唯一且不展示无操作的更多功能`() {
    val ids = workbenchFeatureSections().flatMap { section -> section.features.map { it.id } }

    assertEquals(ids.size, ids.toSet().size)
    assertFalse("more" in ids)
    assertTrue("evaluation" in ids)
    assertTrue("ihome" in ids)
    assertTrue("td" in ids)
  }

  @Test
  fun `作业中心与自动评教使用各自图标`() {
    val features = workbenchFeatureSections().flatMap { it.features }.associateBy { it.id }
    assertEquals(Icons.Default.AssignmentTurnedIn, features.getValue("homework").icon)
    assertEquals(Icons.Default.RateReview, features.getValue("evaluation").icon)
    assertEquals(FeatureIcon.CGYY.vector, features.getValue("cgyy").icon)
  }

  @Test
  fun `签到查询入口位于学习功能区`() {
    val attendance = workbenchFeatureSections().first().features.single { it.id == "attendance" }
    assertEquals("签到查询", attendance.title)
    assertTrue(attendance.description.contains("日期或课程"))
  }

  @Test
  fun `菜单顺序按账号保存且未知标识忽略`() {
    val settings = com.russhwolf.settings.MapSettings()
    app.bucc.repository.ScheduleStore.settings = settings
    val defaultIds = listOf("schedule", "exam", "grade")
    WorkbenchOrderStore.write("student-a", "学习", listOf("grade", "schedule", "invalid"))

    assertEquals(
        listOf("grade", "schedule", "exam"),
        WorkbenchOrderStore.read("student-a", "学习", defaultIds),
    )
    assertEquals(defaultIds, WorkbenchOrderStore.read("student-b", "学习", defaultIds))
  }
}
