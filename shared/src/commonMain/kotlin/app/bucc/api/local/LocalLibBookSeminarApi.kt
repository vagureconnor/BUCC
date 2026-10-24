package app.bucc.api.local

import app.bucc.model.dto.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

internal class LocalLibBookSeminarApi(
    private val upload: suspend (String, String, ByteArray) -> JsonObject = { name, type, bytes ->
      libBookUpload(name, type, bytes)
    },
    private val request: suspend (String, String, JsonObject, Boolean) -> JsonObject =
        { op, path, body, retry ->
          libBookRequest(op, path, body, retry)
        },
) {
  private suspend fun call(
      path: String,
      body: JsonObject = buildJsonObject {},
      retry: Boolean = true,
  ): JsonObject {
    val raw = request("seminar_${path.substringAfterLast('/')}", path, body, retry)
    check(raw.text("code") == "0") { raw.text("message").ifBlank { "图书馆研讨室请求失败" } }
    return raw
  }

  suspend fun dates(): Result<List<String>> = runCatching {
    call("seminar/siftdate")["data"].array().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
  }

  suspend fun filters(date: String): Result<LibBookSeminarFilters> = runCatching {
    val (data, libraries) =
        coroutineScope {
          val filters = async { call("seminar/sift")["data"].obj() }
          val locations = async {
            runCatching {
                  call("space/pcTopFor", buildJsonObject { put("day", date) })["data"]
                      .obj()["list"]
                      .array()
                }
                .getOrDefault(JsonArray(emptyList()))
          }
          filters.await() to locations.await()
        }
    mapSeminarFilters(data, libraries)
  }

  suspend fun rooms(
      date: String,
      buildingId: String? = null,
      floorId: String? = null,
  ): Result<List<LibBookSeminarRoom>> = runCatching {
    val rooms = linkedMapOf<String, LibBookSeminarRoom>()
    var page = 1
    do {
      val data =
          call(
                  "seminar/list",
                  buildJsonObject {
                    put("date", date)
                    put("page", page)
                    put("members", "")
                    put("name", "")
                    put("room", "")
                    put(
                        "premises",
                        buildJsonArray {
                          buildingId?.takeIf { it.isNotBlank() }?.let { add(JsonPrimitive(it)) }
                        },
                    )
                    put(
                        "floor",
                        buildJsonArray {
                          floorId?.takeIf { it.isNotBlank() }?.let { add(JsonPrimitive(it)) }
                        },
                    )
                    listOf("category", "boutique").forEach { put(it, JsonArray(emptyList())) }
                    put("start_time", "")
                    put("end_time", "")
                  },
              )["data"]
              .obj()
      data["data"].array().forEach { element ->
        val row = element.obj()
        val id = row.text("id")
        if (id.isNotBlank()) rooms[id] = mapRoom(row)
      }
      val last = data.number("last_page", 1)
      check(page < 100 || page >= last) { "研讨室列表页数异常，请缩小查询范围" }
      page++
    } while (page <= last)
    rooms.values.toList()
  }

  suspend fun detail(id: String, date: String): Result<LibBookSeminarDetail> = runCatching {
    val data =
        call(
                "seminar/seminar",
                buildJsonObject {
                  put("id", id)
                  put("day", date)
                },
            )["data"]
            .obj()
    val detail = data["detail"].obj()
    val axis = data["axis"].obj()
    val rules =
        call("Help/should", buildJsonObject { put("id", id) })["data"].obj().text("seat_rule")
    LibBookSeminarDetail(
        room = mapRoom(detail).let { if (it.id.isBlank()) it.copy(id = id) else it },
        days =
            axis["list"].array().map { element ->
              val day = element.obj()
              val info = day["info"].obj()
              LibBookSeminarDay(
                  day.text("date"),
                  info.number("start_time", 0),
                  info.number("end_time", 0),
                  info.number("step", 15),
                  info.number("min_time", 60),
                  info.number("max_time", 0),
                  info["list"].array().map { interval ->
                    interval.obj().let {
                      LibBookSeminarInterval(it.number("begin_num", 0), it.number("end_num", 0))
                    }
                  },
                  day.text("status").ifBlank { "1" },
                  info.number("min_person", 1),
              )
            },
        earlierPeriods = detail.text("earlierPeriods"),
        categories =
            axis["category"].array().map { element ->
              element.obj().let {
                LibBookSeminarCategory(
                    it.text("id"),
                    it.text("name"),
                    minute(it.text("start_time")),
                    minute(it.text("end_time")),
                )
              }
            },
        minPeople = detail.number("minPerson", 1),
        maxPeople = detail.number("maxPerson", 1),
        titles =
            detail["titles"].array().map { element ->
              element.obj().let { it.text("id") to it.text("title") }
            },
        requiresTitleChoice = detail.text("readonlyTitle") == "1",
        requiresAttachment = detail.text("isMouldShow") == "1",
        rules = rules,
        typeId = detail.text("type_id"),
    )
  }

  suspend fun member(
      roomId: String,
      memberId: String,
      date: String,
      begin: Int,
      finish: Int,
  ): Result<LibBookSeminarMember> = runCatching {
    val member =
        call(
                "seminar/members",
                buildJsonObject {
                  put("area_id", roomId)
                  put("member_id", memberId.trim())
                  put("begin_time", "$date ${seminarTime(begin)}")
                  put("finish_time", "$date ${seminarTime(finish)}")
                },
            )["data"]
            .obj()
    check(member.text("id").isNotBlank()) { "成员信息无效" }
    LibBookSeminarMember(member.text("id"), member.text("name"))
  }

  suspend fun uploadAttachment(
      fileName: String,
      mimeType: String,
      bytes: ByteArray,
  ): Result<JsonObject> = runCatching {
    require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "附件须小于 10 MiB 且不能为空" }
    require(mimeType in setOf("image/jpeg", "image/png", "image/bmp", "application/pdf")) {
      "仅支持 JPG、PNG、BMP 图片或 PDF"
    }
    val raw = upload(fileName, mimeType, bytes)
    check(raw.text("code") == "0") { raw.text("message").ifBlank { "附件上传失败" } }
    val data = raw["data"].obj()
    check(data.isNotEmpty()) { "附件上传结果无效，请重新上传" }
    data
  }

  suspend fun submit(
      detail: LibBookSeminarDetail,
      reservation: LibBookSeminarRequest,
  ): Result<LibBookSeminarResult> = runCatching {
    detail.validate(reservation)?.let { error(it) }
    val raw =
        call(
            if (detail.earlierPeriods == "0") "seminar/confirm" else "seminar/submit",
            buildJsonObject {
              put("area_id", reservation.roomId)
              put("start_date", reservation.date)
              put("end_date", reservation.date)
              put("title", reservation.title.trim())
              put("title_id", reservation.titleId)
              put("content", reservation.content.trim())
              put("mobile", reservation.mobile.trim())
              put("open", if (reservation.isPublic) 0 else 1)
              put("team", reservation.members.joinToString(",") { it.id })
              put("file", buildJsonArray { reservation.attachment?.let { add(it) } })
              if (reservation.categoryId.isNotBlank()) put("cate_id", reservation.categoryId)
              put(
                  "time",
                  buildJsonArray {
                    add(
                        buildJsonObject {
                          put("start_time", seminarTime(reservation.begin))
                          put("end_time", seminarTime(reservation.finish))
                        }
                    )
                  },
              )
            },
            retry = false,
        )
    LibBookSeminarResult(true, raw.text("message").ifBlank { "预约成功" })
  }
}

private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

private fun JsonElement?.array(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.number(key: String, fallback: Int): Int = text(key).toIntOrNull() ?: fallback

private fun minute(value: String): Int =
    value.split(':').let { parts ->
      if (parts.size >= 2) (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
      else value.toIntOrNull() ?: 0
    }

private fun mapRoom(row: JsonObject) =
    LibBookSeminarRoom(
        row.text("id"),
        row.text("nameMerge").ifBlank { row.text("name_merge").ifBlank { row.text("name") } },
        row.text("maxPerson").ifBlank { row.text("max_person") },
        row.text("contents").replace(Regex("<[^>]+>"), " "),
    )

/** Uses official premises IDs and storey.list.parentId; never infers location from room names. */
internal fun mapSeminarFilters(data: JsonObject, libraries: JsonArray): LibBookSeminarFilters {
  val libraryById = libraries.map { it.obj() }.associateBy { it.text("id") }
  val campuses = linkedMapOf<String, Pair<String, MutableList<LibBookSeminarBuilding>>>()
  data["premises"].array().forEach { element ->
    val premise = element.obj()
    val id = premise.text("id")
    if (id.isBlank()) return@forEach
    val library = libraryById[id] ?: JsonObject(emptyMap())
    val name = premise.text("name").ifBlank { library.text("name") }.ifBlank { "楼馆 $id" }
    val explicitCampus =
        premise
            .text("campus_name")
            .ifBlank { premise.text("top_name") }
            .ifBlank { library.text("campus_name").ifBlank { library.text("top_name") } }
    // Some official premises labels combine campus and library, e.g. 学院路校区图书馆.
    val combinedLabel = library.text("name").ifBlank { name }
    val campusName =
        explicitCampus.ifBlank {
          when {
            "校区" in combinedLabel -> combinedLabel.substringBefore("校区") + "校区"
            "学院路" in combinedLabel -> "学院路校区"
            "沙河" in combinedLabel -> "沙河校区"
            else -> "未标注校区"
          }
        }
    val campusId =
        premise
            .text("campus_id")
            .ifBlank { premise.text("top_id") }
            .ifBlank { library.text("campus_id").ifBlank { library.text("top_id") } }
            .ifBlank { "label:$campusName" }
    val floors = linkedMapOf<String, LibBookSeminarFloor>()
    data["storey"].array().forEach { groupElement ->
      val group = groupElement.obj()
      group["list"].array().forEach { floorElement ->
        val floor = floorElement.obj()
        if (floor.text("parentId") == id) {
          val floorId = floor.text("id")
          if (floorId.isNotBlank())
              floors[floorId] =
                  LibBookSeminarFloor(
                      floorId,
                      floor.text("name").ifBlank { group.text("name") }.ifBlank { "楼层 $floorId" },
                  )
        }
      }
    }
    if (floors.isEmpty())
        library["children"].array().forEach { floorElement ->
          val floor = floorElement.obj()
          val floorId = floor.text("id")
          if (floorId.isNotBlank())
              floors[floorId] =
                  LibBookSeminarFloor(floorId, floor.text("name").ifBlank { "楼层 $floorId" })
        }
    campuses
        .getOrPut(campusId) { campusName to mutableListOf() }
        .second
        .add(LibBookSeminarBuilding(id, name, floors.values.toList()))
  }
  return LibBookSeminarFilters(
      campuses.map { (id, value) ->
        LibBookSeminarCampus(id, value.first, value.second.distinctBy { it.id })
      }
  )
}
