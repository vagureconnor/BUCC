package app.bucc.ui.screens.finance

import dev.datlag.kcef.KCEF
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val paymentBrowserInitMutex = Mutex()
private var paymentBrowserInitialized = false
private var paymentBrowserInitialization: CompletableDeferred<Boolean>? = null
private var paymentBrowserInitializationStarted = false

// KCEF/JCEF 2024.04.20.4 must not load arbitrary newer JetBrains native binaries.
internal const val PAYMENT_BROWSER_RELEASE = "jbr-release-17.0.10b1207.14"

internal fun paymentBrowserInstallDirectory(cacheRoot: File): File =
    File(cacheRoot, "kcef-2024.04.20.4-jbr17.0.10-b1207.14")

internal fun paymentBrowserRuntimeIsCompatible(
    directory: File,
    arch: String = System.getProperty("os.arch"),
): Boolean {
  if (
      !File(directory, "libjcef.dylib").isFile ||
          !File(
                  directory,
                  "Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework",
              )
              .isFile
  )
      return false
  if (arch == "aarch64" || arch == "arm64") {
    val digest = MessageDigest.getInstance("SHA-256")
    File(directory, "libjcef.dylib").inputStream().use { input ->
      val buffer = ByteArray(65536)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } ==
        "6b674f7026888a0ad0cfa223e11c24de996d5f514a0a031414586d19b90bc6fc"
  }
  return arch == "x86_64" || arch == "amd64"
}

internal fun paymentBrowserCacheRoot(): File =
    if (System.getProperty("os.name").startsWith("Windows", true)) {
      File(
          System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
              ?: File(System.getProperty("user.home"), "AppData/Local").path,
          "BUCC/payment-webview",
      )
    } else {
      File(System.getProperty("user.home").orEmpty(), "Library/Caches/BUCC/payment-webview")
    }

actual suspend fun initializePaymentBrowser(): Boolean =
    if (System.getProperty("os.name").startsWith("Mac", true)) macPaymentBrowserAvailable()
    else initializePaymentBrowser(paymentBrowserCacheRoot())

internal suspend fun initializePaymentBrowser(cacheRoot: File): Boolean =
    paymentBrowserInitMutex.withLock {
      if (paymentBrowserInitialized) return@withLock true
      // Keep an in-flight initialization so a later retry can wait for a first-run download
      // that outlives the UI's wait timeout. KCEF native initialization must not be repeated.
      val initializedCallback =
          paymentBrowserInitialization
              ?: CompletableDeferred<Boolean>().also { paymentBrowserInitialization = it }
      if (!paymentBrowserInitializationStarted) {
        paymentBrowserInitializationStarted = true
        withContext(Dispatchers.IO + NonCancellable) {
          val installDirectory = paymentBrowserInstallDirectory(cacheRoot)
          val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
          if (
              isMac &&
                  File(installDirectory, "install.lock").exists() &&
                  !paymentBrowserRuntimeIsCompatible(installDirectory)
          ) {
            initializedCallback.complete(false)
            return@withContext
          }
          val previousJavaHome = System.getProperty("java.home")
          try {
            KCEF.init(
                builder = {
                  installDir(installDirectory)
                  download { github { release(PAYMENT_BROWSER_RELEASE) } }
                  settings {
                    cachePath =
                        File(cacheRoot, "cache-2024.04.20.4-jbr17.0.10-b1207.14").absolutePath
                  }
                  progress {
                    onInitializing {
                      check(!isMac || paymentBrowserRuntimeIsCompatible(installDirectory)) {
                        "Unsupported payment browser runtime"
                      }
                    }
                    onInitialized { initializedCallback.complete(true) }
                  }
                },
                onError = { _ -> initializedCallback.complete(false) },
                onRestartRequired = { initializedCallback.complete(false) },
            )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Throwable) {
            initializedCallback.complete(false)
          } finally {
            // KCEF temporarily changes this process-wide property while loading macOS CEF.
            System.setProperty("java.home", previousJavaHome)
          }
        }
      }
      val initialized = withTimeoutOrNull(60_000) { initializedCallback.await() } ?: false
      if (initialized) paymentBrowserInitialized = true
      initialized
    }
