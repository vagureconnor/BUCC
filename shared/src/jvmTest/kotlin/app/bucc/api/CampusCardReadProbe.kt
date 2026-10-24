package app.bucc.api

import app.bucc.api.finance.CampusCardCodec
import app.bucc.api.finance.CampusCardIdentity
import app.bucc.api.finance.campusCardIdentityFromUrl
import app.bucc.api.finance.formText
import app.bucc.api.local.*
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Explicit GET-only local diagnostic: no credential submission, card initialization or order. */
object CampusCardReadProbe {
  @JvmStatic
  fun main(args: Array<String>) {
    runBlocking {
      val memory = MapSettings()
      Settings().let { saved ->
        saved.keys.forEach { key -> saved.getStringOrNull(key)?.let { memory.putString(key, it) } }
      }
      ConnectionModeStore.settings = memory
      LocalAuthSessionStore.settings = memory
      LocalCookieStore.settings = memory
      val mode = ConnectionMode.valueOf(args.firstOrNull() ?: "WEBVPN")
      ConnectionModeStore.save(mode)
      ConnectionRuntime.resolveSelectedMode()
      LocalUpstreamClientProvider.reset()
      val gateway = localBusinessUsesWebVpnGateway()
      println(
          "CARD_READ mode=$mode gateway=$gateway session=${LocalAuthSessionStore.get() != null}"
      )
      val originalFactory = LocalUpstreamClientProvider.clientFactory
      LocalUpstreamClientProvider.clientFactory = { redirects ->
        originalFactory(false).apply {
          plugin(HttpSend).intercept { request ->
            check(request.method == HttpMethod.Get) { "GET-only diagnostic" }
            val upstream = Url(LocalWebVpnSupport.fromWebVpnUrl(request.url.buildString()))
            check(upstream.protocol == URLProtocol.HTTPS && upstream.port == 443)
            check(upstream.user.isNullOrEmpty() && upstream.password.isNullOrEmpty())
            val allowed =
                when (upstream.host) {
                  "d.buaa.edu.cn" -> upstream.encodedPath in setOf("/", "/login")
                  "sso.buaa.edu.cn" -> upstream.encodedPath == "/login"
                  "m.icw.buaa.edu.cn" ->
                      upstream.encodedPath in
                          setOf(
                              "/card",
                              "/card/",
                              "/card/goIndex",
                              "/card/home/sisUstbMiniReturnPage/112233/1/auth",
                              "/card/home/openHomePageApp",
                              "/card/queryPayInfoList",
                          )
                  else -> false
                }
            check(allowed) { "Unapproved diagnostic destination" }
            val call = execute(request)
            val stage =
                when (upstream.encodedPath) {
                  "/card/home/openHomePageApp" -> "menus"
                  "/card/queryPayInfoList" -> "methods"
                  else -> "identity"
                }
            println(
                "CARD_GET stage=$stage host=${upstream.host} status=${call.response.status.value} gateway=${request.url.host == "d.buaa.edu.cn"}"
            )
            call
          }
        }
      }
      try {
        withTimeout(120_000) {
          if (gateway) ensureLocalWebVpnSession()
          val connection = LocalUpstreamClientProvider.newNoRedirectClient()
          try {
            val user = readIdentity(connection, gateway)
            println("CARD_IDENTITY valid=${user != null}")
            if (user != null) {
              if ("--direct-api" in args) {
                val direct =
                    LocalUpstreamClientProvider.newCampusCardClient().apply {
                      plugin(HttpSend).intercept { request ->
                        check(request.method == HttpMethod.Get) { "GET-only diagnostic" }
                        val url = request.url.build()
                        check(
                            url.protocol == URLProtocol.HTTPS &&
                                url.port == 443 &&
                                url.host == "m.icw.buaa.edu.cn" &&
                                url.user.isNullOrEmpty() &&
                                url.password.isNullOrEmpty() &&
                                url.encodedPath in
                                    setOf("/card/home/openHomePageApp", "/card/queryPayInfoList")
                        ) {
                          "Unapproved diagnostic destination"
                        }
                        val call = execute(request)
                        val stage =
                            if (url.encodedPath == "/card/home/openHomePageApp") "menus"
                            else "methods"
                        println(
                            "CARD_GET stage=$stage host=${url.host} status=${call.response.status.value} gateway=false"
                        )
                        call
                      }
                    }
                println(
                    "CARD_API_TRANSPORT direct=true identityMode=$mode productionCardClient=true"
                )
                try {
                  readCatalog(direct, false, user)
                } finally {
                  direct.close()
                }
              } else readCatalog(connection, gateway, user)
            }
          } finally {
            connection.close()
          }
        }
      } catch (error: Exception) {
        println(
            "CARD_READ_ERROR type=${generateSequence(error as Throwable) { it.cause }.take(4).map { it::class.simpleName }.joinToString("/")}"
        )
      } finally {
        LocalUpstreamClientProvider.reset()
      }
    }
    kotlin.system.exitProcess(0)
  }

  private suspend fun readIdentity(client: HttpClient, gateway: Boolean): CampusCardIdentity? {
    var target =
        URLBuilder("https://sso.buaa.edu.cn/login")
            .apply {
              parameters.append(
                  "service",
                  "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/1/auth",
              )
              parameters.append("noAutoRedirect", "1")
            }
            .buildString()
    repeat(8) { step ->
      campusCardIdentityFromUrl(target)?.let {
        return it
      }
      val upstream = Url(target)
      val routed =
          if (upstream.host == "sso.buaa.edu.cn") localServiceSsoUrl(target)
          else if (gateway) LocalWebVpnSupport.toWebVpnUrl(target) else target
      val response = client.get(routed)
      val location = response.headers[HttpHeaders.Location] ?: return null
      val resolved =
          URLBuilder(response.call.request.url)
              .apply {
                parameters.clear()
                fragment = ""
                takeFrom(location)
              }
              .buildString()
      target = LocalWebVpnSupport.fromWebVpnUrl(resolved)
      val destination = Url(target)
      println(
          "CARD_REDIRECT step=$step scheme=${destination.protocol.name} host=${destination.host} relative=${location.startsWith('/')} identity=${campusCardIdentityFromUrl(target) != null}"
      )
    }
    return null
  }

  private fun String.codePointAtOrNull(): Int? =
      if (isEmpty()) null else java.lang.Character.codePointAt(this, 0)

  @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
  private fun diagnoseResponse(response: io.ktor.client.statement.HttpResponse, body: String) {
    val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull()
    val root = parsed as? JsonObject
    val shape =
        when (parsed) {
          is JsonObject -> "object"
          is JsonArray -> "array"
          is JsonPrimitive -> if (parsed.isString) "string" else "primitive"
          else -> if (body.trimStart().startsWith('<')) "html" else "other"
        }
    val contentType =
        response.contentType()?.withoutParameters()?.toString()?.takeIf {
          it in
              setOf(
                  "application/json",
                  "text/html",
                  "text/plain",
                  "application/octet-stream",
                  "text/javascript",
              )
        } ?: "other"
    println(
        "CARD_BODY type=$contentType shape=$shape length=${body.length} successKey=${root?.containsKey("success")} datajsonKey=${root?.containsKey("datajson")} codeKey=${root?.containsKey("code")} passwordInput=${Regex("(?is)<input[^>]+type\\s*=\\s*['\"]?password").containsMatchIn(body)}"
    )
    val encoding =
        response.headers[HttpHeaders.ContentEncoding]?.lowercase()?.takeIf {
          it in setOf("gzip", "deflate", "br", "identity")
        } ?: "none-or-other"
    val bomStripped = body.removePrefix("\uFEFF")
    val urlDecoded = runCatching { body.decodeURLQueryComponent(plusIsSpace = false) }.getOrNull()
    val bomRecovered = runCatching { CampusCardCodec.decode(bomStripped) }.isSuccess
    val urlRecovered =
        urlDecoded?.let {
          runCatching { CampusCardCodec.decode(it.removePrefix("\uFEFF")) }.isSuccess
        } ?: false
    println(
        "CARD_ENCODING firstCodePoint=${body.codePointAtOrNull()} contentEncoding=$encoding bomPresent=${body.startsWith('\uFEFF')} bomRecovered=$bomRecovered urlRecovered=$urlRecovered"
    )
    println(
        "CARD_ERROR_WORDS illegal=${body.contains("非法")} requestParameter=${body.contains("请求参数")} denied=${body.contains("无权限")} error=${body.contains("错误")} login=${body.contains("登录")} failure=${body.contains("出错")} datajson=${body.contains("datajson")}"
    )
    listOf(
            "系统繁忙",
            "异常",
            "维护",
            "升级",
            "检测",
            "网络",
            "参数",
            "服务",
            "请求",
            "访问",
            "格式",
            "WebVPN",
            "禁止",
            "安全",
            "SSL",
            "客户端",
            "后台",
        )
        .forEachIndexed { index, word ->
          println("CARD_PUBLIC_WORD index=$index match=${body.contains(word, ignoreCase = true)}")
        }
    val wrapped =
        (root?.get("datajson") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (parsed as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: body.trim().takeIf { shape == "other" }
    if (wrapped != null) {
      val ciphertext = wrapped.drop(16)
      val bytes = runCatching { kotlin.io.encoding.Base64.decode(ciphertext) }.getOrNull()
      val prefixValid =
          wrapped.take(16).length == 16 &&
              wrapped.take(16).all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
      val plusRecovered =
          runCatching {
                CampusCardCodec.decode(
                    buildJsonObject { put("datajson", wrapped.replace(' ', '+')) }.toString()
                )
              }
              .isSuccess
      val envelopeRecovered =
          runCatching {
                CampusCardCodec.decode(buildJsonObject { put("datajson", wrapped) }.toString())
              }
              .isSuccess
      println(
          "CARD_ENCRYPTED length=${wrapped.length} prefixValid=$prefixValid plusCount=${ciphertext.count { it == '+' }} spaceCount=${ciphertext.count { it == ' ' }} base64Valid=${bytes != null} blockAligned=${bytes != null && bytes.isNotEmpty() && bytes.size % 16 == 0} envelopeRecovered=$envelopeRecovered plusRecovered=$plusRecovered"
      )
    }
  }

  private suspend fun readCatalog(client: HttpClient, gateway: Boolean, user: CampusCardIdentity) {
    suspend fun query(path: String, payload: JsonObject): JsonObject {
      val target = "https://m.icw.buaa.edu.cn/card$path"
      val encrypted = CampusCardCodec.encode(payload)
      val response =
          client.get(if (gateway) LocalWebVpnSupport.toWebVpnUrl(target) else target) {
            header("X-Requested-With", "XMLHttpRequest")
            header("session-type", "uniapp")
            header("isWechatApp", "true")
            header("orgid", "2")
            header(HttpHeaders.Referrer, "https://m.icw.buaa.edu.cn/card/")
            contentType(ContentType.Application.Json)
            parameter("datajson", encrypted.getValue("datajson").jsonPrimitive.content)
          }
      check(response.status.isSuccess()) { "Read unavailable" }
      val body = response.bodyAsText()
      diagnoseResponse(response, body)
      return CampusCardCodec.decode(body)
    }
    val home =
        query(
            "/home/openHomePageApp",
            buildJsonObject {
              put("openid", user.openid)
              put("usertype", user.usertype)
              put("ua", "mozilla/5.0 bucc/2.0.0")
              put("orgid", "2")
            },
        )
    val functions = (home["data"] as? JsonObject)?.get("funcList") as? JsonArray
    val matches =
        functions
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter {
              formText(it["linkurl"]?.jsonPrimitive?.contentOrNull.orEmpty())
                  .substringBefore('?') == "/pages_other/recharge/cardrecharge/cardrecharge"
            }
    val menu =
        matches.singleOrNull { it["functionname"]?.jsonPrimitive?.contentOrNull == "校园卡充值" }
            ?: matches.singleOrNull()
    println(
        "CARD_MENUS success=${home["success"]?.jsonPrimitive?.booleanOrNull} count=${functions?.size ?: 0} uniqueRecharge=${menu != null}"
    )
    if (menu == null) return
    val link = formText(menu["linkurl"]!!.jsonPrimitive.content)
    val menuId =
        parseQueryString(link.substringAfter('?', ""))["id"]?.takeIf { it.isNotBlank() }
            ?: menu["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return
    val methods =
        query(
            "/queryPayInfoList",
            buildJsonObject {
              put("menuid", menuId)
              put("openid", user.openid)
              put("orgid", "2")
            },
        )
    val options = (methods["data"] as? JsonObject)?.get("data") as? JsonArray
    val closed = methods["message"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("不在缴费期间")
    val hct =
        options?.any { (it as? JsonObject)?.get("payway")?.jsonPrimitive?.contentOrNull == "10" }
    println(
        "CARD_METHODS success=${methods["success"]?.jsonPrimitive?.booleanOrNull} present=${options != null} empty=${options?.isEmpty()} hct=$hct closed=$closed"
    )
  }
}
