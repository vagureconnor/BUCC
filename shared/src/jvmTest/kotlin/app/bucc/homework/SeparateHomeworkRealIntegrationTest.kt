package app.bucc.homework

import app.bucc.repository.ScheduleStore
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/** Opt-in, read-only checks. Credentials are provided only by the test process environment. */
class SeparateHomeworkRealIntegrationTest {
  @Test
  fun `separate source login and current homework query`() = runBlocking {
    val source = System.getenv("BUCC_LIVE_HOMEWORK_SOURCE")
    assumeTrue(source == "ACCODING")
    val username = System.getenv("BUCC_LIVE_HOMEWORK_USERNAME").orEmpty()
    val password = System.getenv("BUCC_LIVE_HOMEWORK_PASSWORD").orEmpty()
    assumeTrue(username.isNotBlank() && password.isNotBlank())
    val previous = ScheduleStore.settings
    ScheduleStore.settings = MapSettings()
    val adapter: HomeworkAdapter = AccodingHomeworkAdapter("live-validation")
    try {
      adapter.login(username, password)
      val items = adapter.load()
      assertTrue(items.all { it.source.name == source })
      println("Separate source authenticated and queried: $source, items=${items.size}")
    } finally {
      adapter.close()
      ScheduleStore.settings = previous
    }
  }
}
