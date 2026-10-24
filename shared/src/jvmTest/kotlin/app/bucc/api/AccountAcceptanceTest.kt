package app.bucc.api

import app.bucc.api.core.DefaultApiFactory
import app.bucc.api.feature.LibBookApi
import app.bucc.api.feature.YgdkApi
import app.bucc.api.finance.parseZfwRechargeForm
import app.bucc.api.local.LocalAuthServiceBackend
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.local.localUpstreamUrl
import app.bucc.homework.ByxtHomeworkAdapter
import app.bucc.homework.SpocHomeworkAdapter
import app.bucc.repository.ScheduleStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Explicit live acceptance; credentials are read only from the process environment. */
class AccountAcceptanceTest {
  @Test
  fun readOnlyCampusAcceptance() = runBlocking {
    val username = System.getenv("BUCC_VERIFY_USERNAME")?.takeIf(String::isNotBlank)
    val password = System.getenv("BUCC_VERIFY_PASSWORD")?.takeIf(String::isNotBlank)
    if (username == null || password == null) {
      println("ACCOUNT_ACCEPTANCE skipped=missing_environment")
      return@runBlocking
    }

    val oldModeSettings = ConnectionModeStore.settings
    val oldAuthSettings = LocalAuthSessionStore.settings
    val oldCookieSettings = LocalCookieStore.settings
    val oldScheduleSettings = ScheduleStore.settings
    try {
      for (mode in ConnectionMode.entries) {
        val isolated = MapSettings()
        ConnectionModeStore.settings = isolated
        LocalAuthSessionStore.settings = isolated
        LocalCookieStore.settings = isolated
        ScheduleStore.settings = isolated
        ConnectionModeStore.save(mode)
        ConnectionRuntime.resolveSelectedMode()
        LocalUpstreamClientProvider.reset()
        DefaultApiFactory.clearCachedBackends()
        try {
          val auth = LocalAuthServiceBackend()
          val preload =
              auth.preloadLoginState().getOrElse {
                println(
                    "ACCOUNT_ACCEPTANCE mode=${mode.storageKey} stage=preload result=FAIL type=${it::class.simpleName}"
                )
                continue
              }
          if (preload.captchaRequired) {
            println(
                "ACCOUNT_ACCEPTANCE mode=${mode.storageKey} stage=login result=MANUAL_VERIFICATION_REQUIRED"
            )
            continue
          }
          if (preload.userData == null) {
            val login = auth.login(username, password, null, preload.execution)
            if (login.isFailure) {
              println(
                  "ACCOUNT_ACCEPTANCE mode=${mode.storageKey} stage=login result=FAIL type=${login.exceptionOrNull()?.let { it::class.simpleName }}"
              )
              continue
            }
          } else {
            LocalAuthSessionStore.save(
                app.bucc.api.local.LocalAuthSession(
                    username = username,
                    user = preload.userData,
                    authenticatedAt = "acceptance",
                    lastActivity = "acceptance",
                )
            )
          }
          check(LocalAuthSessionStore.get() != null) { "session_missing" }
          println("ACCOUNT_ACCEPTANCE mode=${mode.storageKey} stage=login result=PASS")
          runReadOnlyChecks(mode)
        } catch (error: Exception) {
          println(
              "ACCOUNT_ACCEPTANCE mode=${mode.storageKey} stage=read_only result=FAIL type=${error::class.simpleName}"
          )
        } finally {
          LocalUpstreamClientProvider.reset()
          DefaultApiFactory.clearCachedBackends()
          LocalAuthSessionStore.clearAllScopes()
          LocalCookieStore.clearAllScopes()
        }
      }
    } finally {
      LocalUpstreamClientProvider.reset()
      DefaultApiFactory.clearCachedBackends()
      ConnectionModeStore.settings = oldModeSettings
      LocalAuthSessionStore.settings = oldAuthSettings
      LocalCookieStore.settings = oldCookieSettings
      ScheduleStore.settings = oldScheduleSettings
    }
  }

  private suspend fun runReadOnlyChecks(mode: ConnectionMode) {
    val day = Clock.System.now().toLocalDateTime(TimeZone.of("+08:00")).date.toString()
    val library = LibBookApi()
    val libraries = library.getLibraries(day).getOrThrow()
    val areas =
        libraries.firstOrNull()?.let { library.getAreas(it.id, null, day).getOrThrow() }.orEmpty()
    val area = areas.firstOrNull()
    val seats = area?.let { library.getSeats(it.id, day, "08:00", "20:00").getOrThrow() }.orEmpty()
    val bookings = library.getBookings(1, 20).getOrThrow()
    println(
        "ACCOUNT_ACCEPTANCE mode=${mode.storageKey} library=PASS libraries=${libraries.size} areas=${areas.size} seats=${seats.size} bookings=${bookings.bookings.size}"
    )

    val overview = YgdkApi().getOverview().getOrThrow()
    val records = YgdkApi().getRecords(1, 5).getOrThrow()
    println(
        "ACCOUNT_ACCEPTANCE mode=${mode.storageKey} ygdk=PASS items=${overview.items.size} records=${records.content.size}"
    )

    val client = LocalUpstreamClientProvider.newClient(LocalCookieStore.storage(), false)
    try {
      val response = client.get(localUpstreamUrl("https://zfw.buaa.edu.cn/pay/card"))
      val html = response.bodyAsText()
      val formAvailable = parseZfwRechargeForm(html) != null
      println(
          "ACCOUNT_ACCEPTANCE mode=${mode.storageKey} zfw=READ status=${response.status.value} form=$formAvailable"
      )
    } finally {
      client.close()
    }

    val spoc = SpocHomeworkAdapter().load()
    val byxt = ByxtHomeworkAdapter().load()
    println(
        "ACCOUNT_ACCEPTANCE mode=${mode.storageKey} homework=PASS spoc=${spoc.size} byxt=${byxt.size}"
    )
  }
}
