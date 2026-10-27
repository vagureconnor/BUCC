package app.bucc.api

import app.bucc.api.local.*
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

/**
 * Opt-in, read-only diagnostic. Copies sessions to memory and never emits credentials or record
 * IDs.
 */
object LibraryReadProbe {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    val memory = MapSettings()
    val saved = Settings()
    saved.keys.forEach { key -> saved.getStringOrNull(key)?.let { memory.putString(key, it) } }
    ConnectionModeStore.settings = memory
    LocalAuthSessionStore.settings = memory
    LocalCookieStore.settings = memory
    app.bucc.api.storage.CredentialStore.settings = memory
    ConnectionModeStore.save(ConnectionMode.valueOf(args.firstOrNull() ?: "DIRECT"))
    ConnectionRuntime.resolveSelectedMode()
    LocalUpstreamClientProvider.reset()
    val originalFactory = LocalUpstreamClientProvider.libBookClientFactory
    LocalUpstreamClientProvider.libBookClientFactory = { redirects ->
      originalFactory(redirects).apply {
        plugin(HttpSend).intercept { request ->
          val url = Url(LocalWebVpnSupport.fromWebVpnUrl(request.url.buildString()))
          val readPaths =
              setOf(
                  "/v4/login/user",
                  "/v4/space/pcTopFor",
                  "/v4/space/pick",
                  "/v4/Space/map",
                  "/v4/Space/seat",
                  "/v4/member/seat",
              )
          check(request.method.value == "GET" || url.encodedPath in readPaths) {
            "Read-only endpoint restriction"
          }
          val call = execute(request)
          if (url.encodedPath == "/v4/member/seat") {
            val root =
                runCatching { Json.parseToJsonElement(call.response.bodyAsText()).jsonObject }
                    .getOrNull()
            val data = root?.get("data") as? JsonObject
            val rows = (data?.get("data") ?: data?.get("list")) as? JsonArray
            println("LIBRARY bookings code=" + root?.get("code") + " count=" + (rows?.size ?: 0))
            rows?.take(3)?.forEach { row ->
              val obj = row.jsonObject
              println(
                  "LIBRARY booking fieldNames=" +
                      obj.keys.sorted().joinToString(",") +
                      " status=" +
                      obj["status"]?.jsonPrimitive?.contentOrNull
              )
            }
          }
          call
        }
      }
    }
    try {
      println("LIBRARY savedSession=" + (LocalAuthSessionStore.get() != null))
      val account = app.bucc.api.storage.CredentialStore.getAccountId()
      val credential =
          account?.let { app.bucc.security.createPlatformCampusCredentialStorage().load(it) }
      if (credential != null) {
        val login =
            withTimeout(60000) {
              LocalAuthServiceBackend().login(credential.username, credential.password, null, null)
            }
        println("LIBRARY reauthentication=" + login.isSuccess)
      }
      val backend = LocalLibBookApiBackend()
      withTimeout(180000) {
        val bookings = backend.getBookings().getOrThrow()
        println(
            "LIBRARY mappedBookings=" +
                bookings.bookings.size +
                " blankIds=" +
                bookings.bookings.count { it.id.isBlank() }
        )
        println(
            "LIBRARY bookingStates=" +
                bookings.bookings
                    .map { it.status + ":" + it.statusName }
                    .distinct()
                    .joinToString(",")
        )
        if (System.getenv("BUCC_LIBRARY_BOOKINGS_ONLY") == "true") return@withTimeout
        val today = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date.toString()
        val libraries = backend.getLibraries(today).getOrThrow()
        println("LIBRARY buildings=" + libraries.size)
        for (library in libraries) {
          val areas = backend.getAreas(library.id, null, today).getOrThrow()
          for (area in areas.distinctBy { it.id }) {
            val detail = backend.getAreaDetail(area.id).getOrThrow()
            val day = detail.availableDates.firstOrNull { it >= today } ?: today
            val slot =
                detail.timeSlotsByDay[day]?.firstOrNull()
                    ?: detail.timeSlots.firstOrNull()
                    ?: continue
            val snapshot = backend.getSeatSnapshot(area.id, day, slot.start, slot.end).getOrThrow()
            val valid =
                snapshot.seats.count { s ->
                  s.pointX != null &&
                      s.pointY != null &&
                      s.width != null &&
                      s.height != null &&
                      s.width > 0 &&
                      s.height > 0 &&
                      s.pointX >= 0 &&
                      s.pointY >= 0 &&
                      s.pointX + s.width <= 100.01 &&
                      s.pointY + s.height <= 100.01
                }
            println(
                "LIBRARY area=" +
                    area.id +
                    " name=" +
                    area.name +
                    " seats=" +
                    snapshot.seats.size +
                    " validGeometry=" +
                    valid
            )
          }
        }
      }
    } catch (e: Exception) {
      println(
          "LIBRARY failure=" +
              e::class.simpleName +
              " code=" +
              (e as? app.bucc.api.auth.ApiCallException)?.code
      )
    } finally {
      LocalUpstreamClientProvider.reset()
    }
  }
}
