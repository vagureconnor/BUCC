package app.bucc.api.finance

import app.bucc.diagnostics.*
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom

internal fun resolveFinanceRedirect(base: Url, location: String): String {
  val reference = location.trim()
  val resolved =
      when {
        reference.startsWith('?') ->
            base.toString().substringBefore('?').substringBefore('#') + reference
        reference.startsWith('#') -> base.toString().substringBefore('#') + reference
        else -> reference
      }
  return URLBuilder(base)
      .apply {
        parameters.clear()
        fragment = ""
        takeFrom(resolved)
      }
      .buildString()
}

internal suspend fun diagnosticFinanceRequest(
    url: String,
    mutation: Boolean = false,
    block: suspend () -> HttpResponse,
): HttpResponse {
  recordDiagnostic(DiagnosticStage.HTTP, DiagnosticOutcome.STARTED, url)
  try {
    val response = block()
    recordDiagnostic(
        DiagnosticStage.HTTP,
        if (response.status.value in 200..399) DiagnosticOutcome.SUCCESS
        else DiagnosticOutcome.FAILURE,
        url,
        response.status.value,
    )
    response.headers[HttpHeaders.Location]?.let { location ->
      val target = resolveFinanceRedirect(response.call.request.url, location)
      recordDiagnostic(
          DiagnosticStage.REDIRECT,
          DiagnosticOutcome.STARTED,
          target,
          response.status.value,
      )
    }
    return response
  } catch (error: Throwable) {
    recordDiagnostic(
        DiagnosticStage.HTTP,
        if (mutation) DiagnosticOutcome.UNCERTAIN else DiagnosticOutcome.FAILURE,
        url,
        error = error,
    )
    throw error
  }
}

internal suspend fun HttpClient.financeGet(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse = diagnosticFinanceRequest(url) { get(url, block) }

internal suspend fun HttpClient.financePost(
    url: String,
    block: HttpRequestBuilder.() -> Unit = {},
): HttpResponse = diagnosticFinanceRequest(url, mutation = true) { post(url, block) }
