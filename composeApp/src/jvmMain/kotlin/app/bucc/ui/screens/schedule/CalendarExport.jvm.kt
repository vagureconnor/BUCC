package app.bucc.ui.screens.schedule

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun interface CalendarCompletion : Callback {
  fun invoke(context: Pointer?, count: Int, status: Int)
}

private interface CalendarNative : Library {
  fun bucc_calendar_import(
      account: String,
      term: String,
      events: String,
      reminder: Int,
      completion: CalendarCompletion,
      context: Pointer,
  )
}

private val pendingImports =
    ConcurrentHashMap<Long, CompletableDeferred<Result<CalendarImportResult>>>()
private val importIds = AtomicLong(1)
private val calendarCompletion = CalendarCompletion { context, count, status ->
  context
      ?.let { pendingImports.remove(Pointer.nativeValue(it)) }
      ?.complete(
          when (status) {
            0 -> Result.success(CalendarImportResult(count, "课程提醒由系统日历管理"))
            1 -> Result.failure(IllegalStateException("未获得日历权限，请在系统设置中允许 BUCC 访问日历"))
            else -> Result.failure(IllegalStateException("日历导入失败，请检查系统日历账户后重试"))
          }
      )
}

private val calendarNative: CalendarNative? by lazy {
  if (!System.getProperty("os.name").startsWith("Mac", true)) null
  else
      runCatching {
            val resources = System.getProperty("compose.application.resources.dir")
            val library =
                listOfNotNull(
                        resources?.let { File(it, "native/libbucc-native.dylib") },
                        File("composeApp/build/native/libbucc-native.dylib"),
                        File("build/native/libbucc-native.dylib"),
                    )
                    .first { it.isFile }
            Native.load(library.absolutePath, CalendarNative::class.java)
          }
          .getOrNull()
}

actual fun calendarImportAvailable(): Boolean = calendarNative != null

actual suspend fun importCourseCalendar(
    accountId: String,
    termName: String,
    events: List<CalendarCourseEvent>,
    reminderMinutes: Int?,
): Result<CalendarImportResult> {
  val native =
      calendarNative ?: return Result.failure(UnsupportedOperationException("日历导入仅支持 iOS 和 macOS"))
  if (accountId.isBlank() || termName.isBlank())
      return Result.failure(IllegalArgumentException("请先选择账号和学期"))
  val id = importIds.getAndIncrement()
  val result = CompletableDeferred<Result<CalendarImportResult>>()
  pendingImports[id] = result
  try {
    withContext(Dispatchers.IO) {
      native.bucc_calendar_import(
          accountId,
          termName,
          calendarEventsJson(events),
          reminderMinutes ?: -1,
          calendarCompletion,
          Pointer.createConstant(id),
      )
    }
    return result.await()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    return Result.failure(IllegalStateException("日历服务暂不可用，请重试"))
  } finally {
    pendingImports.remove(id)
  }
}
