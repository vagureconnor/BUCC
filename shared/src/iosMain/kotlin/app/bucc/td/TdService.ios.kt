package app.bucc.td

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIApplication
import platform.posix.memcpy

actual fun createTdService(): TdService {
  val httpClient =
      HttpClient(Darwin) {
        followRedirects = false
        install(HttpCookies) { storage = TdHealthCloudCookies() }
        install(HttpTimeout) {
          requestTimeoutMillis = 15_000
          connectTimeoutMillis = 10_000
          socketTimeoutMillis = 15_000
        }
      }
  val socketFactory = TdSocketFactory { host, port -> IosBridgeTdSocket(host, port) }
  val protocol = TdProtocolClient(socketFactory = socketFactory)
  return TdDirectService(
      store = IosTdStore(),
      gateway = TdProtocolGateway(protocol),
      healthCloud = TdHealthCloudClient(httpClient),
      powerController =
          TdExecutionPowerController { active ->
            UIApplication.sharedApplication.idleTimerDisabled = active
          },
  )
}

private class IosBridgeTdSocket(
    private val host: String,
    private val port: Int,
) : TdSocket {
  private var response = ByteArray(0)
  private var offset = 0
  private var request: TdAppleCancellable? = null

  override suspend fun connect() = Unit

  override suspend fun write(bytes: ByteArray) {
    response = suspendCancellableCoroutine { continuation ->
      val callback =
          object : TdAppleNetworkCallback {
            override fun onSuccess(response: ByteArray) {
              if (continuation.isActive) continuation.resume(response)
            }

            override fun onFailure(message: String) {
              if (continuation.isActive) {
                continuation.resumeWithException(TdProtocolException(message))
              }
            }
          }
      request =
          TdAppleBridge.requirePlatform()
              .request(
                  host = host,
                  port = port,
                  bytes = bytes,
                  timeoutMillis = TdDefaults.socketTimeoutMillis,
                  callback = callback,
              )
      continuation.invokeOnCancellation { request?.cancel() }
    }
    offset = 0
  }

  override suspend fun read(maxBytes: Int): ByteArray {
    if (offset >= response.size) return ByteArray(0)
    val end = minOf(response.size, offset + maxBytes)
    return response.copyOfRange(offset, end).also { offset = end }
  }

  override fun close() {
    request?.cancel()
    request = null
    response = ByteArray(0)
    offset = 0
  }
}

private class IosTdStore(private val settings: Settings = Settings()) : TdDirectStore {
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
      TdAppleBridge.platformOrNull()?.password(studentId)

  override fun savePassword(studentId: String, password: String) {
    TdAppleBridge.requirePlatform().savePassword(studentId, password)
  }

  override fun deletePassword(studentId: String) {
    TdAppleBridge.platformOrNull()?.deletePassword(studentId)
  }

  override fun listImages(): List<TdImage> =
      DEFAULT_IMAGE_NAMES.mapNotNull { name ->
        val data = bundledImage(name) ?: return@mapNotNull null
        TdImage(name = name, sizeBytes = data.length.toLong())
      }

  override fun saveImage(name: String, bytes: ByteArray) = error("TD 打卡图片由应用提供")

  override fun deleteImage(name: String) = error("TD 打卡图片由应用提供")

  override fun randomImageBytes(): ByteArray {
    val images = listImages()
    require(images.size == DEFAULT_IMAGE_NAMES.size) { "应用内置的 TD 图片不完整" }
    val image = DEFAULT_IMAGE_NAMES.random()
    return (bundledImage(image) ?: error("读取内置 TD 图片失败：$image")).toByteArray()
  }

  private fun bundledImage(name: String): NSData? =
      NSBundle.mainBundle
          .pathForResource("td-default-${name.substringBeforeLast('.')}", ofType = "jpg")
          ?.let { NSData.dataWithContentsOfFile(it) }

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

  private fun countsPrefix(studentId: String) = "td_counts_${studentId}_"

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

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
  val result = ByteArray(length.toInt())
  if (result.isNotEmpty()) {
    result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
  }
  return result
}
