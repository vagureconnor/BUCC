package app.bucc.api.finance

import app.bucc.api.plantform.PlatformAesEcbPkcs5Padding
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.parseQueryString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CampusCardCodec {
  private const val KEY_LENGTH = 16
  private const val KEY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

  @OptIn(ExperimentalEncodingApi::class)
  fun encode(payload: JsonObject, key: String = randomKey()): JsonObject {
    require(key.length == KEY_LENGTH && key.all(::isAsciiAlphaNumeric)) { "校园卡请求密钥无效" }
    val prefix = key.drop(10) + key.take(10)
    val encrypted =
        PlatformAesEcbPkcs5Padding.encrypt(
            payload.toString().encodeToByteArray(),
            key.encodeToByteArray(),
        )
    return buildJsonObject { put("datajson", prefix.reversed() + Base64.encode(encrypted)) }
  }

  @OptIn(ExperimentalEncodingApi::class)
  fun decode(payload: String): JsonObject {
    val response = parseJsonObject(payload) ?: invalidPayload()
    val datajson = response["datajson"] ?: return response
    val wrapped =
        (datajson as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalidPayload()
    if (wrapped.length <= KEY_LENGTH) invalidPayload()

    val prefix = wrapped.take(KEY_LENGTH)
    if (!prefix.all(::isAsciiAlphaNumeric)) invalidPayload()
    val rotatedKey = prefix.reversed()
    val key = rotatedKey.drop(6) + rotatedKey.take(6)
    val ciphertext =
        try {
          Base64.decode(wrapped.drop(KEY_LENGTH))
        } catch (_: Exception) {
          invalidPayload()
        }
    if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) invalidPayload()

    val plaintext =
        try {
          PlatformAesEcbPkcs5Padding.decrypt(ciphertext, key.encodeToByteArray())
              .decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
          invalidPayload()
        }
    return parseJsonObject(plaintext) ?: invalidPayload()
  }

  private fun randomKey(): String =
      buildString(KEY_LENGTH) {
        repeat(KEY_LENGTH) { append(KEY_CHARS[Random.nextInt(KEY_CHARS.length)]) }
      }

  private fun isAsciiAlphaNumeric(char: Char): Boolean =
      char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9'

  private fun parseJsonObject(value: String): JsonObject? =
      runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()

  private fun invalidPayload(): Nothing = throw IllegalArgumentException("校园卡响应数据无法识别")
}

internal class CampusCardIdentity(val openid: String, val usertype: String) {
  override fun toString(): String = "CampusCardIdentity([redacted])"
}

internal fun campusCardIdentityFromUrl(url: String): CampusCardIdentity? {
  val parsed = runCatching { Url(url) }.getOrNull() ?: return null
  if (
      parsed.protocol != URLProtocol.HTTPS ||
          !parsed.host.equals("m.icw.buaa.edu.cn", ignoreCase = true) ||
          parsed.port != 443 ||
          !parsed.user.isNullOrEmpty() ||
          !parsed.password.isNullOrEmpty()
  ) {
    return null
  }

  if (parsed.encodedPath == "/card" || parsed.encodedPath == "/card/") {
    val fragment = parsed.fragment
    if (!fragment.startsWith("/pages/homepage/index/index?")) return null
    val parameters = parseQueryString(fragment.substringAfter('?'))
    val openid = parameters.getAll("openid")?.singleOrNull()
    val usertype = parameters.getAll("usertype")?.singleOrNull()
    return identityOrNull(openid, usertype)
  }
  return null
}

private fun identityOrNull(openid: String?, usertype: String?): CampusCardIdentity? {
  if (openid == null || usertype == null) return null
  if (!openid.matches(Regex("[A-Za-z0-9._~-]{1,128}"))) return null
  if (!usertype.matches(Regex("[A-Za-z0-9_-]{1,32}"))) return null
  return CampusCardIdentity(openid, usertype)
}

/** Converts positive yuan to an exact integer fen amount, bounded by 1000 yuan. */
internal fun campusCardAmountInFen(amount: String): String? {
  val match = Regex("^(\\d+)(?:\\.(\\d{1,2}))?$").matchEntire(amount.trim()) ?: return null
  val yuan = match.groupValues[1].trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: return null
  val fen = match.groupValues[2].padEnd(2, '0').toIntOrNull() ?: 0
  if (yuan > 1000 || (yuan == 1000L && fen > 0)) return null
  val totalFen = yuan * 100 + fen
  return totalFen.takeIf { it > 0 }?.toString()
}
