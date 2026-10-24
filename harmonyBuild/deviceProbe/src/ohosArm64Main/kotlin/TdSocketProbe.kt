@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.bucc.probe

import app.bucc.td.HarmonyTdSocket
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.INADDR_LOOPBACK
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLNVAL
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SHUT_WR
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.htonl
import platform.posix.listen
import platform.posix.ntohs
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

internal suspend fun tdSocketProbe() =
    withTimeout(PROBE_TIMEOUT_MILLIS) {
      probeExchangeAndClose()
      probeCancelledReadClosesPeer()
      probeExplicitCloseUnblocksRead()
    }

private suspend fun probeExchangeAndClose() = coroutineScope {
  val listener = openLoopbackListener()
  val server =
      async(Dispatchers.Default) {
        withAcceptedClient(listener.descriptor) { client ->
          check(readExact(client, HELLO.size).contentEquals(HELLO)) {
            "TD loopback request mismatch"
          }
          sendAll(client, REPLY.copyOfRange(0, 3))
          delay(SPLIT_REPLY_DELAY_MILLIS)
          sendAll(client, REPLY.copyOfRange(3, REPLY.size))
          check(shutdown(client, SHUT_WR) == 0) { "TD loopback write shutdown failed" }
        }
      }
  val client = HarmonyTdSocket(LOOPBACK_HOST, listener.port)
  try {
    client.connect()
    client.write(HELLO.copyOf())
    val response = ArrayList<Byte>()
    while (true) {
      val part = client.read(SMALL_READ_BYTES)
      if (part.isEmpty()) break
      response.addAll(part.toList())
    }
    check(response.toByteArray().contentEquals(REPLY)) { "TD loopback response mismatch" }
    server.await()

    client.close()
    client.close()
    expectFailure { client.write(HELLO) }
    expectFailure { client.connect() }
  } finally {
    client.close()
    server.cancelAndJoin()
    closeDescriptor(listener.descriptor)
  }
}

private suspend fun probeCancelledReadClosesPeer() = coroutineScope {
  val listener = openLoopbackListener()
  val accepted = CompletableDeferred<Unit>()
  val server =
      async(Dispatchers.Default) {
        withAcceptedClient(listener.descriptor) { client ->
          accepted.complete(Unit)
          check(readUntilEof(client)) { "TD loopback peer sent unexpected data" }
        }
      }
  val client = HarmonyTdSocket(LOOPBACK_HOST, listener.port)
  try {
    client.connect()
    accepted.await()
    val read = async { withTimeout(READ_CANCEL_MILLIS) { client.read(1) } }
    val failure = runCatching { read.await() }.exceptionOrNull()
    check(failure is TimeoutCancellationException) { "TD loopback read did not time out" }
    server.await()
    expectFailure { client.write(HELLO) }
  } finally {
    client.close()
    server.cancelAndJoin()
    closeDescriptor(listener.descriptor)
  }
}

private suspend fun probeExplicitCloseUnblocksRead() = coroutineScope {
  val listener = openLoopbackListener()
  val accepted = CompletableDeferred<Unit>()
  val server =
      async(Dispatchers.Default) {
        withAcceptedClient(listener.descriptor) { client ->
          accepted.complete(Unit)
          check(readUntilEof(client)) { "TD loopback peer sent unexpected data" }
        }
      }
  val client = HarmonyTdSocket(LOOPBACK_HOST, listener.port)
  var read: kotlinx.coroutines.Deferred<Result<ByteArray>>? = null
  try {
    client.connect()
    accepted.await()
    delay(READ_CLOSE_DELAY_MILLIS)
    val readJob = async { runCatching { client.read(1) } }
    read = readJob
    delay(READ_CLOSE_DELAY_MILLIS)
    client.close()
    val failure = withTimeout(CLOSE_READ_TIMEOUT_MILLIS) { readJob.await().exceptionOrNull() }
    check(failure != null && failure !is CancellationException) {
      "TD loopback read did not stop after close"
    }
    server.await()
  } finally {
    client.close()
    read?.cancelAndJoin()
    server.cancelAndJoin()
    closeDescriptor(listener.descriptor)
  }
}

private suspend fun expectFailure(block: suspend () -> Unit) {
  val failure = runCatching { block() }.exceptionOrNull()
  check(failure != null && failure !is CancellationException) {
    "Closed TD socket accepted an operation"
  }
}

private data class LoopbackListener(val descriptor: Int, val port: Int)

private fun openLoopbackListener(): LoopbackListener {
  val descriptor = socket(AF_INET, SOCK_STREAM, 0)
  check(descriptor >= 0) { "Could not create TD loopback listener" }
  try {
    setNonBlocking(descriptor)
    val port = memScoped {
      val address = alloc<sockaddr_in>()
      address.sin_family = AF_INET.convert()
      address.sin_port = 0u.toUShort()
      address.sin_addr.s_addr = htonl(INADDR_LOOPBACK)
      check(
          bind(descriptor, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) ==
              0
      ) {
        "Could not bind TD loopback listener"
      }
      check(listen(descriptor, 1) == 0) { "Could not listen on TD loopback socket" }
      val addressLength = alloc<socklen_tVar>()
      addressLength.value = sizeOf<sockaddr_in>().convert()
      check(getsockname(descriptor, address.ptr.reinterpret<sockaddr>(), addressLength.ptr) == 0) {
        "Could not read TD loopback port"
      }
      ntohs(address.sin_port).toInt()
    }
    check(port in 1..65_535) { "TD loopback listener returned an invalid port" }
    return LoopbackListener(descriptor, port)
  } catch (failure: Throwable) {
    closeDescriptor(descriptor)
    throw failure
  }
}

private suspend fun withAcceptedClient(listener: Int, block: suspend (Int) -> Unit) {
  val deadline = TimeSource.Monotonic.markNow()
  var client = INVALID_DESCRIPTOR
  try {
    while (client < 0) {
      waitForEvent(listener, POLLIN, deadline)
      client = accept(listener, null, null)
      if (client < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
        error("Could not accept TD loopback connection")
      }
    }
    setNonBlocking(client)
    block(client)
  } finally {
    closeDescriptor(client)
  }
}

private suspend fun readExact(descriptor: Int, size: Int): ByteArray {
  val result = ByteArray(size)
  var offset = 0
  val deadline = TimeSource.Monotonic.markNow()
  while (offset < result.size) {
    waitForEvent(descriptor, POLLIN, deadline)
    val count =
        result.usePinned { pinned ->
          recv(descriptor, pinned.addressOf(offset), (result.size - offset).convert(), 0)
        }
    when {
      count > 0 -> offset += count.toInt()
      count == 0L -> error("TD loopback client closed before sending the fixture")
      errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK -> Unit
      else -> error("Could not read TD loopback fixture")
    }
  }
  return result
}

private suspend fun readUntilEof(descriptor: Int): Boolean {
  val deadline = TimeSource.Monotonic.markNow()
  val buffer = ByteArray(1)
  while (true) {
    waitForEvent(descriptor, POLLIN, deadline)
    val count = buffer.usePinned { pinned -> recv(descriptor, pinned.addressOf(0), 1uL, 0) }
    when {
      count == 0L -> return true
      count > 0 -> return false
      errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK -> Unit
      else -> error("Could not observe TD loopback EOF")
    }
  }
}

private suspend fun sendAll(descriptor: Int, bytes: ByteArray) {
  val deadline = TimeSource.Monotonic.markNow()
  var offset = 0
  while (offset < bytes.size) {
    waitForEvent(descriptor, POLLOUT, deadline)
    val count =
        bytes.usePinned { pinned ->
          send(
              descriptor,
              pinned.addressOf(offset),
              (bytes.size - offset).convert(),
              MSG_NOSIGNAL,
          )
        }
    when {
      count > 0 -> offset += count.toInt()
      count == 0L -> error("TD loopback write returned zero")
      errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK -> Unit
      else -> error("Could not write TD loopback fixture")
    }
  }
}

private suspend fun waitForEvent(descriptor: Int, events: Int, deadline: TimeMark) {
  while (true) {
    currentCoroutineContext().ensureActive()
    val remaining = PROBE_IO_TIMEOUT_MILLIS - deadline.elapsedNow().inWholeMilliseconds
    check(remaining > 0) { "TD loopback operation timed out" }
    val result = memScoped {
      val request = alloc<pollfd>()
      request.fd = descriptor
      request.events = events.convert()
      request.revents = 0
      val count = poll(request.ptr, 1uL, minOf(remaining, MAX_POLL_WAIT_MILLIS).toInt())
      count to request.revents.toInt()
    }
    if (result.first < 0) {
      if (errno == EINTR) continue
      error("TD loopback poll failed")
    }
    if (result.first == 0) continue
    val returned = result.second
    check(returned and POLLNVAL == 0 && returned and POLLERR == 0) {
      "TD loopback poll reported an error"
    }
    if (returned and events != 0 || returned and POLLHUP != 0) return
    error("TD loopback poll returned an unexpected event")
  }
}

private fun setNonBlocking(descriptor: Int) {
  val flags = fcntl(descriptor, F_GETFL)
  check(flags >= 0 && fcntl(descriptor, F_SETFL, flags or O_NONBLOCK) >= 0) {
    "Could not set TD loopback socket nonblocking"
  }
}

private fun closeDescriptor(descriptor: Int) {
  if (descriptor >= 0) {
    shutdown(descriptor, SHUT_RDWR)
    close(descriptor)
  }
}

private val HELLO = byteArrayOf(0x48, 0x45, 0x4c, 0x4c, 0x4f)
private val REPLY = byteArrayOf(0x52, 0x45, 0x50, 0x4c, 0x59, 0x21)
private const val LOOPBACK_HOST = "127.0.0.1"
private const val SMALL_READ_BYTES = 2
private const val SPLIT_REPLY_DELAY_MILLIS = 25L
private const val READ_CANCEL_MILLIS = 150L
private const val READ_CLOSE_DELAY_MILLIS = 100L
private const val CLOSE_READ_TIMEOUT_MILLIS = 1_000L
private const val PROBE_IO_TIMEOUT_MILLIS = 3_000L
private const val MAX_POLL_WAIT_MILLIS = 100L
private const val PROBE_TIMEOUT_MILLIS = 15_000L
private const val INVALID_DESCRIPTOR = -1
