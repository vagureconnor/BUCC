@file:OptIn(
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package app.bucc.probe

import app.bucc.platform.HarmonyAppDirectories
import app.bucc.security.CampusCredential
import app.bucc.security.HuksCampusCredentialStorage
import buccCryptoProbe
import com.russhwolf.settings.Settings
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.mkdir

/** Only compiled with bucc.harmony.nativeProbe=true; every identity and value is synthetic. */
@kotlin.native.CName("bucc_shared_probe")
fun sharedDeviceProbe(filesDir: CPointer<ByteVar>, cacheDir: CPointer<ByteVar>): Int {
  var stage = 1
  return try {
    val suffix = Uuid.random().toString()
    val files = filesDir.toKString() + "/bucc-component-probe-$suffix"
    val cache = cacheDir.toKString() + "/bucc-component-probe-$suffix"
    check(mkdir(files, 448u) == 0 && mkdir(cache, 448u) == 0)
    HarmonyAppDirectories.initialize(files, cache)
    stage = 2
    check(buccCryptoProbe() == 1)
    stage = 3
    runBlocking { secureStorageProbe() }
    stage = 4
    imageDiagnosticProbe()
    stage = 5
    runBlocking { tdSocketProbe() }
    stage = 6
    avatarCompressionProbe()
    stage = 8
    rsaProbe()
    stage = 7
    runBlocking { nativeHttpProbe() }
    if (imageSymlinkProbeSkipped) 2 else 1
  } catch (error: Throwable) {
    if (stage == 4 && imageProbeStep == 401) {
      val code =
          Regex("ImageKit code ([0-9]+)")
              .find(error.message.orEmpty())
              ?.groupValues
              ?.get(1)
              ?.toIntOrNull()
      if (code != null) -400_000_000 - code
      else
          when {
            error.message.orEmpty().contains("row stride") -> -481
            error.message.orEmpty().contains("pixel format") -> -482
            error.message.orEmpty().contains("incomplete") -> -483
            error.message.orEmpty().contains("alpha type") -> -484
            else -> -401
          }
    } else if (stage == 4) -imageProbeStep else if (stage == 6) -avatarProbeStep else -stage
  }
}

private suspend fun secureStorageProbe() {
  val settings = Settings()
  val store = HuksCampusCredentialStorage(settings)
  val userA = "fixture-harmony-a-" + Uuid.random()
  val userB = "fixture-harmony-b-" + Uuid.random()
  val password = "synthetic-密码-🧪"
  check(store.load(userA) == null && store.load(userB) == null)
  try {
    val before = settings.keys
    store.save(CampusCredential(userA, password))
    val firstKey = (settings.keys - before).single { it.startsWith("campus_credential_") }
    val firstCiphertext = settings.getString(firstKey, "")
    check(firstCiphertext.isNotEmpty() && !firstCiphertext.contains(password))
    check(HuksCampusCredentialStorage(Settings()).load(userA) == CampusCredential(userA, password))
    store.save(CampusCredential(userA, password))
    check(settings.getString(firstKey, "") != firstCiphertext)
    store.save(CampusCredential(userB, "another-synthetic-password"))
    check(store.load(userA)?.password == password)
    check(store.load(userB)?.password == "another-synthetic-password")
    val saved = settings.getString(firstKey, "")
    val tampered =
        Base64.decode(saved).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    settings.putString(firstKey, Base64.encode(tampered))
    check(runCatching { store.load(userA) }.isFailure)
    settings.putString(firstKey, saved)
    check(store.load(userA)?.password == password)
    store.save(CampusCredential(userA, ""))
    check(store.load(userA)?.password == "")
    store.delete(userA)
    check(store.load(userA) == null)
    check(store.load(userB)?.password == "another-synthetic-password")
    store.delete(userA)
  } finally {
    store.delete(userA)
    store.delete(userB)
  }
}
