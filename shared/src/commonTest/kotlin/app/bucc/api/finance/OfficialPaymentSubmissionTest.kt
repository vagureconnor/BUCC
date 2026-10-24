package app.bucc.api.finance

import io.ktor.http.parseQueryString
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class OfficialPaymentSubmissionTest {
  private val fields =
      linkedMapOf(
          "_csrf-synthetic" to "synthetic-csrf",
          "OneCardForm[cardNo]" to "synthetic-card",
          "OneCardForm[productId]" to "synthetic-product",
          "OneCardForm[amount]" to "1.00",
          "OneCardForm[verifyCode]" to "synthetic code+&",
      )

  @Test
  fun `form submission stays private and is consumed once even across concurrent mounts`() =
      runTest {
        val page = OfficialPaymentPage.zfwFormSubmission(fields)
        assertTrue(page.isFormSubmission)
        assertEquals("https://zfw.buaa.edu.cn/pay/card", page.url)
        val requests = (1..5).map { async { page.takeFormPost() } }.awaitAll().filterNotNull()
        assertEquals(1, requests.size)
        val parsed = parseQueryString(requests.single().decodeToString())
        fields.forEach { (key, value) -> assertEquals(value, parsed[key]) }
        assertNull(page.takeFormPost())
        assertFalse(page.toString().contains("synthetic"))
      }

  @Test
  fun `VPN form handoff posts to the direct ZFW origin`() = runTest {
    val previous = app.bucc.api.ConnectionModeStore.settings
    app.bucc.api.ConnectionModeStore.settings = com.russhwolf.settings.MapSettings()
    try {
      app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
      val page = OfficialPaymentPage.zfwFormSubmission(fields)
      assertEquals("https://zfw.buaa.edu.cn/pay/card", page.url)
      assertNotNull(page.takeFormPost())
      assertNull(page.takeFormPost())
    } finally {
      app.bucc.api.ConnectionModeStore.settings = previous
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun `card payment navigation never contains a form body`() = runTest {
    val page = OfficialPaymentPage("https://cashier.cc-pay.cn/cashier?order=synthetic")
    assertFalse(page.isFormSubmission)
    assertNull(page.takeFormPost())
    assertFalse(page.toString().contains("synthetic"))
  }

  @Test
  fun `form handoff rejects extra fields or missing authentication fields`() {
    assertFailsWith<IllegalArgumentException> {
      OfficialPaymentPage.zfwFormSubmission(fields + ("unapproved" to "synthetic"))
    }
    assertFailsWith<IllegalArgumentException> {
      OfficialPaymentPage.zfwFormSubmission(fields - "_csrf-synthetic")
    }
    assertFailsWith<IllegalArgumentException> {
      OfficialPaymentPage.zfwFormSubmission(fields + ("OneCardForm[verifyCode]" to ""))
    }
  }

  @Test
  fun `controller allows an explicit fresh prepare after an ambiguous create without auto retry`() =
      runTest {
        var creates = 0
        var prepares = 0
        var ambiguous = true
        val backend =
            object : CampusRechargeBackend {
              override suspend fun prepare(kind: RechargeKind): RechargePreparation {
                prepares++
                return RechargePreparation(true)
              }

              override suspend fun createOrder(
                  kind: RechargeKind,
                  amount: String,
                  verificationCode: String?,
              ): OfficialPaymentPage {
                creates++
                if (ambiguous) error("synthetic unknown result")
                return OfficialPaymentPage.zfwFormSubmission(fields)
              }
            }
        val controller = CampusRechargeController(backend)
        controller.prepare(RechargeKind.Internet)
        controller.create("1", "synthetic")
        assertTrue(controller.state is CampusRechargeState.Failed)
        assertTrue(controller.canPrepare)
        assertEquals(1, creates)

        controller.prepare(RechargeKind.Internet)
        assertEquals(2, prepares)
        assertEquals(1, creates, "A fresh prepare must not submit an order by itself")
        ambiguous = false
        controller.create("1", "synthetic")
        assertTrue(controller.state is CampusRechargeState.OfficialPage)
        controller.create("1", "synthetic")
        assertEquals(2, creates)
        assertFalse(controller.canSubmit)
      }
}
