package app.bucc.ui

import app.bucc.api.finance.FinanceBalance
import app.bucc.api.plane.PaperPlanePointInfo
import app.bucc.api.plane.PaperPlanePointTasksResponse
import app.bucc.ui.screens.finance.retainFinanceValueOnRefresh
import app.bucc.ui.screens.menu.PaperPlaneSectionState
import app.bucc.ui.screens.menu.paperPlanePointsSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperPlaneScreenStateTest {
  @Test
  fun `finance refresh errors keep the previous value and expose the error`() {
    val balance =
        retainFinanceValueOnRefresh(
            refreshed = FinanceBalance(error = "portal unavailable"),
            previous = FinanceBalance(value = "12.34"),
        )

    assertEquals("12.34", balance.value)
    assertEquals("元", balance.unit)
    assertEquals("portal unavailable", balance.error)
  }

  @Test
  fun `finance refresh success replaces previous value`() {
    val balance =
        retainFinanceValueOnRefresh(
            refreshed = FinanceBalance(value = "23.45"),
            previous = FinanceBalance(value = "12.34", error = "old error"),
        )

    assertEquals(FinanceBalance(value = "23.45"), balance)
  }

  @Test
  fun `only numeric personal points are displayed`() {
    assertEquals(
        "8.5",
        paperPlanePointsSummary(
            PaperPlanePointTasksResponse(info = PaperPlanePointInfo(jf = " 8.5 "))
        ),
    )
    assertEquals(
        "—",
        paperPlanePointsSummary(
            PaperPlanePointTasksResponse(info = PaperPlanePointInfo(jf = "unknown"))
        ),
    )
    assertEquals("—", paperPlanePointsSummary(PaperPlanePointTasksResponse()))
  }

  @Test
  fun `refresh start clears stale error and keeps previous content`() {
    val previous = PaperPlaneSectionState(value = "12", loaded = true, error = "offline")

    val refreshing = previous.beginRefresh()

    assertTrue(refreshing.refreshing)
    assertEquals("12", refreshing.value)
    assertNull(refreshing.error)
  }

  @Test
  fun `successful refresh stores content and marks section loaded`() {
    val refreshed = PaperPlaneSectionState<String>().beginRefresh().completeRefresh("23")

    assertEquals("23", refreshed.value)
    assertTrue(refreshed.loaded)
    assertFalse(refreshed.refreshing)
    assertNull(refreshed.error)
  }
}
