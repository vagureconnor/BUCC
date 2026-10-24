package app.bucc.api.feature

import app.bucc.api.ConnectionRuntime
import app.bucc.model.dto.LibBookAreaDetailDto
import app.bucc.model.dto.LibBookAreaDto
import app.bucc.model.dto.LibBookBookingsResponse
import app.bucc.model.dto.LibBookCancelResponse
import app.bucc.model.dto.LibBookLibraryDto
import app.bucc.model.dto.LibBookReserveRequest
import app.bucc.model.dto.LibBookReserveResponse
import app.bucc.model.dto.LibBookSeatDto
import app.bucc.model.dto.LibBookSeatSnapshotDto

interface LibBookApiBackend {
  suspend fun getLibraries(day: String): Result<List<LibBookLibraryDto>>

  suspend fun getAreas(
      premisesId: String,
      storeyId: String?,
      day: String,
  ): Result<List<LibBookAreaDto>>

  suspend fun getAreaDetail(areaId: String): Result<LibBookAreaDetailDto>

  suspend fun getSeats(
      areaId: String,
      day: String,
      startTime: String,
      endTime: String,
  ): Result<List<LibBookSeatDto>>

  suspend fun getSeatSnapshot(
      areaId: String,
      day: String,
      startTime: String,
      endTime: String,
  ): Result<LibBookSeatSnapshotDto> =
      getSeats(areaId, day, startTime, endTime).map { LibBookSeatSnapshotDto(it) }

  suspend fun reserve(request: LibBookReserveRequest): Result<LibBookReserveResponse>

  suspend fun getBookings(page: Int = 1, limit: Int = 20): Result<LibBookBookingsResponse>

  suspend fun cancelBooking(bookingId: String): Result<LibBookCancelResponse>
}

open class LibBookApi(
    private val backendProvider: () -> LibBookApiBackend = {
      ConnectionRuntime.apiFactory().libBookApi()
    }
) {
  internal constructor(backend: LibBookApiBackend) : this({ backend })

  private fun currentBackend(): LibBookApiBackend = backendProvider()

  open suspend fun getLibraries(day: String): Result<List<LibBookLibraryDto>> =
      currentBackend().getLibraries(day)

  open suspend fun getAreas(
      premisesId: String,
      storeyId: String? = null,
      day: String,
  ): Result<List<LibBookAreaDto>> = currentBackend().getAreas(premisesId, storeyId, day)

  open suspend fun getAreaDetail(areaId: String): Result<LibBookAreaDetailDto> =
      currentBackend().getAreaDetail(areaId)

  open suspend fun getSeats(
      areaId: String,
      day: String,
      startTime: String,
      endTime: String,
  ): Result<List<LibBookSeatDto>> = currentBackend().getSeats(areaId, day, startTime, endTime)

  open suspend fun getSeatSnapshot(
      areaId: String,
      day: String,
      startTime: String,
      endTime: String,
  ): Result<LibBookSeatSnapshotDto> =
      currentBackend().getSeatSnapshot(areaId, day, startTime, endTime)

  open suspend fun reserve(request: LibBookReserveRequest): Result<LibBookReserveResponse> =
      currentBackend().reserve(request)

  open suspend fun getBookings(page: Int = 1, limit: Int = 20): Result<LibBookBookingsResponse> =
      currentBackend().getBookings(page, limit)

  open suspend fun cancelBooking(bookingId: String): Result<LibBookCancelResponse> =
      currentBackend().cancelBooking(bookingId)
}
