package app.bucc.homework

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.repository.ScheduleSnapshotStorage
import app.bucc.repository.ScheduleStore
import com.russhwolf.settings.Settings
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class SavedHomeworkCookie(val cookie: Cookie, val createdAt: Long)

/** Separate service sessions are isolated by campus account and survive application restarts. */
internal class HomeworkCookies(
    private val account: String,
    private val source: HomeworkSource,
    private val settings: Settings = ScheduleStore.settings,
) : CookiesStorage {
  private val mode = ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT
  private val key = homeworkSessionKey(account, source, mode)
  private val storage = ScheduleSnapshotStorage(settings, "homework_session")
  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }

  private fun read() =
      runCatching {
            storage.read(key)?.let { json.decodeFromString<List<SavedHomeworkCookie>>(it) }
          }
          .getOrNull()
          .orEmpty()

  private fun save(cookies: List<SavedHomeworkCookie>) =
      storage.write(key, json.encodeToString(cookies))

  override suspend fun addCookie(requestUrl: Url, cookie: Cookie) =
      mutex.withLock {
        if (source == HomeworkSource.ACCODING && ConnectionRuntime.currentMode() != mode)
            return@withLock
        val normalized =
            cookie.copy(
                domain = cookie.domain ?: requestUrl.host,
                path =
                    cookie.path ?: requestUrl.encodedPath.substringBeforeLast('/').ifBlank { "/" },
            )
        val remaining =
            read().filterNot {
              it.cookie.name == normalized.name &&
                  it.cookie.domain == normalized.domain &&
                  it.cookie.path == normalized.path
            }
        save(
            if (normalized.maxAge == 0) remaining
            else
                remaining +
                    SavedHomeworkCookie(normalized, Clock.System.now().toEpochMilliseconds())
        )
      }

  override suspend fun get(requestUrl: Url): List<Cookie> =
      mutex.withLock {
        if (source == HomeworkSource.ACCODING && ConnectionRuntime.currentMode() != mode)
            return@withLock emptyList()
        val now = Clock.System.now().toEpochMilliseconds()
        val active =
            read().filter { record ->
              val c = record.cookie
              (c.expires?.timestamp?.let { it > now } != false) &&
                  (c.maxAge?.takeIf { it >= 0 }?.let { now < record.createdAt + it * 1000L } !=
                      false)
            }
        save(active)
        val ownCookies =
            active
                .map { it.cookie }
                .filter { c ->
                  val domain = c.domain.orEmpty().trimStart('.').lowercase()
                  val path = c.path ?: "/"
                  (requestUrl.host.lowercase() == domain ||
                      requestUrl.host.lowercase().endsWith(".$domain")) &&
                      (requestUrl.encodedPath == path ||
                          requestUrl.encodedPath.startsWith(
                              if (path.endsWith('/')) path else "$path/"
                          )) &&
                      (!c.secure || requestUrl.protocol.name == "https")
                }
        val originalHost =
            runCatching { Url(LocalWebVpnSupport.fromWebVpnUrl(requestUrl.toString())).host }
                .getOrNull()
        val gatewayCookies =
            if (
                source == HomeworkSource.ACCODING &&
                    requestUrl.host == "d.buaa.edu.cn" &&
                    originalHost == "accoding.buaa.edu.cn" &&
                    LocalAuthSessionStore.get()?.username == account
            ) {
              // Share only the gateway's root authentication, never another upstream's cookies.
              LocalCookieStore.storage(mode).get(Url("https://d.buaa.edu.cn/")).filter {
                it.domain.orEmpty().trimStart('.').equals("d.buaa.edu.cn", ignoreCase = true) &&
                    it.path == "/"
              }
            } else emptyList()
        val gatewayNames = gatewayCookies.map { it.name }.toSet()
        gatewayCookies + ownCookies.filterNot { it.name in gatewayNames }
      }

  override fun close() {}
}

private fun homeworkSessionKey(
    account: String,
    source: HomeworkSource,
    mode: ConnectionMode,
): String =
    "${account}_${source.name}" +
        if (source == HomeworkSource.ACCODING && mode != ConnectionMode.DIRECT)
            "_${mode.storageKey}"
        else ""

internal fun homeworkHasSavedSession(
    account: String,
    source: HomeworkSource,
    settings: Settings = ScheduleStore.settings,
): Boolean {
  val storage = ScheduleSnapshotStorage(settings, "homework_session")
  val json = Json { ignoreUnknownKeys = true }
  val records =
      runCatching {
            storage
                .read(
                    homeworkSessionKey(
                        account,
                        source,
                        ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT,
                    )
                )
                ?.let { json.decodeFromString<List<SavedHomeworkCookie>>(it) }
          }
          .getOrNull()
          .orEmpty()
  val now = Clock.System.now().toEpochMilliseconds()
  return records.any { record ->
    val cookie = record.cookie
    (cookie.expires?.timestamp?.let { it > now } != false) &&
        (cookie.maxAge?.takeIf { it >= 0 }?.let { now < record.createdAt + it * 1000L } != false)
  }
}
