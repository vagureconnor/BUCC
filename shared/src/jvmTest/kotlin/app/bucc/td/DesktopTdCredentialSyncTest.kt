package app.bucc.td

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopTdCredentialSyncTest {
  @Test
  fun countRefreshProgressClearsAfterSuccessFailureAndCancellation() = runTest {
    val previousModeSettings = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    TdCampusCredentials.clearSession()
    try {
      for (outcome in listOf("success", "failure", "cancel")) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client =
            HttpClient(
                MockEngine { request ->
                  when (request.url.encodedPath) {
                    "/api/connection-mode" -> respond("{}", HttpStatusCode.OK)
                    "/api/counts/refresh" -> {
                      entered.complete(Unit)
                      release.await()
                      if (outcome == "failure")
                          respond(
                              """{"error":"fixture failure"}""",
                              HttpStatusCode.ServiceUnavailable,
                          )
                      else
                          respond(
                              """{"daily":{"count":1},"total":{"count":7,"term":"2026-2027-1"},"errors":[]}""",
                              HttpStatusCode.OK,
                          )
                    }
                    else -> error("Unexpected read-only fixture path")
                  }
                }
            )
        val service =
            DesktopTdService(httpClient = client, backend = DesktopTdBackend()).apply {
              javaClass
                  .getDeclaredField("endpoint")
                  .apply { isAccessible = true }
                  .set(this, "http://127.0.0.1:12345")
            }
        try {
          val refresh = launch { service.refreshCounts() }
          entered.await()
          assertTrue(service.state.value.refreshingCounts)
          assertTrue(service.state.value.loading)
          if (outcome == "cancel") refresh.cancelAndJoin()
          else {
            release.complete(Unit)
            refresh.join()
          }
          assertEquals(false, service.state.value.refreshingCounts)
          assertEquals(false, service.state.value.loading)
          if (outcome == "success") assertEquals(7, service.state.value.counts.semester)
          if (outcome == "failure") assertEquals("fixture failure", service.state.value.error)
        } finally {
          release.complete(Unit)
          service.close()
        }
      }
    } finally {
      ConnectionModeStore.settings = previousModeSettings
      ConnectionRuntime.resolveSelectedMode()
      TdCampusCredentials.clearSession()
    }
  }

  @Test
  fun loadPersistsMatchingActiveCampusCredentialThroughHealthCloudSettingsEndpoint() = runTest {
    TdCampusCredentials.setAuthenticated("20370001", "session-only")
    var stateCalls = 0
    var savedPayload: String? = null
    val client =
        HttpClient(
            MockEngine { request ->
              when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/state" -> {
                  stateCalls += 1
                  val body =
                      if (stateCalls == 1) {
                        """{"users":[{"student_id":"20370001","healthcloud_username":"20370001","healthcloud_password_configured":false}],"images":[]}"""
                      } else {
                        """{"users":[{"student_id":"20370001","healthcloud_username":"20370001","healthcloud_password_configured":true}],"images":[]}"""
                      }
                  respond(body, HttpStatusCode.OK)
                }
                request.method == HttpMethod.Post &&
                    request.url.encodedPath == "/api/settings/healthcloud" -> {
                  savedPayload = (request.body as TextContent).text
                  respond("{}", HttpStatusCode.OK)
                }
                request.method == HttpMethod.Post &&
                    request.url.encodedPath == "/api/connection-mode" ->
                    respond("{}", HttpStatusCode.OK)
                else ->
                    error(
                        "Unexpected TD backend request: ${request.method} ${request.url.encodedPath}"
                    )
              }
            }
        )
    val service =
        DesktopTdService(httpClient = client, backend = DesktopTdBackend()).apply {
          javaClass
              .getDeclaredField("endpoint")
              .apply { isAccessible = true }
              .set(this, "http://127.0.0.1:12345")
        }

    try {
      service.load()

      val payload = Json.parseToJsonElement(requireNotNull(savedPayload)).jsonObject
      assertEquals("20370001", payload.getValue("student_id").jsonPrimitive.content)
      assertEquals("", payload.getValue("username").jsonPrimitive.content)
      assertEquals("session-only", payload.getValue("password").jsonPrimitive.content)
      assertTrue(service.state.value.account?.passwordConfigured == true)
    } finally {
      service.close()
      TdCampusCredentials.clearSession()
    }
  }

  @Test
  fun readOnlyGatewayLoadNeverPersistsCampusPasswordInTheBackendVault() = runTest {
    val previous = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    TdCampusCredentials.setAuthenticated("20370001", "fixture-only")
    val client =
        HttpClient(
            MockEngine { request ->
              when (request.url.encodedPath) {
                "/api/state" ->
                    respond(
                        """{"users":[{"student_id":"20370001","healthcloud_username":"20370001"}],"images":[]}""",
                        HttpStatusCode.OK,
                    )
                "/api/connection-mode" -> respond("{}", HttpStatusCode.OK)
                else -> error("Read-only gateway load must not persist credentials")
              }
            }
        )
    val service =
        DesktopTdService(httpClient = client).apply {
          javaClass
              .getDeclaredField("endpoint")
              .apply { isAccessible = true }
              .set(this, "http://127.0.0.1:12345")
        }
    try {
      for (mode in listOf(ConnectionMode.WEBVPN)) {
        ConnectionModeStore.save(mode)
        ConnectionRuntime.resolveSelectedMode()
        service.load()
        assertEquals(null, service.state.value.error)
        assertTrue(service.state.value.available)
      }
    } finally {
      service.close()
      TdCampusCredentials.clearSession()
      ConnectionModeStore.settings = previous
      ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun desktopServiceRejectsWebVpnCheckinBeforeStartingBackend() = runTest {
    val previousModeSettings = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    val client =
        HttpClient(
            MockEngine { error("Read-only TD check-in must not contact the desktop backend") }
        )
    val service = DesktopTdService(httpClient = client, backend = DesktopTdBackend())
    try {
      for (mode in listOf(ConnectionMode.WEBVPN)) {
        ConnectionModeStore.save(mode)
        ConnectionRuntime.resolveSelectedMode()
        assertFailsWith<IllegalStateException> { service.startCheckin(1) }
      }
    } finally {
      service.close()
      ConnectionModeStore.settings = previousModeSettings
      ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun refreshCarriesCurrentConnectionModeOnEveryRequestWithoutCredentials() = runTest {
    val previousModeSettings = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    val modes = mutableListOf<String?>()
    val client =
        HttpClient(
            MockEngine { request ->
              if (request.method == HttpMethod.Post) {
                val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                when (request.url.encodedPath) {
                  "/api/connection-mode" -> modes += payload["mode"]?.jsonPrimitive?.content
                  "/api/counts/refresh" -> {
                    assertEquals(
                        modes.lastOrNull(),
                        payload["connection_mode"]?.jsonPrimitive?.content,
                    )
                    assertTrue("password" !in payload)
                  }
                  else -> error("Unexpected TD backend POST ${request.url.encodedPath}")
                }
              }
              respond("{}", HttpStatusCode.OK)
            }
        )
    val service =
        DesktopTdService(httpClient = client, backend = DesktopTdBackend()).apply {
          javaClass
              .getDeclaredField("endpoint")
              .apply { isAccessible = true }
              .set(this, "http://127.0.0.1:12345")
        }
    try {
      for (mode in listOf(ConnectionMode.DIRECT, ConnectionMode.WEBVPN, null)) {
        if (mode == null) ConnectionModeStore.clear() else ConnectionModeStore.save(mode)
        ConnectionRuntime.resolveSelectedMode()
        service.refreshCounts()
      }
      assertEquals(listOf<String?>("direct", "webvpn", ""), modes)
    } finally {
      service.close()
      ConnectionModeStore.settings = previousModeSettings
      ConnectionRuntime.resolveSelectedMode()
    }
  }
}
