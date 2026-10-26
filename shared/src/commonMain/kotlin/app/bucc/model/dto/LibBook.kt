package app.bucc.model.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibBookLibraryDto(
    val id: String,
    val name: String,
    val freeNum: Int = 0,
    val totalNum: Int = 0,
    val storeys: List<LibBookStoreyDto> = emptyList(),
)

@Serializable
data class LibBookStoreyDto(
    val id: String,
    val name: String,
    val freeNum: Int = 0,
    val totalNum: Int = 0,
)

@Serializable
data class LibBookAreaDto(
    val id: String,
    val name: String,
    val areaName: String = "",
    val premisesId: String = "",
    val storeyId: String = "",
    val freeNum: Int = 0,
    val totalNum: Int = 0,
)

@Serializable
data class LibBookTimeSlotDto(
    val id: String,
    val start: String,
    val end: String,
    val label: String = "$start-$end",
)

@Serializable
data class LibBookAreaDetailDto(
    val id: String,
    val name: String = "",
    val availableDates: List<String> = emptyList(),
    val timeSlots: List<LibBookTimeSlotDto> = emptyList(),
    val timeSlotsByDay: Map<String, List<LibBookTimeSlotDto>> = emptyMap(),
    val mapImages: LibBookMapImagesDto = LibBookMapImagesDto(),
)

@Serializable
data class LibBookSeatDto(
    val id: String,
    val name: String = "",
    val no: String = "",
    val status: String = "",
    val statusName: String = "",
    val isAvailable: Boolean = status == "1",
    val pointX: Double? = null,
    val pointY: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val inLabel: Boolean = true,
)

@Serializable
data class LibBookReserveRequest(
    val areaId: String,
    val seatId: String,
    val day: String,
    val segment: String,
    val startTime: String = "",
    val endTime: String = "",
)

@Serializable
data class LibBookReserveResponse(
    val success: Boolean,
    val message: String,
    val booking: LibBookBookingDto? = null,
)

@Serializable
data class LibBookCancelResponse(
    val success: Boolean,
    val message: String,
)

@Serializable
data class LibBookBookingsResponse(
    val bookings: List<LibBookBookingDto> = emptyList(),
    val page: Int = 1,
    val limit: Int = 20,
    val total: Int = bookings.size,
)

@Serializable
data class LibBookBookingDto(
    val id: String,
    val nameMerge: String = "",
    val areaName: String = "",
    val seatNo: String = "",
    val day: String = "",
    val beginTime: String = "",
    val endTime: String = "",
    val status: String = "",
    val statusName: String = "",
    val cancellationAllowed: Boolean = false,
)

fun LibBookBookingDto.canCancelBooking(): Boolean = cancelBlockedMessage() == null

fun LibBookBookingDto.cancelBlockedMessage(): String? {
  if (id.isBlank()) return "预约记录不存在或已失效，请刷新后重试"
  if (status.trim() in nonCancelableLibBookBookingStatusCodes) {
    return "该预约已结束或已取消，无需取消"
  }
  if (nonCancelableLibBookBookingStatusKeywords.any { statusName.contains(it) }) {
    return "该预约已结束或已取消，无需取消"
  }
  if (status.trim() !in setOf("2", "9") || !cancellationAllowed) {
    return "当前预约状态不允许取消，请刷新或前往图书馆官方页面确认"
  }
  return null
}

private val nonCancelableLibBookBookingStatusCodes = setOf("4", "6", "8")

private val nonCancelableLibBookBookingStatusKeywords = listOf("取消", "结束", "已完成", "过期", "失效")

@Serializable
data class LibBookEncryptedReserveBody(
    @SerialName("seat_id") val seatId: String,
    val segment: String,
    val day: String,
    @SerialName("start_time") val startTime: String = "",
    @SerialName("end_time") val endTime: String = "",
)

@Serializable
data class LibBookMapImagesDto(
    val free: String = "",
    val book: String = "",
    val use: String = "",
    val leave: String = "",
    val close: String = "",
    val notAvailable: String = "",
    val selected: String = "",
)

@Serializable
data class LibBookSeatSnapshotDto(
    val seats: List<LibBookSeatDto> = emptyList(),
    val mapImages: LibBookMapImagesDto = LibBookMapImagesDto(),
)
