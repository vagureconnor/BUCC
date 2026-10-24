package app.bucc.api.local

import app.bucc.api.auth.ApiCallException
import app.bucc.api.feature.IhomeException
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalIhomeApiFailureTest {
  @Test
  fun `unauthenticated device session preserves status and hides source message`() {
    val mapped =
        mapIhomeFailure(
            ApiCallException(
                "sensitive URL with ticket=secret",
                status = HttpStatusCode.Unauthorized,
                code = "unauthenticated",
            )
        )

    assertEquals(HttpStatusCode.Unauthorized, mapped.status)
    assertEquals("ihome_session", mapped.code)
    assertTrue(mapped.message!!.contains("主账号登录状态"))
    assertTrue(mapped.message!!.contains("HTTP 401"))
    assertFalse(mapped.message!!.contains("secret"))
  }

  @Test
  fun `upstream errors expose a safe stage without source text`() {
    val mapped = mapIhomeFailure(IhomeException("ihome_upstream", "URL with token=secret"))

    assertEquals("ihome_upstream", mapped.code)
    assertTrue(mapped.message!!.contains("上游服务"))
    assertFalse(mapped.message!!.contains("secret"))
  }
}
