package app.bucc.diagnostics

import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalDiagnosticsTest {
  @Test
  fun `endpoint retains only approved host and literal path`() {
    assertEquals(
        "spoc.buaa.edu.cn/spocnewht/inco/ht/add",
        diagnosticEndpoint(
            "https://spoc.buaa.edu.cn/spocnewht/inco/ht/add?token=synthetic-secret#openid=synthetic-id"
        ),
    )
    assertEquals(
        "mall.cc-pay.cn/entry/card/[redacted]",
        diagnosticEndpoint(
            "https://mall.cc-pay.cn/entry/card/synthetic-private-id?backUrl=%252Fsecret"
        ),
    )
    assertEquals(
        "m.icw.buaa.edu.cn/card/cardpay/openCardPay",
        diagnosticEndpoint("https://m.icw.buaa.edu.cn/card/cardpay/openCardPay?openid=synthetic"),
    )
    assertNull(diagnosticEndpoint("https://evil.test/?token=synthetic"))
    assertNull(diagnosticEndpoint("https://spoc.buaa.edu.cn/private/synthetic-account"))
    assertNull(diagnosticEndpoint("https://user:secret@spoc.buaa.edu.cn/spocnewht/inco/ht/add"))
  }

  @Test
  fun `VPN diagnostics reveal only the approved original route`() {
    val original = "https://zfw.buaa.edu.cn/pay/card?token=synthetic#synthetic"
    val wrapped = app.bucc.api.local.LocalWebVpnSupport.toWebVpnUrl(original)
    assertEquals("zfw.buaa.edu.cn/pay/card", diagnosticEndpoint(wrapped))
    assertNull(diagnosticEndpoint(wrapped.replace("https://d.buaa.edu.cn", "https://evil.test")))
    assertNull(
        diagnosticEndpoint(wrapped.replace("https://d.buaa.edu.cn", "https://d.buaa.edu.cn:8443"))
    )
  }

  @Test
  fun `store rotates by UTF8 bytes and keeps at most three files`() = runTest {
    val files = MemoryFiles()
    val store = DiagnosticStore(files, { 1000L }, maxBytes = 60)
    repeat(8) { store.append("{\"event\":\"中文中文中文\",\"index\":$it}") }
    assertEquals(setOf(0, 1, 2), files.values.keys)
    assertTrue(files.values.values.all { it.encodeToByteArray().size <= 60 })
    assertTrue(files.values[0]!!.contains("7"))
    assertFalse(files.values.values.joinToString().contains("\"index\":0"))
  }

  @Test
  fun `disabled storage produces no writes and retention removes stale segments`() = runTest {
    val files = MemoryFiles()
    files.enabled = false
    val store = DiagnosticStore(files, { 1000L }, retentionMillis = 100)
    store.append("{\"stage\":\"HTTP\"}")
    assertTrue(files.values.isEmpty())
    files.enabled = true
    files.values[1] = "stale"
    store.append("{\"stage\":\"FINISH\"}")
    assertFalse(files.values.values.contains("stale"))
  }

  @Test
  fun `nested encoded secrets and forged stack text in messages never enter diagnostics`() {
    val cause =
        IllegalArgumentException(
            "Cookie=synthetic-cookie password=synthetic-password\n at app.bucc.fake.synthetic_secret()"
        )
    val error =
        IllegalStateException("%257B%2522openid%2522%253A%2522synthetic-openid%2522%257D", cause)
    val encoded = Json.encodeToString(diagnosticFailure(error))
    listOf("synthetic-cookie", "synthetic-password", "synthetic_secret", "synthetic-openid", "%25")
        .forEach { assertFalse(encoded.contains(it)) }
    assertTrue(encoded.contains("IllegalStateException"))
    assertTrue(encoded.contains("IllegalArgumentException"))
    assertTrue(encoded.contains("app.bucc.diagnostics"))
    assertEquals(
        "spoc.buaa.edu.cn/spocnewht/inco/ht/add",
        diagnosticEndpoint(
            "https://spoc.buaa.edu.cn/spocnewht/inco/ht/add?param=%257B%2522password%2522%253A%2522synthetic%2522%257D#synthetic"
        ),
    )
  }

  @Test
  fun `concurrent records keep independent local correlation identifiers and bounded size`() =
      runTest {
        val files = MemoryFiles()
        val store = DiagnosticStore(files, { 1000L }, maxBytes = 1024 * 1024)
        val sessions =
            (1..40).map {
              LocalDiagnostics.session(DiagnosticModule.PAPER_PLANE, DiagnosticOperation.ONE_CLICK)
            }
        assertEquals(40, sessions.map { it.id }.toSet().size)
        sessions
            .map { session -> async { store.append("{\"correlationId\":\"${session.id}\"}") } }
            .awaitAll()
        assertEquals(40, files.values[0]!!.lineSequence().filter(String::isNotBlank).count())
        sessions.forEach { assertTrue(files.values[0]!!.contains(it.id)) }
        val tiny = MemoryFiles()
        DiagnosticStore(tiny, { 1000L }, maxBytes = 60).append("synthetic-oversize".repeat(100))
        assertFalse(tiny.values.values.joinToString().contains("synthetic-oversize"))
        assertTrue(tiny.values.values.joinToString().contains("TRUNCATED"))
      }

  @Test
  fun `rotating a segment cannot extend retention of its old events`() = runTest {
    val files = MemoryFiles()
    files.values[1] =
        "{\"timestampMillis\":800,\"stage\":\"OLD\"}\n{\"timestampMillis\":950,\"stage\":\"RECENT\"}\n"
    DiagnosticStore(files, { 1000L }, retentionMillis = 100).append("{\"timestampMillis\":1000}")
    assertFalse(files.values.values.joinToString().contains("OLD"))
    assertTrue(files.values.values.joinToString().contains("RECENT"))
  }

  private class MemoryFiles : DiagnosticFiles {
    val values = mutableMapOf<Int, String>()
    override var enabled = true

    override fun read(slot: Int) = values[slot]

    override fun write(slot: Int, value: String) {
      values[slot] = value
    }

    override fun delete(slot: Int) {
      values.remove(slot)
    }

    override fun modifiedAt(slot: Int) = values[slot]?.let { 0L }
  }
}
