package app.bucc.ui.screens.schedule

actual fun calendarImportAvailable(): Boolean = false

actual suspend fun importCourseCalendar(
    accountId: String,
    termName: String,
    events: List<CalendarCourseEvent>,
    reminderMinutes: Int?,
): Result<CalendarImportResult> = Result.failure(UnsupportedOperationException("日历直接导入仅支持 iOS"))
