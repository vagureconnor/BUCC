package app.bucc.api

import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCgyyApiBackend
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.api.local.ensureLocalWebVpnSession
import app.bucc.homework.AccodingHomeworkAdapter
import app.bucc.repository.ScheduleStore
import app.bucc.td.TdHealthCloudClient
import app.bucc.td.TdHealthCloudCookies
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.Url
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Explicitly invoked read-only probe. Clone saved sessions in memory; never print identities or
 * secrets.
 */
object GatewaySavedSessionProbe {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    val saved = Settings()
    val memory = MapSettings()
    saved.keys.forEach { key -> saved.getStringOrNull(key)?.let { memory.putString(key, it) } }
    ConnectionModeStore.settings = memory
    LocalAuthSessionStore.settings = memory
    LocalCookieStore.settings = memory
    ScheduleStore.settings = memory
    ConnectionRuntime.resolveSelectedMode()
    val session = LocalAuthSessionStore.get()
    println("PROBE mode=${ConnectionRuntime.currentMode()} session=${session != null}")
    requireNotNull(session) { "Sign in to BUCC before running this read-only probe" }
    LocalUpstreamClientProvider.reset()
    fun trace(client: HttpClient): HttpClient =
        client.apply {
          plugin(HttpSend).intercept { request ->
            val target = Url(LocalWebVpnSupport.fromWebVpnUrl(request.url.buildString()))
            val endpoint =
                when (target.encodedPath) {
                  "/",
                  "/login",
                  "/user/portal",
                  "/api/users/me",
                  "/user/login",
                  "/venue-zhjs-server/sso/manageLogin",
                  "/venue-zhjs-server/api/login",
                  "/venue-zhjs-server/api/orders/mine" -> target.encodedPath
                  else -> if (target.host == "10.212.28.38") target.encodedPath else "other"
                }
            val label = "${request.method.value} ${request.url.host} -> ${target.host} $endpoint"
            check(
                request.method.value == "GET" ||
                    (request.method.value == "POST" &&
                        ((target.host == "cgyy.buaa.edu.cn" &&
                            target.encodedPath == "/venue-zhjs-server/api/login") ||
                            (target.host == "10.212.28.38" &&
                                target.encodedPath == "/api.php" &&
                                target.parameters["action"] == "getRecordDetail")))
            ) {
              "Only session continuation and read-only queries are allowed"
            }
            try {
              val call = execute(request)
              println("HTTP $label status=${call.response.status.value}")
              call
            } catch (failure: Throwable) {
              println("HTTP $label error=${failure::class.simpleName}")
              throw failure
            }
          }
        }
    val factory = LocalUpstreamClientProvider.clientFactory
    val isolated = LocalUpstreamClientProvider.isolatedClientFactory
    LocalUpstreamClientProvider.clientFactory = { trace(factory(it)) }
    LocalUpstreamClientProvider.isolatedClientFactory = { redirects, cookies ->
      trace(isolated(redirects, cookies))
    }
    val failed = mutableListOf<String>()
    suspend fun check(name: String, block: suspend () -> Unit) {
      try {
        block()
        println("RESULT $name OK")
      } catch (failure: Throwable) {
        failed += name
        println(
            "RESULT $name FAILED ${generateSequence(failure) { it.cause }.take(5).map { it::class.simpleName }.joinToString("/")}"
        )
      }
    }
    check("gateway") { ensureLocalWebVpnSession() }
    check("cgyy") {
      val result = LocalCgyyApiBackend().getMyOrders(0, 10).getOrThrow()
      println("CGYY resultRecords=${result.content.size}")
    }
    check("health-cloud") {
      val client =
          LocalUpstreamClientProvider.newClient(TdHealthCloudCookies(), followRedirects = false)
      try {
        TdHealthCloudClient(client)
            .queryTodayValidExitCount(
                session.username,
                "must-not-be-transmitted",
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
            )
      } finally {
        client.close()
      }
    }
    check("mac-python-health") {
      val root = Path.of("").toAbsolutePath()
      val process =
          ProcessBuilder(
                  "python3",
                  root
                      .resolve("shared/src/jvmTest/resources/gateway_health_read_probe.py")
                      .toString(),
              )
              .apply {
                environment()["PYTHONPATH"] =
                    root.resolve("composeApp/src/jvmMain/resources/td-backend/src").toString()
                redirectError(ProcessBuilder.Redirect.DISCARD)
              }
              .start()
      val payload = buildJsonObject {
        put("username", session.username)
        put(
            "cookies",
            buildJsonArray {
              LocalCookieStore.storage()
                  .get(Url("https://d.buaa.edu.cn/"))
                  .filter { it.path == "/" }
                  .forEach {
                    add(
                        buildJsonObject {
                          put("name", it.name)
                          put("value", it.value)
                        }
                    )
                  }
            },
        )
      }
      process.outputStream.writer().use { it.write(payload.toString()) }
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("Mac read probe timed out")
      }
      check(process.exitValue() == 0) { "Mac read probe failed" }
    }
    check("accoding") {
      val adapter = AccodingHomeworkAdapter(session.username)
      try {
        println("ACCODING resultRecords=${adapter.load().size}")
      } finally {
        adapter.close()
      }
    }
    LocalUpstreamClientProvider.reset()
    kotlin.check(failed.isEmpty()) {
      "Read-only service verification failed: ${failed.joinToString()}"
    }
  }
}
