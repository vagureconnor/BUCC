package app.bucc.api.finance

import app.bucc.api.ConnectionMode
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCasParser
import app.bucc.api.local.LocalCookieStore
import app.bucc.api.local.LocalWebVpnSupport
import app.bucc.api.local.ensureLocalWebVpnSession
import app.bucc.api.local.localBusinessUsesWebVpnGateway
import app.bucc.api.local.localServiceSsoUrl
import app.bucc.diagnostics.*
import app.bucc.model.dto.LoginRequest
import app.bucc.security.CampusCredential
import app.bucc.security.CampusSecureCredentials
import app.bucc.td.TdCampusCredentials
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Reuses BUCC CAS cookies and the official Hct gateway; never imports a browser identity. */
internal class OfficialCampusCardRechargeBackend(
    private val clientFactory: (Boolean) -> HttpClient,
    private val hasAppSession: () -> Boolean,
    private val directClientFactory: () -> HttpClient = { clientFactory(false) },
    private val prepareGatewaySession: suspend () -> Unit = ::ensureLocalWebVpnSession,
    private val financeClientFactory: () -> HttpClient = { directClientFactory() },
    private val refreshCasOnPrepare: Boolean = false,
    private val directCardOnPrepare: Boolean = false,
) : CampusRechargeBackend {
  private var client: HttpClient? = null
  private var cardClient: HttpClient? = null
  private var identity: CampusCardIdentity? = null
  private var cardSerial: String? = null
  private var attempted = false
  private var gatewayRoute = false
  private var cardApiGatewayRoute = false

  override suspend fun prepare(kind: RechargeKind): RechargePreparation {
    attempted = false
    close()
    if (!hasAppSession()) return RechargePreparation(false, "请先完成 BUCC 主账号登录。")
    gatewayRoute = localBusinessUsesWebVpnGateway()
    // The official card API rejects the WebVPN host rewrite; always read it direct.
    cardApiGatewayRoute = false
    // The card service is direct-only now. Use the direct cookie scope so its JSESSIONID is
    // written and read by the same jar, even while BUCC runs in WebVPN mode.
    val directStorage = LocalCookieStore.storage(ConnectionMode.DIRECT)
    runCatching { directStorage.clearHost("m.icw.buaa.edu.cn") }
    val connection =
        (if (directCardOnPrepare) financeClientFactory() else clientFactory(false)).also {
          client = it
        }
    return try {
      if (gatewayRoute && !directCardOnPrepare) prepareGatewaySession()
      if (refreshCasOnPrepare) ensureDirectCasSession(connection)
      val user = establishIdentity(connection)
      var cardConnection =
          (if (directCardOnPrepare) connection else financeClientFactory()).also { cardClient = it }
      val homePayload = buildJsonObject {
        put("openid", user.openid)
        put("usertype", user.usertype)
        put("ua", "mozilla/5.0 bucc/2.0.0")
        put("orgid", "2")
      }
      val home =
          try {
            query(cardConnection, "/home/openHomePageApp", homePayload)
          } catch (error: Exception) {
            // Only this first read may switch transport, before any initialization or order.
            if (error !is CardNetworkUnavailable && error !is CardResponseUnavailable) throw error
            val fallback = directClientFactory()
            if (fallback !== cardConnection) cardConnection.close()
            cardClient = null
            cardConnection = fallback.also { cardClient = it }
            query(cardConnection, "/home/openHomePageApp", homePayload)
          }
      check(home["success"]?.jsonPrimitive?.booleanOrNull == true)
      val menus = (home["data"] as? JsonObject)?.get("funcList") as? JsonArray
      val rechargeMenus =
          menus
              .orEmpty()
              .mapNotNull { it as? JsonObject }
              .filter {
                formText(it["linkurl"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    .substringBefore('?') == "/pages_other/recharge/cardrecharge/cardrecharge"
              }
      val menu =
          rechargeMenus.singleOrNull { it["functionname"]?.jsonPrimitive?.contentOrNull == "校园卡充值" }
              ?: rechargeMenus.singleOrNull()
              ?: error("Missing unambiguous official recharge menu")
      val link = formText(menu["linkurl"]!!.jsonPrimitive.content)
      val menuId =
          parseQueryString(link.substringAfter('?', ""))["id"]?.takeIf(String::isNotBlank)
              ?: menu["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
              ?: error("Missing official menu identifier")
      val methods =
          query(
              cardConnection,
              "/queryPayInfoList",
              buildJsonObject {
                put("menuid", menuId)
                put("openid", user.openid)
                put("orgid", "2")
              },
          )
      if (methods["message"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("不在缴费期间")) {
        close()
        return RechargePreparation(false, "当前不在官方缴费时间，请在开放时段重试。未创建订单。")
      }
      check(methods["success"]?.jsonPrimitive?.booleanOrNull == true)
      val options =
          (methods["data"] as? JsonObject)?.get("data") as? JsonArray
              ?: error("Missing official payment methods")
      // The official recharge page uses its Hct default when this list is empty.
      check(
          options.isEmpty() ||
              options.any {
                (it as? JsonObject)?.get("payway")?.jsonPrimitive?.contentOrNull == "10"
              }
      )
      // This initialization only reads the official card context; no amount is sent.
      val initialized =
          query(
              cardConnection,
              "/cardpay/openCardPay",
              buildJsonObject { put("openid", user.openid) },
              post = true,
          )
      check(initialized["success"]?.jsonPrimitive?.booleanOrNull == true)
      val serial =
          ((initialized["data"] as? JsonObject)?.get("cardPay") as? JsonObject)
              ?.get("idserial")
              ?.jsonPrimitive
              ?.contentOrNull
              ?.takeIf(String::isNotBlank) ?: error("Missing official card context")
      identity = user
      cardSerial = serial
      RechargePreparation(true)
    } catch (cancelled: CancellationException) {
      close()
      throw cancelled
    } catch (error: CardIdentityUnavailable) {
      recordDiagnostic(DiagnosticStage.IDENTITY, DiagnosticOutcome.FAILURE, error = error)
      close()
      RechargePreparation(false, "校园卡统一认证未接续，请重新完成 BUCC 主账号登录后重试。未创建订单。")
    } catch (error: CardNetworkUnavailable) {
      recordDiagnostic(
          DiagnosticStage.PREPARE,
          DiagnosticOutcome.FAILURE,
          error = error,
          httpStatus = error.status,
      )
      close()
      RechargePreparation(
          false,
          "校园卡服务连接失败${error.status?.let { "（HTTP $it）" }.orEmpty()}，请检查连接模式后刷新充值准备。未创建订单。",
      )
    } catch (error: CardResponseUnavailable) {
      recordDiagnostic(DiagnosticStage.PREPARE, DiagnosticOutcome.FAILURE, error = error)
      close()
      RechargePreparation(false, "校园卡服务响应无法识别，请稍后刷新充值准备。未创建订单。")
    } catch (error: Exception) {
      recordDiagnostic(DiagnosticStage.PREPARE, DiagnosticOutcome.FAILURE, error = error)
      close()
      RechargePreparation(false, "暂时无法读取校园卡充值信息或官方支付方式，未创建订单。")
    }
  }

  override suspend fun createOrder(
      kind: RechargeKind,
      amount: String,
      verificationCode: String?,
  ): OfficialPaymentPage {
    check(!attempted && kind == RechargeKind.Card)
    val user = checkNotNull(identity)
    val serial = checkNotNull(cardSerial)
    val cardConnection = checkNotNull(cardClient)
    val fen = requireNotNull(campusCardAmountInFen(amount))
    attempted = true
    try {
      val result =
          try {
            query(
                cardConnection,
                "/recharge/rechargeByHct",
                buildJsonObject {
                  put("idserial", serial)
                  put("txamt", fen)
                  put("payway", "10")
                  put("openid", user.openid)
                  put("payWay", "10")
                  put("tradetype", "WAP")
                  put("usertype", user.usertype)
                  put("orgid", "2")
                },
                post = true,
                queryIdentity = user.openid,
            )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            throw RechargeFlowException(RechargeFlowStage.CREATE_ORDER, error)
          }
      when (result["success"]?.jsonPrimitive?.booleanOrNull) {
        true -> Unit
        false -> throw RechargeFlowException(RechargeFlowStage.REJECTED)
        null -> throw RechargeFlowException(RechargeFlowStage.CREATE_ORDER)
      }
      val target =
          (result["resultData"] as? JsonObject)
              ?.get("mweb_url")
              ?.jsonPrimitive
              ?.contentOrNull
              ?.let(::formText)
              ?.takeIf(::isOfficialPaymentUrl)
              ?: throw RechargeFlowException(RechargeFlowStage.RESOLVE_PAYMENT_PAGE)
      val paymentClient = financeClientFactory()
      return try {
        connectOfficialPaymentPage(paymentClient, target)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        throw RechargeFlowException(
            RechargeFlowStage.CONNECT_PAYMENT_SESSION,
            error,
            OfficialPaymentPage(target),
        )
      } finally {
        paymentClient.close()
      }
    } finally {
      close()
    }
  }

  /** Refresh a direct CAS session from BUCC's secure credential before the card hand-off. */
  private suspend fun ensureDirectCasSession(connection: HttpClient) {
    val service = "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/1/auth"
    val loginUrl =
        "https://sso.buaa.edu.cn/login?service=${service.encodeURLParameter()}&noAutoRedirect=1"
    val page =
        try {
          connection.financeGet(loginUrl)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          return
        }
    if (page.status.value in 300..399) return
    val html = page.bodyAsText()
    val execution = LocalCasParser.extractExecution(html)
    if (
        execution.isBlank() ||
            LocalCasParser.detectCaptcha(html, "https://sso.buaa.edu.cn/captcha") != null
    )
        return
    val account = LocalAuthSessionStore.get()?.user?.schoolid?.takeIf(String::isNotBlank) ?: return
    val credential: CampusCredential? =
        TdCampusCredentials.current()
            ?.takeIf { it.username == account }
            ?.let { CampusCredential(it.username, it.password) }
            ?: CampusSecureCredentials.load(account)?.let { CampusCredential(account, it.password) }
    if (credential == null || credential.password.isBlank()) return
    val formUrl = LocalCasParser.loginFormUrl(html, loginUrl)
    val parameters =
        LocalCasParser.buildCasLoginParameters(
            html,
            LoginRequest(credential.username, credential.password, null, execution),
        )
    try {
      connection.submitForm(formUrl, parameters)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      return
    }
  }

  private suspend fun establishIdentity(connection: HttpClient): CampusCardIdentity {
    // The official login script uses this public placeholder before CAS issues an identity.
    val service = "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/1/auth"
    var target =
        URLBuilder("https://sso.buaa.edu.cn/login")
            .apply {
              parameters.append("service", service)
              parameters.append("noAutoRedirect", "1")
            }
            .buildString()
    repeat(8) {
      campusCardIdentityFromUrl(target)?.let {
        return it
      }
      val url = Url(target)
      if (
          url.protocol != URLProtocol.HTTPS ||
              url.port != 443 ||
              url.host !in setOf("sso.buaa.edu.cn", "m.icw.buaa.edu.cn") ||
              !url.user.isNullOrEmpty() ||
              !url.password.isNullOrEmpty()
      )
          throw CardIdentityUnavailable()
      // Production uses the direct card service; legacy/test paths retain WebVPN wrapping.
      val routed =
          if (directCardOnPrepare) target
          else if (url.host == "sso.buaa.edu.cn") localServiceSsoUrl(target)
          else if (gatewayRoute) LocalWebVpnSupport.toWebVpnUrl(target) else target
      val response =
          try {
            connection.financeGet(routed)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            throw CardNetworkUnavailable(cause = error)
          }
      val location = response.headers[HttpHeaders.Location] ?: throw CardIdentityUnavailable()
      target = resolveCardRedirect(target, location)
    }
    throw CardIdentityUnavailable()
  }

  private suspend fun query(
      connection: HttpClient,
      path: String,
      payload: JsonObject,
      post: Boolean = false,
      queryIdentity: String? = null,
  ): JsonObject {
    val encrypted = CampusCardCodec.encode(payload)
    val response =
        try {
          diagnosticFinanceRequest("https://m.icw.buaa.edu.cn/card$path", mutation = post) {
            // Route before the request; an uncertain order POST is never replayed on another path.
            val target = "https://m.icw.buaa.edu.cn/card$path"
            connection.request(
                if (cardApiGatewayRoute) LocalWebVpnSupport.toWebVpnUrl(target) else target
            ) {
              method = if (post) HttpMethod.Post else HttpMethod.Get
              header("X-Requested-With", "XMLHttpRequest")
              header("session-type", "uniapp")
              header("isWechatApp", "true")
              header("orgid", "2")
              header(HttpHeaders.Referrer, "https://m.icw.buaa.edu.cn/card/")
              contentType(ContentType.Application.Json)
              if (post) {
                queryIdentity?.let { parameter("openid", it) }
                setBody(encrypted.toString())
              } else parameter("datajson", encrypted["datajson"]!!.jsonPrimitive.content)
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          throw CardNetworkUnavailable(cause = error)
        }
    if (!response.status.isSuccess()) {
      throw CardNetworkUnavailable(response.status.value)
    }
    return try {
      CampusCardCodec.decode(response.bodyAsText())
    } catch (error: Exception) {
      throw CardResponseUnavailable(error)
    }
  }

  fun close() {
    cardClient?.takeIf { it !== client }?.close()
    cardClient = null
    client?.close()
    client = null
    identity = null
    cardSerial = null
  }
}

private class CardIdentityUnavailable : Exception()

private class CardNetworkUnavailable(val status: Int? = null, cause: Throwable? = null) :
    Exception(cause)

private class CardResponseUnavailable(cause: Throwable) : Exception(cause)

private fun resolveCardRedirect(base: String, location: String): String {
  val upstream = LocalWebVpnSupport.fromWebVpnUrl(location)
  return resolveFinanceRedirect(Url(base), upstream)
}

internal suspend fun connectOfficialPaymentPage(
    client: HttpClient,
    initial: String,
): OfficialPaymentPage =
    withLocalDiagnostics(DiagnosticModule.RECHARGE, DiagnosticOperation.PAYMENT_CONNECT) {
      if (!isOfficialPaymentUrl(initial) || isPaymentLoginUrl(initial))
          throw RechargeFlowException(RechargeFlowStage.CONNECT_PAYMENT_SESSION)
      var target = initial
      var unifiedAuthenticationStarted = false
      repeat(12) {
        if (Url(target).encodedPath == "/signIn" && officialPaymentLoginBackUrl(target) != null) {
          target = URLBuilder(target).apply { encodedPath = "/login" }.buildString()
        }
        val url = Url(target)
        val secure =
            url.protocol == URLProtocol.HTTPS &&
                url.port == 443 &&
                url.user.isNullOrEmpty() &&
                url.password.isNullOrEmpty()
        val paymentPage = isOfficialPaymentUrl(target) && !isPaymentLoginUrl(target)
        val paymentLogin = secure && url.host == "pass.cc-pay.cn" && url.encodedPath == "/login"
        val callback =
            paymentLogin && unifiedAuthenticationStarted && url.parameters["ticket"] != null
        val backUrl =
            if (paymentLogin) url.parameters["backUrl"]?.takeIf(::isOfficialPaymentUrl) else null
        val casService = url.parameters.getAll("service")?.singleOrNull()
        val casLogin =
            secure &&
                url.host == "sso.buaa.edu.cn" &&
                url.encodedPath == "/login" &&
                casService?.let(::isOfficialPaymentCasService) == true
        if (!paymentPage && !callback && !(paymentLogin && backUrl != null) && !casLogin) {
          val category =
              when {
                url.protocol != URLProtocol.HTTPS -> "payment_redirect_insecure"
                paymentLogin -> "payment_redirect_login_back"
                url.host == "pass.cc-pay.cn" -> "payment_redirect_pass_route"
                url.host == "cashier.cc-pay.cn" -> "payment_redirect_cashier_route"
                url.host == "mall.cc-pay.cn" -> "payment_redirect_mall_route"
                url.host == "sso.buaa.edu.cn" -> "payment_redirect_sso_route"
                else -> "payment_redirect_other_origin"
              }
          recordDiagnostic(
              DiagnosticStage.CONNECT_PAYMENT_SESSION,
              DiagnosticOutcome.BLOCKED,
              businessCode = category,
          )
          throw RechargeFlowException(RechargeFlowStage.CONNECT_PAYMENT_SESSION)
        }
        if (casLogin) unifiedAuthenticationStarted = true
        // The official payment CAS login must stay on its direct origin; the WebVPN
        // gateway answers with its own login page and cannot issue a service ticket.
        val response = client.financeGet(target)
        val redirect = response.headers[HttpHeaders.Location]
        if (redirect != null && response.status.value in 300..399) {
          target = resolveCardRedirect(target, redirect)
        } else if (
            paymentLogin &&
                backUrl != null &&
                !unifiedAuthenticationStarted &&
                response.status.isSuccess()
        ) {
          // Official login component: /login?backUrl=…&channel=BUAASSO.
          // CAS cookies belong to BUCC; no separate payment password is entered.
          unifiedAuthenticationStarted = true
          target =
              URLBuilder(target)
                  .apply {
                    parameters.remove("channel")
                    parameters.append("channel", "BUAASSO")
                  }
                  .buildString()
        } else {
          if (casLogin && response.status.isSuccess()) {
            // The official CAS login page is a valid hand-off: the embedded browser
            // may already hold a CAS session and can finish the login itself.
            return@withLocalDiagnostics OfficialPaymentPage(initial)
          }
          if (
              !paymentPage ||
                  !response.status.isSuccess() ||
                  Regex("(?is)<input[^>]+type\\s*=\\s*['\"]?password")
                      .containsMatchIn(response.bodyAsText())
          )
              throw RechargeFlowException(RechargeFlowStage.CONNECT_PAYMENT_SESSION)
          return@withLocalDiagnostics OfficialPaymentPage(target)
        }
      }
      throw RechargeFlowException(RechargeFlowStage.CONNECT_PAYMENT_SESSION)
    }
