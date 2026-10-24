package app.bucc.ui.screens.homework

import app.bucc.homework.HomeworkAdapter
import app.bucc.homework.HomeworkItem
import app.bucc.homework.HomeworkRepository
import app.bucc.homework.HomeworkSource
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class HomeworkReminderMonitorTest {
  @Test
  fun firstCheckSeedsExistingAssignmentsAndLaterCheckFindsOnlyNewItems() = runTest {
    var assignments = listOf(homework("old"))
    val adapter =
        object : HomeworkAdapter {
          override val source = HomeworkSource.SPOC

          override suspend fun load() = assignments
        }
    val repository = HomeworkRepository("reminder-test", listOf(adapter), MapSettings(), { true })
    val monitor = HomeworkReminderMonitor("reminder-test", repository, MapSettings())

    assertEquals(emptyList(), monitor.check())
    assignments = assignments + homework("new")
    assertEquals(listOf("new"), monitor.check().map { it.id })
    assertEquals(emptyList(), monitor.check())
  }

  private fun homework(id: String) =
      HomeworkItem(
          id = id,
          source = HomeworkSource.SPOC,
          upstreamId = id,
          title = id,
      )
}
