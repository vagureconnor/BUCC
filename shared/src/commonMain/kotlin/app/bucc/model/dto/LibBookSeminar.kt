package app.bucc.model.dto

import kotlinx.serialization.json.JsonObject

/**
 * Official library seminar contracts. Times are minutes after midnight, never local epoch times.
 */
data class LibBookSeminarRoom(
    val id: String,
    val name: String,
    val capacity: String = "",
    val description: String = "",
)

data class LibBookSeminarFloor(val id: String, val name: String)

data class LibBookSeminarBuilding(
    val id: String,
    val name: String,
    val floors: List<LibBookSeminarFloor> = emptyList(),
)

data class LibBookSeminarCampus(
    val id: String,
    val name: String,
    val buildings: List<LibBookSeminarBuilding>,
)

data class LibBookSeminarFilters(val campuses: List<LibBookSeminarCampus> = emptyList())

data class LibBookSeminarInterval(val start: Int, val end: Int)

data class LibBookSeminarCategory(val id: String, val name: String, val start: Int, val end: Int)

data class LibBookSeminarDay(
    val date: String,
    val start: Int,
    val end: Int,
    val step: Int,
    val minimum: Int,
    val maximum: Int,
    val occupied: List<LibBookSeminarInterval>,
    val status: String = "1",
    val minPeople: Int = 1,
) {
  fun permits(begin: Int, finish: Int, notBefore: Int = 0): Boolean =
      status in setOf("1", "-2", "-3") &&
          step > 0 &&
          begin >= maxOf(start, notBefore) &&
          finish <= end &&
          finish > begin &&
          finish - begin in minimum..maximum &&
          (begin - start) % step == 0 &&
          (finish - start) % step == 0 &&
          occupied.none { begin < it.end && finish > it.start }

  fun starts(notBefore: Int = 0): List<Int> =
      if (step <= 0 || end <= start) emptyList()
      else (start..end step step).filter { begin -> ends(begin, notBefore).isNotEmpty() }

  fun ends(begin: Int, notBefore: Int = 0): List<Int> =
      if (step <= 0 || end <= start) emptyList()
      else (start..end step step).filter { permits(begin, it, notBefore) }
}

data class LibBookSeminarDetail(
    val room: LibBookSeminarRoom,
    val days: List<LibBookSeminarDay>,
    val earlierPeriods: String,
    val categories: List<LibBookSeminarCategory> = emptyList(),
    val minPeople: Int = 1,
    val maxPeople: Int = 1,
    val titles: List<Pair<String, String>> = emptyList(),
    val requiresTitleChoice: Boolean = false,
    val requiresAttachment: Boolean = false,
    val rules: String = "",
    val typeId: String = "",
)

data class LibBookSeminarMember(val id: String, val name: String)

data class LibBookSeminarRequest(
    val roomId: String,
    val date: String,
    val begin: Int,
    val finish: Int,
    val title: String,
    val titleId: String = "",
    val content: String,
    val mobile: String,
    val members: List<LibBookSeminarMember> = emptyList(),
    val isPublic: Boolean = true,
    val categoryId: String = "",
    val attachment: JsonObject? = null,
)

data class LibBookSeminarResult(val success: Boolean, val message: String)

fun seminarTime(minutes: Int): String =
    "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

fun LibBookSeminarDetail.validate(request: LibBookSeminarRequest, notBefore: Int = 0): String? {
  if (requiresAttachment && request.attachment == null) return "请先上传申请附件"
  if (request.roomId != room.id) return "房间信息已变化，请重新选择"
  val day = days.firstOrNull { it.date == request.date } ?: return "预约日期已失效，请刷新"
  if (!day.permits(request.begin, request.finish, notBefore)) return "所选时段不可预约，请重新选择"
  if (earlierPeriods == "3") {
    val category = categories.firstOrNull { it.id == request.categoryId } ?: return "请选择预约时段"
    if (
        category.start != request.begin ||
            category.end != request.finish ||
            (day.status == "-2" && category.id in setOf("1", "3")) ||
            (day.status == "-3" && category.id in setOf("2", "3"))
    )
        return "所选时段已被预约"
  }
  if (requiresTitleChoice && titles.none { it.first == request.titleId }) return "请选择申请主题"
  if (!requiresTitleChoice && request.title.isBlank()) return "请填写申请主题"
  if (request.content.isBlank() || request.content.length > 200) return "请填写申请内容（最多 200 字）"
  if (!Regex("[0-9]{11}").matches(request.mobile)) return "请输入正确的 11 位联系电话"
  if (request.members.map { it.id }.distinct().size != request.members.size) return "参与成员重复"
  val minimumPeople = maxOf(minPeople, day.minPeople)
  if (
      request.members.any { it.id.isBlank() } ||
          request.members.size + 1 !in minimumPeople..maxPeople
  )
      return "预约人数须为 $minimumPeople 至 $maxPeople 人（含申请人）"
  return null
}
