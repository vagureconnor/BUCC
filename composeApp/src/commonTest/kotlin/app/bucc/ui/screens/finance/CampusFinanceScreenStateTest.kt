package app.bucc.ui.screens.finance

import app.bucc.api.finance.CampusNetworkTraffic
import app.bucc.api.finance.FinanceBalance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampusFinanceScreenStateTest {
  @Test
  fun `keeps last traffic data and exposes refresh error`() {
    val previous =
        CampusNetworkTraffic(
            used = "1.23 GB",
            freeRemaining = "2.34 GB",
            chargedRemaining = "3.45 GB",
        )

    val result = retainTrafficValueOnRefresh(CampusNetworkTraffic(error = "登录已失效"), previous)

    assertEquals("1.23 GB", result.used)
    assertEquals("2.34 GB", result.freeRemaining)
    assertEquals("3.45 GB", result.chargedRemaining)
    assertEquals("登录已失效", result.error)
  }

  @Test
  fun `does not invent traffic values when no previous data exists`() {
    val result = retainTrafficValueOnRefresh(CampusNetworkTraffic(error = "需要登录"), null)

    assertNull(result.used)
    assertNull(result.freeRemaining)
    assertNull(result.chargedRemaining)
    assertEquals("需要登录", result.error)
  }

  @Test
  fun `retains previous balance and exposes refresh failure`() {
    val previous = FinanceBalance(value = "12.30", unit = "元")

    val result = retainFinanceValueOnRefresh(FinanceBalance(error = "查询失败"), previous)

    assertEquals("12.30", result.value)
    assertEquals("元", result.unit)
    assertEquals("查询失败", result.error)
  }

  @Test
  fun `does not invent balance when first query fails`() {
    val result = retainFinanceValueOnRefresh(FinanceBalance(error = "需要登录"), null)

    assertNull(result.value)
    assertEquals("需要登录", result.error)
  }
}
