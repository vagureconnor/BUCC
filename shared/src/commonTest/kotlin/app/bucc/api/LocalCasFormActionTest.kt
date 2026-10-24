package app.bucc.api

import app.bucc.api.local.LocalCasParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalCasFormActionTest {
  private val page = "https://sso.buaa.edu.cn/login?service=uc"

  private fun form(action: String) =
      """<form action="$action"><input name="execution" value="fixture"></form>"""

  @Test
  fun relativeActionKeepsOriginAndService() {
    assertEquals(
        "https://sso.buaa.edu.cn/submit?service=uc&execution=new",
        LocalCasParser.loginFormUrl(form("/submit?service=uc&amp;execution=new"), page),
    )
    assertEquals(
        "https://sso.buaa.edu.cn/login?service=next",
        LocalCasParser.loginFormUrl(form("?service=next"), page),
    )
    assertEquals(page, LocalCasParser.loginFormUrl(form(""), page))
  }

  @Test
  fun credentialsCannotBePostedToAnotherOrigin() {
    assertFailsWith<IllegalArgumentException> {
      LocalCasParser.loginFormUrl(form("https://other.invalid/submit"), page)
    }
    assertFailsWith<IllegalArgumentException> {
      LocalCasParser.loginFormUrl(form("//other.invalid/submit"), page)
    }
  }
}
