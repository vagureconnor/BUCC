package app.bucc.attendance

import app.bucc.model.dto.Term

/** IDs are upstream identities; course names must never be used for deduplication. */
data class AttendanceCourse(val id: String, val name: String)

data class AttendanceRecord(
    val id: String,
    val courseId: String,
    val date: String,
    val time: String = "",
    val status: String,
)

data class AttendanceSemester(
    val courses: List<AttendanceCourse>,
    val records: List<AttendanceRecord>,
)

interface AttendanceApi {
  suspend fun getTerms(): Result<List<Term>>

  suspend fun getSemester(termCode: String): Result<AttendanceSemester>
}
