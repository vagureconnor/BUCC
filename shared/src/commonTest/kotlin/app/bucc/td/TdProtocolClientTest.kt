package app.bucc.td

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

class TdProtocolClientTest {
  @Test
  fun reassemblesSplitHeaderAndBody() = runTest {
    val body = """{"status":"success"}""".encodeToByteArray()
    val response = tdFrame(type = 80, payload = body)
    val socket =
        FakeTdSocket(
            reads =
                listOf(
                    response.copyOfRange(0, 2),
                    response.copyOfRange(2, 5),
                    response.copyOfRange(5, 9),
                    response.copyOfRange(9, response.size),
                )
        )
    val client = TdProtocolClient(socketFactory = TdSocketFactory { _, _ -> socket })

    val result = client.request(payload = "{}".encodeToByteArray(), type = 80)

    assertEquals("success", result["status"]?.jsonPrimitive?.content)
    assertContentEquals(tdFrame(80, "{}".encodeToByteArray()), socket.writes.single())
    assertTrue(socket.closed)
  }

  @Test
  fun rejectsResponseWithWrongType() = runTest {
    val socket = FakeTdSocket(listOf(tdFrame(100, "{}".encodeToByteArray())))
    val client = TdProtocolClient(socketFactory = TdSocketFactory { _, _ -> socket })

    val error = assertFailsWith<TdProtocolException> { client.request(ByteArray(0), 80) }

    assertTrue(error.message.orEmpty().contains("expected 80, received 100"))
    assertTrue(socket.closed)
  }

  @Test
  fun rejectsTruncatedResponseBody() = runTest {
    val declaredLength = byteArrayOf(0, 0, 0, 12, 80)
    val socket = FakeTdSocket(listOf(declaredLength, "{}".encodeToByteArray()))
    val client = TdProtocolClient(socketFactory = TdSocketFactory { _, _ -> socket })

    val error = assertFailsWith<TdProtocolException> { client.request(ByteArray(0), 80) }

    assertTrue(error.message.orEmpty().contains("response body"))
  }

  @Test
  fun timesOutAndClosesSocket() = runTest {
    val socket = SuspendingTdSocket()
    val client =
        TdProtocolClient(
            socketFactory = TdSocketFactory { _, _ -> socket },
            timeoutMillis = 50,
        )

    assertFailsWith<TimeoutCancellationException> { client.request(ByteArray(0), 80) }

    assertTrue(socket.closed)
  }

  @Test
  fun callerCancellationClosesSocket() = runTest {
    val socket = SuspendingTdSocket()
    val client = TdProtocolClient(socketFactory = TdSocketFactory { _, _ -> socket })

    val request = launch { client.request(ByteArray(0), 80) }
    testScheduler.runCurrent()
    request.cancelAndJoin()

    assertTrue(socket.closed)
  }
}

private class FakeTdSocket(private val reads: List<ByteArray>) : TdSocket {
  val writes = mutableListOf<ByteArray>()
  var closed = false
  private var readIndex = 0

  override suspend fun connect() = Unit

  override suspend fun write(bytes: ByteArray) {
    writes += bytes
  }

  override suspend fun read(maxBytes: Int): ByteArray {
    if (readIndex >= reads.size) return ByteArray(0)
    return reads[readIndex++].let { if (it.size <= maxBytes) it else it.copyOf(maxBytes) }
  }

  override fun close() {
    closed = true
  }
}

private class SuspendingTdSocket : TdSocket {
  var closed = false

  override suspend fun connect() = Unit

  override suspend fun write(bytes: ByteArray) = Unit

  override suspend fun read(maxBytes: Int): ByteArray = awaitCancellation()

  override fun close() {
    closed = true
  }
}
