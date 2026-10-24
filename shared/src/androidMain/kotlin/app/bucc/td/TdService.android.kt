package app.bucc.td

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import app.bucc.security.CampusCredential
import app.bucc.security.CampusSecureCredentials
import app.bucc.security.initializeCampusCredentialStorage
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

actual fun createTdService(): TdService {
  val httpClient =
      HttpClient(OkHttp) {
        followRedirects = false
        install(HttpCookies) { storage = TdHealthCloudCookies() }
        install(HttpTimeout) {
          requestTimeoutMillis = 15_000
          connectTimeoutMillis = 10_000
          socketTimeoutMillis = 15_000
        }
      }
  val protocol =
      TdProtocolClient(
          socketFactory = TdSocketFactory { host, port -> AndroidTdSocket(host, port) },
      )
  return TdDirectService(
      store = AndroidTdStore(),
      gateway = TdProtocolGateway(protocol),
      healthCloud = TdHealthCloudClient(httpClient),
      powerController = TdExecutionPowerController(AndroidTdRuntime::setExecutionActive),
  )
}

/** Called by the Android host before Compose content is installed. */
fun initializeAndroidTd(activity: Activity) {
  AndroidTdRuntime.initialize(activity)
}

private object AndroidTdRuntime {
  @Volatile private var applicationContext: Context? = null
  @Volatile private var activityRef = WeakReference<Activity>(null)
  @Volatile private var executionActive = false

  fun initialize(activity: Activity) {
    applicationContext = activity.applicationContext
    activityRef = WeakReference(activity)
    initializeCampusCredentialStorage(activity.applicationContext)
    applyScreenFlag(activity)
  }

  fun assetsContext(): Context = applicationContext ?: error("TD 运行环境尚未初始化")

  fun setExecutionActive(active: Boolean) {
    executionActive = active
    activityRef.get()?.let(::applyScreenFlag)
  }

  private fun applyScreenFlag(activity: Activity) {
    activity.runOnUiThread {
      if (activityRef.get() !== activity || activity.isFinishing || activity.isDestroyed)
          return@runOnUiThread
      if (executionActive) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    }
  }
}

private class AndroidTdSocket(
    private val host: String,
    private val port: Int,
) : TdSocket {
  private val executor =
      Executors.newSingleThreadExecutor(
          ThreadFactory { runnable ->
            Thread(runnable, "bucc-td-socket").apply { isDaemon = true }
          },
      )
  @Volatile private var socket: Socket? = null
  @Volatile private var closed = false
  private val socketLock = Any()

  override suspend fun connect() {
    runSocketIo {
      val connected = Socket()
      synchronized(socketLock) {
        if (closed) {
          runCatching { connected.close() }
          throw TdProtocolException("TD 连接已关闭，请重新尝试")
        }
        socket = connected
      }
      try {
        connected.connect(
            InetSocketAddress(host, port),
            TdDefaults.socketTimeoutMillis.toInt(),
        )
        connected.soTimeout = TdDefaults.socketTimeoutMillis.toInt()
      } catch (_: IOException) {
        closeTransport()
        throw TdProtocolException("TD 服务器连接失败，请检查网络或校园 VPN 后重试")
      }
    }
  }

  override suspend fun write(bytes: ByteArray) {
    runSocketIo {
      try {
        requireNotNull(socket) { "TD 连接尚未建立" }
            .getOutputStream()
            .apply {
              write(bytes)
              flush()
            }
      } catch (_: IOException) {
        throw TdProtocolException("TD 请求发送失败，请检查网络后重试")
      }
    }
  }

  override suspend fun read(maxBytes: Int): ByteArray = runSocketIo {
    val target = ByteArray(maxBytes)
    try {
      val count = requireNotNull(socket) { "TD 连接尚未建立" }.getInputStream().read(target)
      if (count < 0) ByteArray(0) else target.copyOf(count)
    } catch (_: IOException) {
      throw TdProtocolException("TD 响应读取失败或连接超时，请检查网络后重试")
    }
  }

  override fun close() {
    closed = true
    closeTransport()
    executor.shutdownNow()
  }

  private suspend fun <T> runSocketIo(block: () -> T): T =
      suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { close() }
        try {
          executor.execute {
            try {
              val result = block()
              if (continuation.isActive) continuation.resume(result)
            } catch (cause: Throwable) {
              if (continuation.isActive) continuation.resumeWithException(cause)
            }
          }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
          if (continuation.isActive) {
            continuation.resumeWithException(TdProtocolException("TD 连接已关闭，请重新尝试"))
          }
        }
      }

  private fun closeTransport() {
    synchronized(socketLock) {
      runCatching { socket?.close() }
      socket = null
    }
  }
}

private class AndroidTdStore(private val settings: Settings = Settings()) : TdDirectStore {
  override fun loadAccount(): TdStoredAccount? {
    val studentId = settings.getStringOrNull(KEY_STUDENT_ID) ?: return null
    val campus =
        runCatching { TdCampus.valueOf(settings.getString(KEY_CAMPUS, TdCampus.XUEYUANLU.name)) }
            .getOrDefault(TdCampus.XUEYUANLU)
    return TdStoredAccount(
        studentId = studentId,
        cardId = settings.getString(KEY_CARD_ID, studentId.toLong().toString(16).uppercase()),
        campus = campus,
        entranceMachineId = settings.getInt(KEY_ENTRANCE, if (campus == TdCampus.SHAHE) 8 else 2),
        exitMachineId = settings.getInt(KEY_EXIT, if (campus == TdCampus.SHAHE) 11 else 5),
        healthCloudUsernameOverride = settings.getStringOrNull(KEY_HEALTH_CLOUD_USERNAME),
    )
  }

  override fun saveAccount(account: TdStoredAccount) {
    settings.putString(KEY_STUDENT_ID, account.studentId)
    settings.putString(KEY_CARD_ID, account.cardId)
    settings.putString(KEY_CAMPUS, account.campus.name)
    settings.putInt(KEY_ENTRANCE, account.entranceMachineId)
    settings.putInt(KEY_EXIT, account.exitMachineId)
    account.healthCloudUsernameOverride?.let { settings.putString(KEY_HEALTH_CLOUD_USERNAME, it) }
        ?: settings.remove(KEY_HEALTH_CLOUD_USERNAME)
  }

  override fun password(studentId: String): String? =
      runBlocking(Dispatchers.IO) {
        CampusSecureCredentials.load(credentialKey(studentId))?.password
      }

  override fun savePassword(studentId: String, password: String) {
    runBlocking(Dispatchers.IO) {
      CampusSecureCredentials.save(CampusCredential(credentialKey(studentId), password))
    }
  }

  override fun deletePassword(studentId: String) {
    runBlocking(Dispatchers.IO) { CampusSecureCredentials.delete(credentialKey(studentId)) }
  }

  override fun listImages(): List<TdImage> =
      DEFAULT_IMAGE_NAMES.mapNotNull { name ->
        runCatching {
              AndroidTdRuntime.assetsContext().assets.open(assetPath(name)).use {
                it.readBytes().size.toLong()
              }
            }
            .getOrNull()
            ?.let { TdImage(name = name, sizeBytes = it) }
      }

  override fun saveImage(name: String, bytes: ByteArray) = error("TD 打卡图片由应用提供")

  override fun deleteImage(name: String) = error("TD 打卡图片由应用提供")

  override fun randomImageBytes(): ByteArray {
    require(listImages().size == DEFAULT_IMAGE_NAMES.size) { "应用内置的 TD 图片不完整" }
    return AndroidTdRuntime.assetsContext()
        .assets
        .open(assetPath(DEFAULT_IMAGE_NAMES.random()))
        .use { it.readBytes() }
  }

  override fun loadCounts(studentId: String): TdStoredCounts? {
    val prefix = countsPrefix(studentId)
    val term = settings.getStringOrNull("${prefix}term") ?: return null
    val days =
        settings
            .getString("${prefix}days", "")
            .split(';')
            .mapNotNull { entry ->
              val key = entry.substringBefore('=', "")
              val value = entry.substringAfter('=', "").toIntOrNull()
              if (key.isBlank() || value == null) null else key to value
            }
            .toMap()
    return TdStoredCounts(
        today = settings.getInt("${prefix}today", 0),
        semester = settings.getInt("${prefix}semester", 0),
        term = term,
        dayCounts = days,
        updatedAt = settings.getString("${prefix}updated", ""),
    )
  }

  override fun saveCounts(studentId: String, counts: TdStoredCounts) {
    val prefix = countsPrefix(studentId)
    settings.putInt("${prefix}today", counts.today)
    settings.putInt("${prefix}semester", counts.semester)
    settings.putString("${prefix}term", counts.term)
    settings.putString(
        "${prefix}days",
        counts.dayCounts.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" },
    )
    settings.putString("${prefix}updated", counts.updatedAt)
  }

  private fun credentialKey(username: String) = "td-healthcloud:$username"

  private fun countsPrefix(studentId: String) = "td_counts_${studentId}_"

  private fun assetPath(name: String) = "td-default-images/$name"

  private companion object {
    const val KEY_STUDENT_ID = "td_student_id"
    const val KEY_CARD_ID = "td_card_id"
    const val KEY_CAMPUS = "td_campus"
    const val KEY_ENTRANCE = "td_entrance_machine"
    const val KEY_EXIT = "td_exit_machine"
    const val KEY_HEALTH_CLOUD_USERNAME = "td_healthcloud_username"
    val DEFAULT_IMAGE_NAMES = listOf("image1.jpg", "image2.jpg", "image3.jpg")
  }
}
