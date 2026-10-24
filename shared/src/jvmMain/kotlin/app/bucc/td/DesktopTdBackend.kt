package app.bucc.td

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DesktopTdBackend {
  private val startMutex = Mutex()
  private var process: Process? = null
  private var address: String? = null
  private var localPort: Int? = null
  private val pythonCommand by lazy { resolvePythonCommand() }

  suspend fun endpoint(httpClient: HttpClient): String =
      startMutex.withLock {
        address
            ?.takeIf { process?.isAlive == true }
            ?.let {
              return@withLock it
            }

        val launch = findBackendLaunch()
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val builder =
            ProcessBuilder(launch.command + listOf("--port", port.toString()))
                .directory(launch.workingDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.PIPE)
        launch.pythonPath?.let { pythonPath ->
          val previous = builder.environment()["PYTHONPATH"]
          builder.environment()["PYTHONPATH"] =
              if (previous.isNullOrBlank()) pythonPath.pathString
              else pythonPath.pathString + File.pathSeparator + previous
        }

        val started = builder.start()
        DesktopTdProxy.allow(port)
        localPort = port
        val diagnostic = StringBuilder()
        val errorReader =
            Thread(
                    {
                      runCatching {
                        started.errorStream.bufferedReader().useLines { lines ->
                          lines.forEach { line ->
                            synchronized(diagnostic) {
                              diagnostic.append(line).append('\n')
                              if (diagnostic.length > 1200)
                                  diagnostic.delete(0, diagnostic.length - 1200)
                            }
                          }
                        }
                      }
                    },
                    "bucc-autotd-stderr",
                )
                .apply {
                  isDaemon = true
                  start()
                }
        process = started
        val endpoint = "http://127.0.0.1:$port"
        try {
          var lastFailure: String? = null
          repeat(240) {
            if (!started.isAlive) {
              errorReader.join(100)
              val detail = synchronized(diagnostic) { diagnostic.toString().trim() }
              throw IllegalStateException(
                  "AutoTD 本地服务启动后立即退出" +
                      detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
              )
            }
            val ready =
                try {
                  val status = httpClient.get("$endpoint/api/health").status
                  if (!status.isSuccess()) lastFailure = "HTTP ${status.value}"
                  status.isSuccess()
                } catch (cancelled: CancellationException) {
                  throw cancelled
                } catch (cause: Throwable) {
                  lastFailure = cause.javaClass.simpleName + ": " + cause.message
                  false
                }
            if (ready) {
              address = endpoint
              Runtime.getRuntime().addShutdownHook(Thread({ close() }, "bucc-autotd-shutdown"))
              return@withLock endpoint
            }
            delay(250)
          }
          throw IllegalStateException(
              "AutoTD 本地服务未能在 60 秒内启动" + lastFailure?.let { "：$it" }.orEmpty()
          )
        } catch (cause: Throwable) {
          destroyAndWait(started)
          if (process === started) process = null
          address = null
          DesktopTdProxy.remove(port)
          localPort = null
          throw cause
        }
      }

  fun close() {
    process?.let(::destroyAndWait)
    process = null
    address = null
    localPort?.let(DesktopTdProxy::remove)
    localPort = null
  }

  private fun destroyAndWait(target: Process) {
    if (!target.isAlive) return
    target.destroy()
    if (!target.waitFor(1, TimeUnit.SECONDS)) {
      target.destroyForcibly()
      target.waitFor(1, TimeUnit.SECONDS)
    }
  }

  private fun findBackendLaunch(): BackendLaunch {
    val explicit = System.getenv("BUCC_AUTOTD_BACKEND")?.takeIf(String::isNotBlank)?.let(Paths::get)
    val resourceDirectory =
        System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
    val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")
    val platformResourceDirectory =
        when (System.getProperty("os.name").lowercase()) {
          "mac os x",
          "macos",
          "darwin" ->
              when (System.getProperty("os.arch").lowercase()) {
                "aarch64",
                "arm64" -> resourceDirectory?.resolve("macos-arm64")
                "x86_64",
                "amd64" -> resourceDirectory?.resolve("macos-x64")
                else -> null
              }
          else ->
              if (
                  isWindows && System.getProperty("os.arch").lowercase() in setOf("x86_64", "amd64")
              ) {
                resourceDirectory?.resolve("windows-x64")
              } else {
                null
              }
        }

    val windowsExecutableSuffix = if (isWindows) ".exe" else ""
    val binaryCandidates =
        listOfNotNull(
            explicit?.takeIf { it.isRegularFile() && it.fileName.toString() != "BackendMain.py" },
            explicit
                ?.takeIf { Files.isDirectory(it) }
                ?.resolve("AutoTDBackend$windowsExecutableSuffix"),
            platformResourceDirectory?.resolve("td-backend/AutoTDBackend$windowsExecutableSuffix"),
            resourceDirectory?.resolve("td-backend/AutoTDBackend$windowsExecutableSuffix"),
            resourceDirectory?.resolve("AutoTDBackend$windowsExecutableSuffix"),
        )
    binaryCandidates
        .firstOrNull {
          it.isRegularFile() &&
              (isWindows && it.fileName.toString().endsWith(".exe", ignoreCase = true) ||
                  Files.isExecutable(it))
        }
        ?.let { binary ->
          return BackendLaunch(listOf(binary.pathString), binary.parent, pythonPath = null)
        }

    val classpathScript =
        javaClass.classLoader.getResource("td-backend/BackendMain.py")?.let { resource ->
          if (resource.protocol == "file") runCatching { Paths.get(resource.toURI()) }.getOrNull()
          else null
        }
    val workingDirectory = Paths.get(System.getProperty("user.dir"))
    val scriptCandidates =
        listOfNotNull(
            explicit?.takeIf { it.isRegularFile() && it.fileName.toString() == "BackendMain.py" },
            explicit?.takeIf { Files.isDirectory(it) }?.resolve("BackendMain.py"),
            resourceDirectory?.resolve("td-backend/BackendMain.py"),
            classpathScript,
            workingDirectory.resolve("composeApp/src/jvmMain/resources/td-backend/BackendMain.py"),
            workingDirectory.resolve("src/jvmMain/resources/td-backend/BackendMain.py"),
        )
    val script =
        scriptCandidates.firstOrNull { it.isRegularFile() }
            ?: throw IllegalStateException("找不到内置 AutoTD 服务；请配置桌面资源目录或 BUCC_AUTOTD_BACKEND")
    val root = script.parent
    return BackendLaunch(
        command = listOf(pythonCommand) + script.pathString,
        workingDirectory = root,
        pythonPath = root.resolve("src"),
    )
  }

  private data class BackendLaunch(
      val command: List<String>,
      val workingDirectory: Path,
      val pythonPath: Path?,
  )
}

internal fun resolvePythonCommand(
    explicit: String? = System.getenv("BUCC_AUTOTD_PYTHON"),
    candidates: List<String> =
        if (System.getProperty("os.name").lowercase().startsWith("windows")) {
          listOf("python.exe")
        } else {
          listOf(
              "/opt/homebrew/bin/python3",
              "/usr/local/bin/python3",
              "/usr/bin/python3",
              "python3",
          )
        },
    timeoutMillis: Long = 5_000,
): String {
  val requested = explicit?.takeIf(String::isNotBlank)
  val options = requested?.let(::listOf) ?: candidates
  for (python in options) {
    val probe =
        try {
          ProcessBuilder(python, "-c", "import cryptography")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start()
        } catch (_: Exception) {
          continue
        }
    try {
      if (probe.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) && probe.exitValue() == 0)
          return python
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      throw IllegalStateException("Python 解释器探测被中断")
    } finally {
      if (probe.isAlive) probe.destroyForcibly()
    }
  }
  val prefix =
      if (requested != null) "BUCC_AUTOTD_PYTHON 指定的解释器不可用或缺少 cryptography。"
      else "未找到可用的 Python 解释器。"
  throw IllegalStateException("$prefix 请安装 cryptography，或设置 BUCC_AUTOTD_PYTHON 指向已安装依赖的解释器。")
}
