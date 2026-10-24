package app.bucc.model.dto

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.datetime.LocalDate

data class LibBookQrTarget(val areaId: String, val seatId: String, val day: String?) {
  override fun toString(): String = "LibBookQrTarget([redacted])"
}

sealed interface LibBookQrResult {
  data class Seat(val target: LibBookQrTarget) : LibBookQrResult

  data object Invalid : LibBookQrResult

  data object Unsupported : LibBookQrResult

  data object Expired : LibBookQrResult
}

/** Decode only the public library seat routes. Never visits the QR or executes qr_scan/qr_touch. */
fun parseLibBookReservationQr(payload: String, today: String): LibBookQrResult {
  if (payload.length !in 1..4096 || payload.any { it.isISOControl() })
      return LibBookQrResult.Invalid
  val url = runCatching { Url(payload.trim()) }.getOrNull() ?: return LibBookQrResult.Invalid
  if (
      url.protocol != URLProtocol.HTTPS ||
          url.host.lowercase() != "booking.lib.buaa.edu.cn" ||
          url.port != 443 ||
          !url.user.isNullOrEmpty() ||
          !url.password.isNullOrEmpty() ||
          url.encodedPath !in setOf("/h5/index.html", "/h5/") ||
          !url.parameters.isEmpty()
  )
      return LibBookQrResult.Unsupported
  val route =
      runCatching { Url("https://booking.lib.buaa.edu.cn/" + url.fragment.removePrefix("/")) }
          .getOrNull() ?: return LibBookQrResult.Invalid
  val areaKey =
      when (route.encodedPath) {
        "/booking" -> "area_id"
        "/mo/seat/appointment",
        "/seat/appointment" -> "id"
        else -> return LibBookQrResult.Unsupported
      }
  if (route.parameters.names().any { it !in setOf(areaKey, "seat_id", "date") })
      return LibBookQrResult.Unsupported
  fun one(name: String) = route.parameters.getAll(name)?.singleOrNull()
  val area = one(areaKey) ?: return LibBookQrResult.Invalid
  val seat = one("seat_id") ?: return LibBookQrResult.Invalid
  val id = Regex("[1-9][0-9]{0,11}")
  if (!id.matches(area) || !id.matches(seat)) return LibBookQrResult.Invalid
  val day = one("date")
  if (route.parameters.getAll("date")?.size?.let { it != 1 } == true) return LibBookQrResult.Invalid
  if (day != null) {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return LibBookQrResult.Invalid
    val current =
        runCatching { LocalDate.parse(today) }.getOrNull() ?: return LibBookQrResult.Invalid
    if (date < current) return LibBookQrResult.Expired
  }
  return LibBookQrResult.Seat(LibBookQrTarget(area, seat, day))
}
