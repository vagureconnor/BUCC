@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package app.bucc.td

import kotlin.concurrent.atomics.AtomicInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLNVAL
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.htonl
import platform.posix.htons
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

internal class HarmonyTdSocket(
    private val host: String,
    private val port: Int,
) : TdSocket {
  private val mutex = Mutex()
  private val closed = AtomicInt(0)
  private var descriptor = INVALID_DESCRIPTOR

  override suspend fun connect() =
      withIoLock(CONNECT_ERROR) {
        if (descriptor >= 0) return@withIoLock
        require(port in 1..MAX_PORT) { CONNECT_ERROR }
        val started = TimeSource.Monotonic.markNow()

        val socketFd = socket(AF_INET, SOCK_STREAM, 0)
        if (socketFd < 0) throw TdProtocolException(CONNECT_ERROR)
        descriptor = socketFd

        val flags = fcntl(socketFd, F_GETFL)
        if (flags < 0 || fcntl(socketFd, F_SETFL, flags or O_NONBLOCK) < 0) {
          throw TdProtocolException(CONNECT_ERROR)
        }

        memScoped {
          val address = alloc<sockaddr_in>()
          address.sin_family = AF_INET.convert()
          address.sin_port = htons(port.convert())
          address.sin_addr.s_addr =
              htonl(parseIpv4(host) ?: throw TdProtocolException(CONNECT_ERROR))
          val result =
              connect(
                  socketFd,
                  address.ptr.reinterpret<sockaddr>(),
                  kotlinx.cinterop.sizeOf<sockaddr_in>().convert(),
              )
          if (result == 0) return@memScoped
          if (errno != EINPROGRESS && errno != EINTR) throw TdProtocolException(CONNECT_ERROR)
        }

        val readyEvents =
            awaitReady(
                socketFd,
                POLLOUT,
                started,
                CONNECT_ERROR,
                allowHangup = true,
                allowError = true,
            )
        memScoped {
          val socketError = alloc<IntVar>()
          socketError.value = 0
          val length = alloc<socklen_tVar>()
          length.value = kotlinx.cinterop.sizeOf<IntVar>().convert()
          if (
              getsockopt(socketFd, SOL_SOCKET, SO_ERROR, socketError.ptr, length.ptr) != 0 ||
                  socketError.value != 0 ||
                  readyEvents and POLLOUT == 0
          ) {
            throw TdProtocolException(CONNECT_ERROR)
          }
        }
      }

  override suspend fun write(bytes: ByteArray) =
      withIoLock(WRITE_ERROR) {
        val socketFd = requireConnected()
        val started = TimeSource.Monotonic.markNow()
        var offset = 0
        while (offset < bytes.size) {
          currentCoroutineContext().ensureActive()
          ensureOpen()
          ensureWithinTimeout(started, WRITE_ERROR)
          awaitReady(socketFd, POLLOUT, started, WRITE_ERROR)
          val count =
              bytes.usePinned { pinned ->
                send(
                    socketFd,
                    pinned.addressOf(offset),
                    (bytes.size - offset).convert(),
                    MSG_NOSIGNAL,
                )
              }
          when {
            count > 0 -> offset += count.toInt()
            count == 0L -> throw TdProtocolException(WRITE_ERROR)
            errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK -> Unit
            else -> throw TdProtocolException(WRITE_ERROR)
          }
        }
      }

  override suspend fun read(maxBytes: Int): ByteArray =
      withIoLock(READ_ERROR) {
        require(maxBytes in 1..MAX_READ_BYTES) { READ_ERROR }
        val socketFd = requireConnected()
        val started = TimeSource.Monotonic.markNow()
        while (true) {
          currentCoroutineContext().ensureActive()
          ensureOpen()
          ensureWithinTimeout(started, READ_ERROR)
          val readyEvents = awaitReady(socketFd, POLLIN, started, READ_ERROR, allowHangup = true)
          if (readyEvents and POLLIN != 0) Unit
          else if (readyEvents and POLLHUP != 0) {
            closeDescriptor()
            return@withIoLock ByteArray(0)
          }
          val buffer = ByteArray(maxBytes)
          val count =
              buffer.usePinned { pinned ->
                recv(socketFd, pinned.addressOf(0), buffer.size.convert(), 0)
              }
          when {
            count > 0 -> return@withIoLock buffer.copyOf(count.toInt())
            count == 0L -> {
              closeDescriptor()
              return@withIoLock ByteArray(0)
            }
            errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK -> Unit
            else -> throw TdProtocolException(READ_ERROR)
          }
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
      }

  override fun close() {
    if (!closed.compareAndSet(0, 1)) return
    cleanupScope.launch { mutex.withLock { closeDescriptor() } }
  }

  private suspend fun awaitReady(
      socketFd: Int,
      events: Int,
      started: TimeMark,
      operationError: String,
      allowHangup: Boolean = false,
      allowError: Boolean = false,
  ): Int {
    while (true) {
      currentCoroutineContext().ensureActive()
      ensureOpen()
      val remaining = ensureWithinTimeout(started, operationError)
      val result = memScoped {
        val pollRequest = alloc<pollfd>()
        pollRequest.fd = socketFd
        pollRequest.events = events.convert()
        pollRequest.revents = 0
        val count =
            poll(pollRequest.ptr, 1.convert(), minOf(POLL_INTERVAL_MILLIS, remaining).toInt())
        count to pollRequest.revents.toInt()
      }
      if (result.first < 0) {
        if (errno == EINTR) continue
        throw TdProtocolException(operationError)
      }
      if (result.first == 0) continue
      val returnedEvents = result.second
      if (returnedEvents and POLLNVAL != 0) throw TdProtocolException(operationError)
      if (returnedEvents and events != 0) return returnedEvents
      if (returnedEvents and POLLERR != 0) {
        if (allowError) return returnedEvents
        throw TdProtocolException(operationError)
      }
      if (returnedEvents and POLLHUP != 0) {
        if (allowHangup) return returnedEvents
        throw TdProtocolException(operationError)
      }
      throw TdProtocolException(operationError)
    }
  }

  private fun parseIpv4(value: String): UInt? {
    val octets = value.split('.')
    if (octets.size != 4) return null
    var address = 0u
    for (octet in octets) {
      if (octet.isEmpty() || octet.any { it !in '0'..'9' }) return null
      val number = octet.toUIntOrNull() ?: return null
      if (number > 255u) return null
      address = (address shl 8) or number
    }
    return address
  }

  private fun requireConnected(): Int {
    ensureOpen()
    return descriptor.takeIf { it >= 0 } ?: throw TdProtocolException(CONNECT_ERROR)
  }

  private fun ensureOpen() {
    if (closed.load() != 0) throw TdProtocolException(CONNECT_ERROR)
  }

  private fun ensureWithinTimeout(
      started: TimeMark,
      operationError: String,
  ): Long {
    val elapsed = started.elapsedNow().inWholeMilliseconds
    val remaining = TdDefaults.socketTimeoutMillis - elapsed
    if (remaining <= 0) throw TdProtocolException(operationError)
    return remaining
  }

  private suspend fun <T> withIoLock(operationError: String, block: suspend () -> T): T =
      withContext(Dispatchers.Default) {
        mutex.withLock {
          ensureOpen()
          try {
            block()
          } catch (cause: CancellationException) {
            closeDescriptor()
            throw cause
          } catch (cause: TdProtocolException) {
            closeDescriptor()
            throw cause
          } catch (_: Throwable) {
            closeDescriptor()
            throw TdProtocolException(operationError)
          }
        }
      }

  private fun closeDescriptor() {
    val socketFd = descriptor
    descriptor = INVALID_DESCRIPTOR
    if (socketFd >= 0) {
      shutdown(socketFd, SHUT_RDWR)
      close(socketFd)
    }
  }

  private companion object {
    const val INVALID_DESCRIPTOR = -1
    const val MAX_PORT = 65_535
    const val MAX_READ_BYTES = 8 * 1024 * 1024
    const val POLL_INTERVAL_MILLIS = 50L
    const val CONNECT_ERROR = "TD 服务器连接失败，请检查网络或校园 VPN 后重试"
    const val WRITE_ERROR = "TD 请求发送失败，请检查网络后重试"
    const val READ_ERROR = "TD 响应读取失败或连接超时，请检查网络后重试"

    val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
