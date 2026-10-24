package app.bucc.connection

import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhysicalCampusProxyTest {
  private class Host(private val response: String) : PhysicalCampusHelper {
    var calls = 0
    var closes = 0

    override suspend fun exchange(request: String): String {
      assertEquals("{\"op\":\"endpoint\"}", request)
      calls++
      return response
    }

    override suspend fun close() {
      closes++
    }
  }

  @Test
  fun `campus clients share one helper and last close releases it`() = runTest {
    val host = Host("""{"status":"READY","port":19001}""")
    var creations = 0
    val pool =
        PhysicalCampusProxyPool(
            {
              creations++
              host
            },
            { true },
            this,
        )
    val first = pool.acquire()
    val second = pool.acquire()
    assertEquals(Proxy.Type.HTTP, first.select("sso.buaa.edu.cn")?.type())
    assertEquals(Proxy.Type.HTTP, second.select("uc.buaa.edu.cn")?.type())
    assertEquals(1, creations)
    first.close()
    first.close()
    advanceUntilIdle()
    assertEquals(0, host.closes)
    second.close()
    advanceUntilIdle()
    assertEquals(1, host.closes)
    assertNull(second.select("uc.buaa.edu.cn"))
  }

  @Test
  fun `noncampus and loopback hosts never reach the helper`() = runTest {
    val pool = PhysicalCampusProxyPool({ error("helper must not start") }, { true }, this)
    val lease = pool.acquire()
    for (name in
        listOf(
            "localhost",
            "127.0.0.1",
            "example.org",
            "buaa.edu.cn.invalid",
            "sso.buaa.edu.cn.invalid",
        )) {
      assertNull(lease.select(name))
    }
    lease.close()
    advanceUntilIdle()
  }

  @Test
  fun `off campus or invalid helper endpoint cannot replace ordinary routing`() = runTest {
    for (response in
        listOf(
            """{"status":"FAILED","errorCode":"physical_campus_unavailable"}""",
            """{"status":"READY","port":0}""",
            """{"status":"READY","port":65536}""",
        )) {
      val host = Host(response)
      val lease = PhysicalCampusProxyPool({ host }, { true }, this).acquire()
      assertNull(lease.select("sso.buaa.edu.cn"))
      lease.close()
      advanceUntilIdle()
      assertEquals(1, host.closes)
    }
  }
}
