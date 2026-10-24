package app.bucc.td

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

const val TD_CHECK_REQUEST_TYPE: Int = 80
const val TD_PHOTO_REQUEST_TYPE: Int = 100

private const val TD_HEADER_SIZE = 5
private const val TD_MAX_RESPONSE_BYTES = 8 * 1024 * 1024

fun interface TdSocketFactory {
  fun open(host: String, port: Int): TdSocket
}

interface TdSocket {
  suspend fun connect()

  suspend fun write(bytes: ByteArray)

  /** Returns up to [maxBytes], or an empty array when the peer closes the connection. */
  suspend fun read(maxBytes: Int): ByteArray

  fun close()
}

class TdProtocolException(message: String) : Exception(message)

class TdProtocolClient(
    private val host: String = TdDefaults.serverHost,
    private val port: Int = TdDefaults.serverPort,
    private val socketFactory: TdSocketFactory,
    private val timeoutMillis: Long = TdDefaults.socketTimeoutMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
  suspend fun request(payload: ByteArray, type: Int): JsonObject {
    require(type in 0..255) { "TD request type must fit in one byte" }
    val socket = socketFactory.open(host, port)
    return try {
      withTimeout(timeoutMillis) {
        socket.connect()
        socket.write(tdFrame(type, payload))
        val header = socket.readExactly(TD_HEADER_SIZE, "response header")
        val length = decodeInt32BigEndian(header)
        val responseType = header[4].toInt() and 0xff
        if (responseType != type) {
          throw TdProtocolException(
              "TD response type mismatch: expected $type, received $responseType"
          )
        }
        if (length <= 0 || length > TD_MAX_RESPONSE_BYTES) {
          throw TdProtocolException("Invalid TD response length: $length")
        }
        val body = socket.readExactly(length, "response body")
        runCatching { json.parseToJsonElement(body.decodeToString()).let { it as JsonObject } }
            .getOrElse { throw TdProtocolException("TD response is not a JSON object") }
      }
    } finally {
      socket.close()
    }
  }
}

fun tdFrame(type: Int, payload: ByteArray): ByteArray {
  require(type in 0..255) { "TD frame type must fit in one byte" }
  val frame = ByteArray(TD_HEADER_SIZE + payload.size)
  frame[0] = (payload.size ushr 24).toByte()
  frame[1] = (payload.size ushr 16).toByte()
  frame[2] = (payload.size ushr 8).toByte()
  frame[3] = payload.size.toByte()
  frame[4] = type.toByte()
  payload.copyInto(frame, destinationOffset = TD_HEADER_SIZE)
  return frame
}

private suspend fun TdSocket.readExactly(size: Int, label: String): ByteArray {
  val result = ByteArray(size)
  var offset = 0
  while (offset < size) {
    val chunk = read(size - offset)
    if (chunk.isEmpty()) {
      throw TdProtocolException("TD connection closed while reading $label")
    }
    if (chunk.size > size - offset) {
      throw TdProtocolException("TD transport returned more bytes than requested")
    }
    chunk.copyInto(result, destinationOffset = offset)
    offset += chunk.size
  }
  return result
}

private fun decodeInt32BigEndian(bytes: ByteArray): Int =
    ((bytes[0].toInt() and 0xff) shl 24) or
        ((bytes[1].toInt() and 0xff) shl 16) or
        ((bytes[2].toInt() and 0xff) shl 8) or
        (bytes[3].toInt() and 0xff)

object TdDefaults {
  const val serverHost = "10.212.28.38"
  const val serverPort = 8888
  const val schoolNumber = "10006"
  const val eventNumber = "802"
  const val socketTimeoutMillis = 10_000L
  const val healthCloudBaseUrl = "http://10.212.28.38/"
}
