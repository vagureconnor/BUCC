package app.bucc.api

import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.PersistentLocalCookieStorage
import app.bucc.api.local.StoredCookieRecord
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalCookieStoreTest {
  @BeforeTest
  fun setup() {
    ConnectionModeStore.settings = MapSettings()
    LocalCookieStore.settings = MapSettings()
    LocalCookieStore.clearAllScopes()
    ConnectionModeStore.clear()
  }

  @Test
  fun `cookies without explicit path use request directory as default path`() = runTest {
    val storage = PersistentLocalCookieStorage(ConnectionMode.DIRECT)

    storage.addCookie(
        Url("https://spoc.buaa.edu.cn/spocnewht/cas"),
        Cookie(
            name = "SESSION",
            value = "session-1",
            domain = "spoc.buaa.edu.cn",
        ),
    )

    val cookies = storage.get(Url("https://spoc.buaa.edu.cn/spocnewht/sys/casLogin"))

    assertEquals(listOf("SESSION"), cookies.map { it.name })
  }

  @Test
  fun `large cookie set is split and can be restored before adding another cookie`() = runTest {
    val backing = MapSettings()
    LocalCookieStore.settings = lengthLimited(backing)
    val url = Url("https://cookies.example/")
    val syntheticCookies =
        listOf(
            StoredCookieRecord(
                Cookie("source-one", "a".repeat(5000), domain = url.host, path = "/"),
                1L,
            ),
            StoredCookieRecord(
                Cookie("source-two", "b".repeat(5000), domain = url.host, path = "/"),
                2L,
            ),
        )
    assertTrue(Json.encodeToString(syntheticCookies).length > 8192)
    val storage = LocalCookieStore.storage(ConnectionMode.DIRECT)

    syntheticCookies.forEach { storage.addCookie(url, it.cookie) }

    val restored = LocalCookieStore.storage(ConnectionMode.DIRECT)
    assertEquals(setOf("source-one", "source-two"), restored.get(url).map { it.name }.toSet())
    restored.addCookie(url, Cookie("payment", "fixture", domain = url.host, path = "/"))
    assertEquals(3, LocalCookieStore.storage(ConnectionMode.DIRECT).get(url).size)
    assertTrue(backing.keys.count { it.startsWith("local_cookie_snapshots_part_") } > 1)
    assertTrue(backing.keys.all { backing.getString(it, "").length <= 8192 })
  }

  @Test
  fun `legacy cookie JSON is readable and migrates after a successful write`() = runTest {
    val backing = MapSettings()
    LocalCookieStore.settings = lengthLimited(backing)
    val url = Url("https://cookies.example/")
    val legacyKey =
        ModeScopedSessionStore.scopedKey("local_connection_cookies", ConnectionMode.DIRECT)
    backing.putString(
        legacyKey,
        Json.encodeToString(
            listOf(
                StoredCookieRecord(
                    Cookie("legacy", "fixture", domain = url.host, path = "/"),
                    1L,
                )
            )
        ),
    )

    val storage = LocalCookieStore.storage(ConnectionMode.DIRECT)
    assertEquals(listOf("legacy"), storage.get(url).map { it.name })
    storage.addCookie(url, Cookie("new", "fixture", domain = url.host, path = "/"))

    assertFalse(backing.hasKey(legacyKey))
    assertEquals(setOf("legacy", "new"), storage.get(url).map { it.name }.toSet())
  }

  @Test
  fun `cookie snapshots are isolated by mode and clear removes their parts`() = runTest {
    val backing = MapSettings()
    LocalCookieStore.settings = lengthLimited(backing)
    val url = Url("https://cookies.example/")
    LocalCookieStore.storage(ConnectionMode.DIRECT)
        .addCookie(url, Cookie("direct", "fixture", domain = url.host, path = "/"))
    LocalCookieStore.storage(ConnectionMode.WEBVPN)
        .addCookie(url, Cookie("webvpn", "fixture", domain = url.host, path = "/"))

    assertEquals(
        listOf("direct"),
        LocalCookieStore.storage(ConnectionMode.DIRECT).get(url).map { it.name },
    )
    assertEquals(
        listOf("webvpn"),
        LocalCookieStore.storage(ConnectionMode.WEBVPN).get(url).map { it.name },
    )

    val directIndex = backing.getString("local_cookie_snapshots_v2_direct", "")
    val directGeneration = directIndex.substringBefore(':')
    val webvpnGeneration =
        backing.getString("local_cookie_snapshots_v2_webvpn", "").substringBefore(':')
    assertTrue(directGeneration.isNotBlank())
    LocalCookieStore.clear(ConnectionMode.DIRECT)
    assertFalse(backing.hasKey("local_cookie_snapshots_v2_direct"))
    assertTrue(
        backing.keys.none { it.startsWith("local_cookie_snapshots_part_${directGeneration}_") }
    )
    assertTrue(
        backing.keys.any { it.startsWith("local_cookie_snapshots_part_${webvpnGeneration}_") }
    )
    assertTrue(LocalCookieStore.storage(ConnectionMode.DIRECT).get(url).isEmpty())
    assertEquals(
        listOf("webvpn"),
        LocalCookieStore.storage(ConnectionMode.WEBVPN).get(url).map { it.name },
    )
    assertFalse(backing.keys.any { it == "mode_direct_local_connection_cookies" })
  }

  @Test
  fun `concurrent clients preserve added cookies`() = runTest {
    val backing = MapSettings()
    LocalCookieStore.settings = lengthLimited(backing)
    val url = Url("https://cookies.example/")
    (0 until 80)
        .map { index ->
          async(Dispatchers.Default) {
            LocalCookieStore.storage(ConnectionMode.DIRECT)
                .addCookie(
                    url,
                    Cookie("cookie-$index", "fixture-$index", domain = url.host, path = "/"),
                )
          }
        }
        .awaitAll()

    assertEquals(80, LocalCookieStore.storage(ConnectionMode.DIRECT).get(url).size)
  }

  @Test
  fun `synchronous clear during cookie read cannot publish the stale snapshot again`() = runTest {
    val backing = MapSettings()
    LocalCookieStore.settings = backing
    val mode = ConnectionMode.DIRECT
    val url = Url("https://cookies.example/")
    LocalCookieStore.storage(mode)
        .addCookie(url, Cookie("old", "fixture", domain = url.host, path = "/"))
    val indexKey = "local_cookie_snapshots_v2_${mode.storageKey}"
    var indexReads = 0
    var clearDuringRead = true
    LocalCookieStore.settings =
        object : Settings by backing {
          override fun getStringOrNull(key: String): String? {
            val value = backing.getStringOrNull(key)
            if (key == indexKey && clearDuringRead && ++indexReads == 2) {
              clearDuringRead = false
              LocalCookieStore.clear(mode)
            }
            return value
          }
        }

    assertTrue(LocalCookieStore.storage(mode).get(url).isEmpty())
    assertTrue(LocalCookieStore.storage(mode).get(url).isEmpty())
    assertFalse(backing.hasKey(indexKey))
    assertTrue(backing.keys.none { it.startsWith("local_cookie_snapshots_part_") })
  }

  @Test
  fun `clear after snapshot publication cleans stale data even when legacy removal fails`() =
      runTest {
        val backing = MapSettings()
        val mode = ConnectionMode.DIRECT
        val indexKey = "local_cookie_snapshots_v2_${mode.storageKey}"
        val legacyKey = ModeScopedSessionStore.scopedKey("local_connection_cookies", mode)
        var clearOnPublish = true
        var legacyRemovals = 0
        LocalCookieStore.settings =
            object : Settings by backing {
              override fun putString(key: String, value: String) {
                backing.putString(key, value)
                if (key == indexKey && clearOnPublish) {
                  clearOnPublish = false
                  try {
                    LocalCookieStore.clear(mode)
                  } finally {
                    // Model the snapshot index write completing after clear removed the old index.
                    backing.putString(key, value)
                  }
                }
              }

              override fun remove(key: String) {
                if (key == legacyKey) {
                  legacyRemovals++
                  if (legacyRemovals == 2) error("synthetic legacy removal failure")
                  if (legacyRemovals == 3) error("synthetic cleanup failure")
                }
                backing.remove(key)
              }
            }

        var failure: IllegalStateException? = null
        try {
          LocalCookieStore.storage(mode)
              .addCookie(
                  Url("https://cookies.example/"),
                  Cookie("new", "synthetic", domain = "cookies.example", path = "/"),
              )
        } catch (error: IllegalStateException) {
          failure = error
        }

        assertEquals("synthetic legacy removal failure", failure?.message)
        assertFalse(backing.hasKey(indexKey))
        assertFalse(backing.hasKey(legacyKey))
        assertTrue(backing.keys.none { it.startsWith("local_cookie_snapshots_part_") })
      }

  @Test
  fun `adding cookies recovers from a malformed snapshot index`() = runTest {
    val backing = MapSettings()
    val mode = ConnectionMode.DIRECT
    backing.putString("local_cookie_snapshots_v2_${mode.storageKey}", "broken-index")
    LocalCookieStore.settings = backing

    val storage = LocalCookieStore.storage(mode)
    storage.addCookie(
        Url("https://cookies.example/"),
        Cookie("recovered", "synthetic", domain = "cookies.example", path = "/"),
    )

    assertEquals(listOf("recovered"), storage.get(Url("https://cookies.example/")).map { it.name })
    assertFalse(
        backing.getString("local_cookie_snapshots_v2_${mode.storageKey}", "").contains("broken")
    )
  }

  private fun lengthLimited(backing: MapSettings): Settings =
      object : Settings by backing {
        override fun putString(key: String, value: String) {
          require(value.length <= 8192) { "Settings single value exceeded 8192 characters" }
          backing.putString(key, value)
        }
      }
}
