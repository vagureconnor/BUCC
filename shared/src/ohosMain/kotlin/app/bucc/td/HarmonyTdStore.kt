package app.bucc.td

import app.bucc.security.CampusCredential
import app.bucc.security.CampusSecureCredentials
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class HarmonyTdStore(private val settings: Settings = Settings()) : TdDirectStore {
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
      runBlocking(Dispatchers.Default) {
        CampusSecureCredentials.load(credentialKey(studentId))?.password
      }

  override fun savePassword(studentId: String, password: String) {
    runBlocking(Dispatchers.Default) {
      CampusSecureCredentials.save(CampusCredential(credentialKey(studentId), password))
    }
  }

  override fun deletePassword(studentId: String) {
    runBlocking(Dispatchers.Default) { CampusSecureCredentials.delete(credentialKey(studentId)) }
  }

  override fun listImages(): List<TdImage> {
    val images = HarmonyDefaultTdImages.images
    return DEFAULT_IMAGE_NAMES.map { name ->
      val bytes = images[name] ?: error("应用内置的 TD 图片不完整")
      TdImage(name = name, sizeBytes = bytes.size.toLong())
    }
  }

  override fun saveImage(name: String, bytes: ByteArray) = error("TD 打卡图片由应用提供")

  override fun deleteImage(name: String) = error("TD 打卡图片由应用提供")

  override fun randomImageBytes(): ByteArray {
    val images = HarmonyDefaultTdImages.images
    require(DEFAULT_IMAGE_NAMES.all { name -> images[name] != null }) { "应用内置的 TD 图片不完整" }
    return images.getValue(DEFAULT_IMAGE_NAMES.random()).copyOf()
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
