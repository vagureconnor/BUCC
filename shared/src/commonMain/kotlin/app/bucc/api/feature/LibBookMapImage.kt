package app.bucc.api.feature

import app.bucc.api.local.LocalUpstreamClientProvider
import app.bucc.api.local.localUpstreamUrl
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Map images use their official origin only, without bearer credentials or redirect following. */
suspend fun fetchLibBookMapImage(raw: String): ByteArray {
  val absolute =
      if (raw.startsWith("/") && !raw.startsWith("//")) "https://booking.lib.buaa.edu.cn$raw"
      else raw
  val url = Url(absolute)
  require(
      url.protocol == URLProtocol.HTTPS &&
          url.host.equals("booking.lib.buaa.edu.cn", true) &&
          url.port == 443 &&
          url.user.isNullOrEmpty() &&
          url.password.isNullOrEmpty()
  ) {
    "地图来源无法验证"
  }
  val client = LocalUpstreamClientProvider.newLibBookNoRedirectClient()
  return try {
    val response = client.get(localUpstreamUrl(absolute))
    check(response.status.value == 200) { "地图暂时无法加载" }
    check(
        response.headers["Content-Length"]?.toLongOrNull()?.let { it <= 20 * 1024 * 1024 } != false
    ) {
      "地图文件过大"
    }
    val bytes = response.body<ByteArray>()
    check(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "地图文件无效" }
    bytes
  } finally {
    client.close()
  }
}
