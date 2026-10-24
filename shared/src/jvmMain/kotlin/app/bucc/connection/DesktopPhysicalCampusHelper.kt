package app.bucc.connection

import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class DesktopPhysicalCampusHelper(
    private val executableOverride: Path? = null,
    private val responseTimeoutMillis: Long = 15_000,
) : PhysicalCampusHelper {
  private var process: Process? = null
  private var reader: BufferedReader? = null
  private var writer: BufferedWriter? = null
  private val mutex = Mutex()

  override suspend fun exchange(request: String): String =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          check(physicalCampusProxySupported) { "physical_proxy_requires_macos12" }
          if (process?.isAlive != true) {
            val name =
                if (System.getProperty("os.name").startsWith("Windows", true))
                    "bucc-campus-proxy.exe"
                else "bucc-campus-proxy"
            val roots =
                listOfNotNull(
                    executableOverride,
                    System.getProperty("compose.application.resources.dir")?.let {
                      Path.of(it, "campus-proxy", name)
                    },
                    Path.of("build/campus-proxy/desktop", name),
                )
            val executable =
                roots.firstOrNull(Files::isRegularFile) ?: error("physical_proxy_not_bundled")
            process =
                ProcessBuilder(executable.toAbsolutePath().toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            reader = process!!.inputStream.bufferedReader()
            writer = process!!.outputStream.bufferedWriter()
          }
          writer!!.apply {
            write(request)
            newLine()
            flush()
          }
          try {
            withTimeout(responseTimeoutMillis) {
              val response = StringBuilder()
              while (true) {
                if (reader!!.ready()) {
                  val char = reader!!.read()
                  check(char >= 0) { "physical_proxy_exited" }
                  if (char == 10) break
                  response.append(char.toChar())
                  check(response.length <= 2 * 1024 * 1024) { "invalid_physical_proxy_response" }
                } else {
                  check(process?.isAlive == true) { "physical_proxy_exited" }
                  delay(10)
                }
              }
              response.toString()
            }
          } catch (failure: Throwable) {
            process?.destroyForcibly()
            writer = null
            reader = null
            process = null
            throw failure
          }
        }
      }

  override suspend fun close() =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          writer?.close()
          process?.destroy()
          reader?.close()
          writer = null
          reader = null
          process = null
        }
      }
}
