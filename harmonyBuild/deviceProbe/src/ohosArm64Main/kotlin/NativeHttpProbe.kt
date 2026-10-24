package app.bucc.probe

import app.bucc.api.core.getDefaultEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout

/** Read-only anonymous public endpoint; no cookies, accounts or response content are persisted. */
internal suspend fun nativeHttpProbe() {
  val client =
      HttpClient(getDefaultEngine()) { install(HttpTimeout) { requestTimeoutMillis = 10_000 } }
  try {
    for (endpoint in
        listOf(
            "https://example.com/",
            "https://sso.buaa.edu.cn/",
            "https://uc.buaa.edu.cn/api/uc/status",
        )) {
      try {
        withTimeout(15_000) {
          val response = client.get(endpoint)
          check(response.status.value in 200..399)
          response.bodyAsText()
          println("BUCC_NATIVE_HTTP_ENDPOINT " + endpoint + " PASS")
        }
      } catch (error: Throwable) {
        println(
            "BUCC_NATIVE_HTTP_ENDPOINT " +
                endpoint +
                " " +
                error::class.simpleName +
                " " +
                error.message.orEmpty().replace(Regex("https?://[^ ]+"), "[endpoint]")
        )
        if (endpoint.contains("uc.buaa")) throw error
      }
    }
  } catch (error: Throwable) {
    // This probe sends no credentials or cookies; redact the fixed endpoint as well.
    println(
        "BUCC_NATIVE_HTTP_FAILURE " +
            error::class.simpleName +
            " " +
            error.message.orEmpty().replace(Regex("https?://[^ ]+"), "[endpoint]")
    )
    throw error
  } finally {
    client.close()
  }
}
