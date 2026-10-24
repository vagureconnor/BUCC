package app.bucc.td

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionModeStore
import app.bucc.api.ConnectionRuntime
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DesktopTdCancellationTest {
  @Test
  fun cancelledReadDoesNotPublishAServiceFailure() = runTest {
    val client = HttpClient(MockEngine { awaitCancellation() })
    val service =
        DesktopTdService(httpClient = client).apply {
          javaClass
              .getDeclaredField("endpoint")
              .apply { isAccessible = true }
              .set(this, "http://127.0.0.1:12345")
        }
    try {
      val loading = launch { service.load() }
      testScheduler.runCurrent()
      loading.cancelAndJoin()
      assertNull(service.state.value.error)
      assertNull(service.state.value.availabilityMessage)
      assertFalse(service.state.value.loading)
    } finally {
      service.close()
    }
  }

  @Test
  fun structuredProgressMapsRoundDelayWithoutParsingDisplayText() {
    val progress =
        desktopExecutionProgress(
            status =
                buildJsonObject {
                  put("active", true)
                  put("phase", "round_delay")
                  put("message", "任意展示文本")
                  put("current_round", 2)
                  put("completed_rounds", 1)
                  put("remaining_seconds", 17)
                },
            requestedRounds = 3,
            previous = TdExecutionState(),
        )

    requireNotNull(progress)
    assertEquals(TdExecutionPhase.ROUND_DELAY, progress.phase)
    assertEquals(2, progress.currentRound)
    assertEquals(1, progress.completedRounds)
    assertEquals(17, progress.remainingSeconds)
    assertEquals("任意展示文本", progress.message)
  }

  @Test
  fun cancelDuringBackendStartupCancelsExecutionAndDestroysSpawnedProcess() = runTest {
    val fixture = BackendFixture.create()
    val previousModeSettings = ConnectionModeStore.settings
    ConnectionModeStore.settings = MapSettings()
    ConnectionModeStore.save(ConnectionMode.DIRECT)
    ConnectionRuntime.resolveSelectedMode()
    val previousResources = System.getProperty(RESOURCE_DIRECTORY_PROPERTY)
    System.setProperty(RESOURCE_DIRECTORY_PROPERTY, fixture.resourceDirectory.toString())
    val requests = mutableListOf<String>()
    val client =
        HttpClient(
            MockEngine { request ->
              requests += request.url.encodedPath
              awaitCancellation()
            }
        )
    val backend = DesktopTdBackend()
    val service = DesktopTdService(httpClient = client, backend = backend)

    try {
      val execution = launch { service.startCheckin(1) }
      fixture.awaitPid()

      service.cancelCheckin()
      execution.join()

      assertEquals(TdExecutionStatus.CANCELLED, service.state.value.execution.status)
      assertFalse(execution.isActive)
      fixture.awaitProcessExit()
      assertEquals(listOf("/api/health"), requests)
    } finally {
      service.close()
      ConnectionModeStore.settings = previousModeSettings
      ConnectionRuntime.resolveSelectedMode()
      if (previousResources == null) System.clearProperty(RESOURCE_DIRECTORY_PROPERTY)
      else System.setProperty(RESOURCE_DIRECTORY_PROPERTY, previousResources)
      fixture.delete()
    }
  }

  @Test
  fun cancellingBackendReadinessDestroysSpawnedProcess() = runTest {
    val fixture = BackendFixture.create()
    val previousResources = System.getProperty(RESOURCE_DIRECTORY_PROPERTY)
    System.setProperty(RESOURCE_DIRECTORY_PROPERTY, fixture.resourceDirectory.toString())
    val client = HttpClient(MockEngine { awaitCancellation() })
    val backend = DesktopTdBackend()

    try {
      val startup = launch { backend.endpoint(client) }
      fixture.awaitPid()

      startup.cancelAndJoin()

      fixture.awaitProcessExit()
    } finally {
      backend.close()
      client.close()
      if (previousResources == null) System.clearProperty(RESOURCE_DIRECTORY_PROPERTY)
      else System.setProperty(RESOURCE_DIRECTORY_PROPERTY, previousResources)
      fixture.delete()
    }
  }

  private class BackendFixture(
      val resourceDirectory: java.nio.file.Path,
      private val pidFile: java.nio.file.Path,
  ) {
    suspend fun awaitPid(): Long =
        withContext(Dispatchers.IO) {
          repeat(1000) {
            if (pidFile.exists()) {
              pidFile.readText().trim().toLongOrNull()?.let {
                return@withContext it
              }
            }
            Thread.sleep(10)
          }
          error("backend fixture did not write its pid")
        }

    suspend fun awaitProcessExit() {
      val pid = awaitPid()
      withContext(Dispatchers.IO) {
        repeat(200) {
          val alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
          if (!alive) return@withContext
          Thread.sleep(10)
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
      }
    }

    fun delete() {
      resourceDirectory.toFile().deleteRecursively()
    }

    companion object {
      fun create(): BackendFixture {
        val resources = createTempDirectory("bucc-td-backend-test")
        val backendDirectory = resources.resolve("td-backend").createDirectories()
        val pidFile = resources.resolve("backend.pid")
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
          val executable = backendDirectory.resolve("AutoTDBackend.exe")
          requireNotNull(
                  DesktopTdCancellationTest::class
                      .java
                      .getResourceAsStream("/td/BackendProcessFixture.exe")
              )
              .use { Files.copy(it, executable) }
        } else {
          val executable = backendDirectory.resolve("AutoTDBackend")
          executable.writeText(
              """#!/bin/sh
              echo ${'$'}${'$'} > '${pidFile.toAbsolutePath()}'
              while true; do sleep 1; done
              """
                  .trimIndent()
          )
          assertTrue(executable.toFile().setExecutable(true))
          assertTrue(Files.isExecutable(executable))
        }
        return BackendFixture(resources, pidFile)
      }
    }
  }

  private companion object {
    const val RESOURCE_DIRECTORY_PROPERTY = "compose.application.resources.dir"
  }
}
