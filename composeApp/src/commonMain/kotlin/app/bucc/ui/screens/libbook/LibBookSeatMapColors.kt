package app.bucc.ui.screens.libbook

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class LibBookSeatMapStatus {
  Available,
  Booked,
  InUse,
  Leave,
  Closed,
  Restricted,
  Unknown,
}

internal data class LibBookSeatMapPalette(
    val booked: Int = 0xff5c9da8.toInt(),
    val inUse: Int = 0xffaaaaaa.toInt(),
    val leave: Int = 0xfff8d146.toInt(),
    val closed: Int = 0xffce686b.toInt(),
    val restricted: Int = 0xffaaaaaa.toInt(),
)

internal data class LibBookMapSeatRegion(
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val status: LibBookSeatMapStatus,
)

internal fun libBookSeatMapStatus(status: String, inLabel: Boolean): LibBookSeatMapStatus =
    if (!inLabel) LibBookSeatMapStatus.Restricted
    else
        when (status.toIntOrNull()) {
          1 -> LibBookSeatMapStatus.Available
          7 -> LibBookSeatMapStatus.Leave
          2,
          10,
          11 -> LibBookSeatMapStatus.Booked
          6,
          8,
          9 -> LibBookSeatMapStatus.InUse
          3,
          4,
          5 -> LibBookSeatMapStatus.Closed
          else -> LibBookSeatMapStatus.Unknown
        }

internal fun libBookMapSeatRegion(
    id: String,
    x: Double?,
    y: Double?,
    width: Double?,
    height: Double?,
    status: String,
    inLabel: Boolean,
): LibBookMapSeatRegion? {
  if (
      x == null ||
          y == null ||
          width == null ||
          height == null ||
          !x.isFinite() ||
          !y.isFinite() ||
          !width.isFinite() ||
          !height.isFinite() ||
          x < 0 ||
          y < 0 ||
          width <= 0 ||
          height <= 0 ||
          x + width > 100.01 ||
          y + height > 100.01
  )
      return null
  return LibBookMapSeatRegion(id, x, y, width, height, libBookSeatMapStatus(status, inLabel))
}

/**
 * Replaces chromatic seat fill only. The HD original, dark outlines and white numbers stay intact.
 */
internal fun recolorLibBookMapPixels(
    original: IntArray,
    imageWidth: Int,
    imageHeight: Int,
    seats: List<LibBookMapSeatRegion>,
    palette: LibBookSeatMapPalette = LibBookSeatMapPalette(),
): IntArray {
  require(original.size == imageWidth * imageHeight)
  val result = original.copyOf()
  for (seat in seats) {
    val target =
        when (seat.status) {
          LibBookSeatMapStatus.Available -> continue
          LibBookSeatMapStatus.Booked -> palette.booked
          LibBookSeatMapStatus.InUse -> palette.inUse
          LibBookSeatMapStatus.Leave -> palette.leave
          LibBookSeatMapStatus.Closed -> palette.closed
          LibBookSeatMapStatus.Restricted,
          LibBookSeatMapStatus.Unknown -> palette.restricted
        }
    val left = floor(seat.x * imageWidth / 100 + 0.000001).toInt().coerceIn(0, imageWidth)
    val top = floor(seat.y * imageHeight / 100 + 0.000001).toInt().coerceIn(0, imageHeight)
    val right =
        ceil((seat.x + seat.width) * imageWidth / 100 - 0.000001).toInt().coerceIn(left, imageWidth)
    val bottom =
        ceil((seat.y + seat.height) * imageHeight / 100 - 0.000001)
            .toInt()
            .coerceIn(top, imageHeight)
    for (y in top until bottom) for (x in left until right) {
      val index = y * imageWidth + x
      val pixel = original[index]
      val r = pixel ushr 16 and 255
      val g = pixel ushr 8 and 255
      val b = pixel and 255
      val high = max(r, max(g, b))
      val low = min(r, min(g, b))
      // Both kinds of available chairs are green/blue. Brown desks, black outlines,
      // white labels and low-chroma JPEG pixels must never enter the replacement mask.
      if (high < 100 || high - low < 48 || max(g, b) < r * 1.18 || g < r * 1.08) continue
      val coverage = ((high - low) / 140f).coerceIn(0f, 1f)
      val neutral = if (high > 190 && low > 100) 255 else 0
      fun channel(shift: Int): Int =
          ((target ushr shift and 255) * coverage + neutral * (1 - coverage))
              .roundToInt()
              .coerceIn(0, 255)
      result[index] =
          (pixel and 0xff000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
  }
  return result
}

/** The same center-based fit/zoom/pan is used for both drawing and pointer conversion. */
internal data class LibBookMapViewport(
    val viewWidth: Float,
    val viewHeight: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
  val scale: Float
    get() = min(viewWidth / imageWidth, viewHeight / imageHeight) * zoom

  fun imageToView(x: Float, y: Float): Pair<Float, Float> =
      Pair(
          viewWidth / 2 + panX + (x - imageWidth / 2f) * scale,
          viewHeight / 2 + panY + (y - imageHeight / 2f) * scale,
      )

  fun viewToImage(x: Float, y: Float): Pair<Float, Float> =
      Pair(
          (x - viewWidth / 2 - panX) / scale + imageWidth / 2f,
          (y - viewHeight / 2 - panY) / scale + imageHeight / 2f,
      )
}

internal fun libBookSeatMapHit(
    seats: List<LibBookMapSeatRegion>,
    viewport: LibBookMapViewport,
    x: Float,
    y: Float,
): String? {
  if (viewport.scale <= 0 || !viewport.scale.isFinite()) return null
  val (px, py) = viewport.viewToImage(x, y)
  val percentX = px * 100 / viewport.imageWidth
  val percentY = py * 100 / viewport.imageHeight
  return seats
      .firstOrNull {
        it.status == LibBookSeatMapStatus.Available &&
            percentX >= it.x &&
            percentX < it.x + it.width &&
            percentY >= it.y &&
            percentY < it.y + it.height
      }
      ?.id
}

internal data class LibBookMapGestureTransform(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

internal fun updateLibBookMapGesture(
    current: LibBookMapGestureTransform,
    zoomChange: Float,
    panX: Float = 0f,
    panY: Float = 0f,
): LibBookMapGestureTransform {
  if (!zoomChange.isFinite() || zoomChange <= 0 || !panX.isFinite() || !panY.isFinite())
      return current
  val zoom = (current.zoom * zoomChange).coerceIn(1f, 5f)
  return if (zoom == 1f) LibBookMapGestureTransform()
  else LibBookMapGestureTransform(zoom, current.panX + panX, current.panY + panY)
}

internal fun libBookTrackpadMagnificationFactor(magnification: Double): Float? =
    magnification
        .takeIf { it.isFinite() && it != 0.0 }
        ?.let { (1.0 + it).coerceIn(0.1, 10.0).toFloat() }

internal fun libBookMapScrollZoomFactor(deltaY: Float, shortcutPressed: Boolean): Float? =
    if (!shortcutPressed || !deltaY.isFinite() || deltaY == 0f) null
    else kotlin.math.exp((-deltaY * 0.08f).coerceIn(-1f, 1f))

internal data class LibBookMapSelectionFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val outerStrokePx: Float,
    val innerStrokePx: Float,
)

internal fun libBookMapSelectionFrame(
    region: LibBookMapSeatRegion,
    viewport: LibBookMapViewport,
    density: Float = 1f,
): LibBookMapSelectionFrame {
  val point =
      viewport.imageToView(
          (region.x * viewport.imageWidth / 100).toFloat(),
          (region.y * viewport.imageHeight / 100).toFloat(),
      )
  val outset = 4f * density
  return LibBookMapSelectionFrame(
      point.first - outset,
      point.second - outset,
      (region.width * viewport.imageWidth / 100 * viewport.scale).toFloat() + outset * 2,
      (region.height * viewport.imageHeight / 100 * viewport.scale).toFloat() + outset * 2,
      6f * density,
      3f * density,
  )
}
