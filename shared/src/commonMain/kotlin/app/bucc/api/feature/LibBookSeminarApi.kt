package app.bucc.api.feature

import app.bucc.api.local.LocalLibBookSeminarApi
import app.bucc.model.dto.*
import kotlinx.serialization.json.JsonObject

open class LibBookSeminarApi {
  private val backend by lazy { LocalLibBookSeminarApi() }

  open suspend fun dates(): Result<List<String>> = backend.dates()

  open suspend fun filters(date: String): Result<LibBookSeminarFilters> = backend.filters(date)

  open suspend fun rooms(
      date: String,
      buildingId: String,
      floorId: String?,
  ): Result<List<LibBookSeminarRoom>> = backend.rooms(date, buildingId, floorId)

  open suspend fun rooms(date: String): Result<List<LibBookSeminarRoom>> = backend.rooms(date)

  open suspend fun detail(id: String, date: String): Result<LibBookSeminarDetail> =
      backend.detail(id, date)

  open suspend fun member(
      roomId: String,
      memberId: String,
      date: String,
      begin: Int,
      finish: Int,
  ): Result<LibBookSeminarMember> = backend.member(roomId, memberId, date, begin, finish)

  open suspend fun uploadAttachment(
      fileName: String,
      mimeType: String,
      bytes: ByteArray,
  ): Result<JsonObject> = backend.uploadAttachment(fileName, mimeType, bytes)

  open suspend fun submit(
      detail: LibBookSeminarDetail,
      request: LibBookSeminarRequest,
  ): Result<LibBookSeminarResult> = backend.submit(detail, request)
}
