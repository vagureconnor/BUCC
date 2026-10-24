package app.bucc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bucc.td.TdProtocolException
import app.bucc.td.TdSocket
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android transport against loopback only; never contacts a campus TD endpoint. */
@RunWith(AndroidJUnit4::class)
class AndroidTdSocketTest {
  @Before
  fun requireDisposableEmulator() {
    assumeTrue(
        "Requires disposable emulator; pass buccFreshInstall=true",
        InstrumentationRegistry.getArguments().getString("buccFreshInstall") == "true",
    )
  }

  @Test(timeout = 15_000)
  fun connectsSendsAndReadsSyntheticBytesAndPeerEof() = runBlocking {
    val request = byteArrayOf(0, 0, 0, 2, 7, 42, 43)
    val response = byteArrayOf(0, 0, 0, 3, 7, 21, 22, 23)
    withLoopbackServer { server, executor, accepted ->
      val peerResult =
          executor.submit<ByteArray> {
            server.accept().use { peer ->
              accepted.set(peer)
              peer.soTimeout = 3_000
              val received = ByteArray(request.size)
              DataInputStream(peer.getInputStream()).readFully(received)
              peer.getOutputStream().apply {
                write(response)
                flush()
              }
              received
            }
          }
      val client = androidSocket(server.localPort)
      try {
        withTimeout(5_000) {
          client.connect()
          client.write(request)
          val received = mutableListOf<Byte>()
          while (received.size < response.size) {
            val part = client.read(2)
            assertTrue("Response must not end prematurely", part.isNotEmpty())
            assertTrue("read(maxBytes) must respect its limit", part.size <= 2)
            received.addAll(part.toList())
          }
          assertArrayEquals(response, received.toByteArray())
          assertArrayEquals(ByteArray(0), client.read(2))
        }
        assertArrayEquals(request, peerResult.get(3, TimeUnit.SECONDS))
      } finally {
        client.close()
      }
    }
  }

  @Test(timeout = 15_000)
  fun cancelingSuspendedReadClosesSocketAndDoesNotHang() = runBlocking {
    withLoopbackServer { server, executor, accepted ->
      val peerResult =
          executor.submit<Int> {
            server.accept().use { peer ->
              accepted.set(peer)
              peer.soTimeout = 3_000
              peer.getOutputStream().apply {
                write(42)
                flush()
              }
              // No response is sent afterwards: client read remains suspended until cancellation.
              peer.getInputStream().read()
            }
          }
      val client = androidSocket(server.localPort)
      try {
        withTimeout(5_000) {
          client.connect()
          assertArrayEquals(byteArrayOf(42), client.read(1))
          val transport =
              client.javaClass.getDeclaredField("socket").apply { isAccessible = true }.get(client)
                  as Socket
          val pendingRead = launch(start = CoroutineStart.UNDISPATCHED) { client.read(1) }
          assertTrue("Read must be pending before cancellation", pendingRead.isActive)
          pendingRead.cancelAndJoin()
          assertTrue(pendingRead.isCancelled)
          assertTrue("Cancellation must close the underlying Android socket", transport.isClosed)
          assertTrue(
              "Closed transport must reject further writes",
              runCatching { client.write(byteArrayOf(1)) }.exceptionOrNull() is TdProtocolException,
          )
        }
        assertEquals(
            "Loopback peer must observe EOF after cancellation",
            -1,
            peerResult.get(3, TimeUnit.SECONDS),
        )
      } finally {
        client.close()
      }
    }
  }

  private fun androidSocket(port: Int): TdSocket =
      Class.forName("app.bucc.td.AndroidTdSocket")
          .getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
          .apply { isAccessible = true }
          .newInstance("127.0.0.1", port) as TdSocket

  private inline fun withLoopbackServer(
      block: (ServerSocket, java.util.concurrent.ExecutorService, AtomicReference<Socket?>) -> Unit,
  ) {
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3_000 }
    val executor =
        Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "bucc-td-loopback-fixture").apply { isDaemon = true }
        }
    val accepted = AtomicReference<Socket?>()
    try {
      block(server, executor, accepted)
    } finally {
      accepted.get()?.close()
      server.close()
      executor.shutdownNow()
      executor.awaitTermination(3, TimeUnit.SECONDS)
    }
  }
}
