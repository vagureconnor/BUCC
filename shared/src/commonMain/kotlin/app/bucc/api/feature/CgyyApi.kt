package app.bucc.api.feature

import app.bucc.api.ConnectionRuntime
import app.bucc.model.dto.CgyyDayInfoResponse
import app.bucc.model.dto.CgyyLockCodeResponse
import app.bucc.model.dto.CgyyOrderDto
import app.bucc.model.dto.CgyyOrdersPageResponse
import app.bucc.model.dto.CgyyPurposeTypeDto
import app.bucc.model.dto.CgyyReservationSubmitRequest
import app.bucc.model.dto.CgyyReservationSubmitResponse
import app.bucc.model.dto.CgyyVenueSiteDto

interface CgyyApiBackend {
  suspend fun getVenueSites(): Result<List<CgyyVenueSiteDto>>

  suspend fun getPurposeTypes(): Result<List<CgyyPurposeTypeDto>>

  suspend fun getDayInfo(venueSiteId: Int, date: String): Result<CgyyDayInfoResponse>

  suspend fun submitReservation(
      request: CgyyReservationSubmitRequest
  ): Result<CgyyReservationSubmitResponse>

  suspend fun getMyOrders(page: Int, size: Int): Result<CgyyOrdersPageResponse>

  suspend fun getOrderDetail(orderId: Int): Result<CgyyOrderDto>

  suspend fun cancelOrder(orderId: Int): Result<CgyyReservationSubmitResponse>

  suspend fun getLockCode(): Result<CgyyLockCodeResponse>
}

open class CgyyApi(
    private val backendProvider: () -> CgyyApiBackend = { ConnectionRuntime.apiFactory().cgyyApi() }
) {
  internal constructor(backend: CgyyApiBackend) : this({ backend })

  private fun currentBackend(): CgyyApiBackend = backendProvider()

  open suspend fun getVenueSites(): Result<List<CgyyVenueSiteDto>> {
    return currentBackend().getVenueSites()
  }

  open suspend fun getPurposeTypes(): Result<List<CgyyPurposeTypeDto>> {
    return currentBackend().getPurposeTypes()
  }

  open suspend fun getDayInfo(venueSiteId: Int, date: String): Result<CgyyDayInfoResponse> {
    return currentBackend().getDayInfo(venueSiteId, date)
  }

  open suspend fun submitReservation(
      request: CgyyReservationSubmitRequest
  ): Result<CgyyReservationSubmitResponse> {
    return currentBackend().submitReservation(request)
  }

  open suspend fun getMyOrders(page: Int = 0, size: Int = 20): Result<CgyyOrdersPageResponse> {
    return currentBackend().getMyOrders(page, size)
  }

  open suspend fun getOrderDetail(orderId: Int): Result<CgyyOrderDto> {
    return currentBackend().getOrderDetail(orderId)
  }

  open suspend fun cancelOrder(orderId: Int): Result<CgyyReservationSubmitResponse> {
    return currentBackend().cancelOrder(orderId)
  }

  open suspend fun getLockCode(): Result<CgyyLockCodeResponse> {
    return currentBackend().getLockCode()
  }
}
