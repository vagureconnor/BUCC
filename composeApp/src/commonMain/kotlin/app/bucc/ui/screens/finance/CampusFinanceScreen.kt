package app.bucc.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bucc.api.finance.CampusFinanceApi
import app.bucc.api.finance.CampusFinanceBalances
import app.bucc.api.finance.CampusFinanceSession
import app.bucc.api.finance.CampusNetworkTraffic
import app.bucc.api.finance.CampusRechargeBackend
import app.bucc.api.finance.CampusRechargeController
import app.bucc.api.finance.CampusRechargeState
import app.bucc.api.finance.FinanceBalance
import app.bucc.api.finance.InternetFinanceBalances
import app.bucc.api.finance.OfficialCampusRechargeBackend
import app.bucc.api.finance.OfficialPaymentPage
import app.bucc.api.finance.RechargeKind
import app.bucc.api.finance.ZfwSessionBackend
import app.bucc.api.finance.ZfwSessionState
import app.bucc.api.finance.formatFinanceAmount
import app.bucc.api.finance.resumeOfficialPaymentPage
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun CampusFinanceScreen(
    sessionOverride: ZfwSessionBackend? = null,
    campusCardBalanceLoaderOverride: (suspend () -> FinanceBalance)? = null,
    internetBalancesLoaderOverride: (suspend () -> InternetFinanceBalances)? = null,
    balancesLoaderOverride: (suspend () -> CampusFinanceBalances)? = null,
    rechargeBackendOverride: CampusRechargeBackend? = null,
    browserInitializer: suspend () -> Boolean = ::initializePaymentBrowser,
    onBackHandlerChanged: ((() -> Unit)?) -> Unit = {},
    paymentBrowser: (@Composable (OfficialPaymentPage) -> Unit)? = null,
    paymentPageResumer: suspend (String) -> OfficialPaymentPage = ::resumeOfficialPaymentPage,
) {
  var paymentPage by remember { mutableStateOf<OfficialPaymentPage?>(null) }
  var browserBackHandler by remember { mutableStateOf<(() -> Boolean)?>(null) }
  var selectedTab by remember { mutableStateOf(0) }
  var internetVerifiedThisVisit by remember { mutableStateOf(false) }
  val rechargeBackend =
      remember(rechargeBackendOverride) {
        rechargeBackendOverride ?: OfficialCampusRechargeBackend()
      }
  DisposableEffect(rechargeBackend) {
    onDispose { (rechargeBackend as? OfficialCampusRechargeBackend)?.close() }
  }
  val backHandlerChanged by rememberUpdatedState(onBackHandlerChanged)
  DisposableEffect(paymentPage != null, browserBackHandler) {
    backHandlerChanged(
        if (paymentPage != null) ({ if (browserBackHandler?.invoke() != true) paymentPage = null })
        else null
    )
    onDispose { backHandlerChanged(null) }
  }
  paymentPage?.let {
    if (paymentBrowser != null) paymentBrowser(it)
    else
        OfficialPaymentBrowser(
            it,
            onBackHandlerChanged = { handler -> browserBackHandler = handler },
            onExternalPaymentAppReturn = { paymentPage = null },
        )
    return
  }
  val financeApi = remember { CampusFinanceApi() }
  val session = remember(sessionOverride) { sessionOverride ?: CampusFinanceSession.current() }
  val sessionState by session.state.collectAsState()
  var loginCode by remember { mutableStateOf("") }
  var smsCode by remember { mutableStateOf("") }
  var verifying by remember { mutableStateOf(false) }
  var networkVerificationError by remember { mutableStateOf<String?>(null) }
  val coroutineScope = rememberCoroutineScope()
  var campusCardBalance by remember { mutableStateOf<FinanceBalance?>(null) }
  var internetBalances by remember { mutableStateOf<InternetFinanceBalances?>(null) }
  var refreshingCard by remember { mutableStateOf(false) }
  var refreshingInternet by remember { mutableStateOf(false) }

  suspend fun refreshCampusCard() {
    if (refreshingCard) return
    refreshingCard = true
    campusCardBalance =
        try {
          val refreshed =
              campusCardBalanceLoaderOverride?.invoke() ?: financeApi.loadCampusCardBalance()
          retainFinanceValueOnRefresh(refreshed, campusCardBalance)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          retainFinanceValueOnRefresh(
              FinanceBalance(error = "暂时无法读取校园卡余额，请稍后刷新。"),
              campusCardBalance,
          )
        } finally {
          refreshingCard = false
        }
  }

  suspend fun refreshInternetBalances() {
    if (!internetVerifiedThisVisit || refreshingInternet) return
    refreshingInternet = true
    internetBalances =
        try {
          val refreshed =
              withTimeout(20_000) {
                internetBalancesLoaderOverride?.invoke()
                    ?: balancesLoaderOverride?.invoke()?.let {
                      InternetFinanceBalances(it.internetFee, it.internetTraffic, it.reminders)
                    }
                    ?: financeApi.loadInternetBalances()
              }
          InternetFinanceBalances(
              internetFee =
                  retainFinanceValueOnRefresh(refreshed.internetFee, internetBalances?.internetFee),
              internetTraffic =
                  retainTrafficValueOnRefresh(
                      refreshed.internetTraffic,
                      internetBalances?.internetTraffic,
                  ),
              reminders = refreshed.reminders,
          )
        } catch (_: TimeoutCancellationException) {
          currentCoroutineContext().ensureActive()
          val error = "网费信息加载超时，请点击刷新重试。"
          InternetFinanceBalances(
              internetFee =
                  retainFinanceValueOnRefresh(
                      FinanceBalance(error = error),
                      internetBalances?.internetFee,
                  ),
              internetTraffic =
                  retainTrafficValueOnRefresh(
                      CampusNetworkTraffic(error = error),
                      internetBalances?.internetTraffic,
                  ),
              reminders = internetBalances?.reminders.orEmpty(),
          )
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          val error = "暂时无法连接，请稍后刷新。"
          InternetFinanceBalances(
              internetFee =
                  retainFinanceValueOnRefresh(
                      FinanceBalance(error = error),
                      internetBalances?.internetFee,
                  ),
              internetTraffic =
                  retainTrafficValueOnRefresh(
                      CampusNetworkTraffic(error = error),
                      internetBalances?.internetTraffic,
                  ),
              reminders = internetBalances?.reminders.orEmpty(),
          )
        } finally {
          refreshingInternet = false
        }
  }

  suspend fun verifyNetworkSession(request: suspend () -> ZfwSessionState) {
    if (verifying) return
    verifying = true
    networkVerificationError = null
    try {
      val result = withTimeout(20_000) { request() }
      when (result) {
        is ZfwSessionState.Ready -> {
          internetVerifiedThisVisit = true
          refreshInternetBalances()
        }
        is ZfwSessionState.SmsChallenge -> smsCode = ""
        else -> Unit
      }
    } catch (_: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      networkVerificationError = "校园网登录状态检查超时，请重试。"
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      networkVerificationError = "校园网登录状态暂时无法读取，请重试。"
    } finally {
      verifying = false
    }
  }

  fun requestNetworkVerification(refreshCaptcha: Boolean = false) {
    if (verifying || internetVerifiedThisVisit) return
    loginCode = ""
    coroutineScope.launch {
      verifyNetworkSession { session.ensureSession(refreshCaptcha = refreshCaptcha) }
    }
  }

  LaunchedEffect(financeApi) { refreshCampusCard() }
  LaunchedEffect(financeApi, selectedTab, internetVerifiedThisVisit, internetBalances) {
    if (selectedTab == 1 && internetVerifiedThisVisit && internetBalances == null) {
      refreshInternetBalances()
    }
  }

  val showSilentNetworkLoading =
      selectedTab == 1 &&
          internetBalances == null &&
          networkVerificationError == null &&
          (verifying || internetVerifiedThisVisit || sessionState is ZfwSessionState.Checking)

  if (showSilentNetworkLoading) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
    return
  }

  LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = "校园财务",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        if (selectedTab == 0) {
          Text(
              text = "校园卡与网费分开查询；网费信息需先完成本次登录验证码验证。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    item {
      TabRow(selectedTabIndex = selectedTab) {
        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("校园卡") })
        Tab(
            selected = selectedTab == 1,
            onClick = {
              selectedTab = 1
              requestNetworkVerification()
            },
            text = { Text("网费") },
        )
      }
    }
    if (selectedTab == 0) {
      item { FinanceSectionHeading("校园卡") }
      item {
        FinanceBalanceCard(
            title = "校园卡余额",
            balance = campusCardBalance,
            refreshing = refreshingCard,
            onRefresh = { coroutineScope.launch { refreshCampusCard() } },
            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
        )
      }
      item {
        CampusRechargeEntry(
            kind = RechargeKind.Card,
            networkVerified = false,
            backendOverride = rechargeBackend,
            browserInitializer = browserInitializer,
            paymentPageResumer = paymentPageResumer,
            onOpenPayment = { paymentPage = it },
        )
      }
    } else if (!internetVerifiedThisVisit) {
      item {
        NetworkVerificationContent(
            sessionState = sessionState,
            requestError = networkVerificationError,
            loginCode = loginCode,
            verifying = verifying,
            onLoginCodeChange = { loginCode = it },
            onVerify = {
              val code = loginCode
              coroutineScope.launch {
                verifyNetworkSession { session.submitLoginCaptcha(code).also { loginCode = "" } }
              }
            },
            smsCode = smsCode,
            onSmsCodeChange = { smsCode = it },
            onSubmitSmsCode = {
              val code = smsCode
              coroutineScope.launch {
                verifyNetworkSession { session.submitSmsCode(code).also { smsCode = "" } }
              }
            },
            onRefreshCaptcha = { requestNetworkVerification(refreshCaptcha = true) },
            onRetry = { requestNetworkVerification(refreshCaptcha = true) },
        )
      }
    } else if (internetBalances == null) {
      item {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
      }
    } else {
      internetBalances
          ?.reminders
          ?.takeIf { it.isNotEmpty() }
          ?.let { reminders -> item { FinanceReminderCard(reminders) } }
      item { FinanceSectionHeading("网费与流量") }
      item {
        FinanceBalanceCard(
            title = "网费余额",
            balance = internetBalances?.internetFee,
            traffic = internetBalances?.internetTraffic,
            showTraffic = true,
            refreshing = refreshingInternet,
            onRefresh = { coroutineScope.launch { refreshInternetBalances() } },
            icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
        )
      }
      item {
        CampusRechargeEntry(
            kind = RechargeKind.Internet,
            networkVerified = true,
            backendOverride = rechargeBackend,
            browserInitializer = browserInitializer,
            paymentPageResumer = paymentPageResumer,
            onOpenPayment = { paymentPage = it },
        )
      }
    }
  }
}

@Composable
private fun NetworkVerificationContent(
    sessionState: ZfwSessionState,
    requestError: String?,
    loginCode: String,
    smsCode: String,
    verifying: Boolean,
    onLoginCodeChange: (String) -> Unit,
    onSmsCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onSubmitSmsCode: () -> Unit,
    onRefreshCaptcha: () -> Unit,
    onRetry: () -> Unit,
) {
  if (requestError != null) {
    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(requestError, color = MaterialTheme.colorScheme.error)
        TextButton(enabled = !verifying, onClick = onRetry) { Text("重新验证校园网") }
      }
    }
    return
  }
  when (val auth = sessionState) {
    is ZfwSessionState.LoginCaptcha ->
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("校园网身份验证", style = MaterialTheme.typography.titleMedium)
            AsyncImage(
                model = auth.image,
                contentDescription = "校园网登录验证码",
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
            OutlinedTextField(
                loginCode,
                onLoginCodeChange,
                label = { Text("登录验证码") },
                singleLine = true,
                enabled = !verifying,
                modifier = Modifier.fillMaxWidth(),
            )
            auth.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !verifying && loginCode.isNotBlank(), onClick = onVerify) {
              Text(if (verifying) "正在验证…" else "验证并继续")
            }
            TextButton(enabled = !verifying, onClick = onRefreshCaptcha) { Text("刷新登录验证码") }
          }
        }
    is ZfwSessionState.SmsChallenge ->
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("短信身份验证", style = MaterialTheme.typography.titleMedium)
            Text("请输入校园网发送给你的短信验证码。")
            OutlinedTextField(
                smsCode,
                onSmsCodeChange,
                label = { Text("短信验证码") },
                singleLine = true,
                enabled = !verifying,
                modifier = Modifier.fillMaxWidth(),
            )
            auth.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !verifying && smsCode.isNotBlank(), onClick = onSubmitSmsCode) {
              Text(if (verifying) "正在验证…" else "验证短信验证码")
            }
          }
        }
    is ZfwSessionState.Failed ->
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(auth.reason, color = MaterialTheme.colorScheme.error)
            TextButton(enabled = !verifying, onClick = onRetry) { Text("重新验证校园网") }
          }
        }
    ZfwSessionState.Ready ->
        if (verifying) NetworkVerificationLoadingCard()
        else
            Card(Modifier.fillMaxWidth()) {
              Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !verifying, onClick = onRetry) { Text("重新验证校园网") }
              }
            }
    ZfwSessionState.Checking -> if (verifying) NetworkVerificationLoadingCard() else Unit
  }
}

@Composable
private fun NetworkVerificationLoadingCard() {
  Box(
      Modifier.fillMaxWidth().padding(vertical = 32.dp),
      contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
  }
}

@Composable
private fun CampusRechargeEntry(
    kind: RechargeKind,
    networkVerified: Boolean,
    backendOverride: CampusRechargeBackend?,
    browserInitializer: suspend () -> Boolean,
    paymentPageResumer: suspend (String) -> OfficialPaymentPage,
    onOpenPayment: (OfficialPaymentPage) -> Unit,
) {
  var amount by remember { mutableStateOf("") }
  var verificationCode by remember { mutableStateOf("") }
  val backend = remember(backendOverride) { backendOverride ?: OfficialCampusRechargeBackend() }
  val pageResumerState = rememberUpdatedState(paymentPageResumer)
  val controller =
      remember(backend) {
        CampusRechargeController(backend) { target -> pageResumerState.value(target) }
      }
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<CampusRechargeState>(CampusRechargeState.Loading) }
  var captcha by remember { mutableStateOf<ByteArray?>(null) }
  var browserReady by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  suspend fun prepare() {
    busy = true
    browserReady = false
    state = CampusRechargeState.Loading
    verificationCode = ""
    captcha = null
    var initializingBrowser = false
    try {
      withTimeout(20_000) { controller.prepare(kind) }
      state = controller.state
      if (state is CampusRechargeState.Ready) {
        captcha = (state as CampusRechargeState.Ready).captchaImage
        initializingBrowser = true
        // The JVM browser may need up to a minute to install its first-run runtime.
        browserReady = withTimeout(75_000) { browserInitializer() }
        if (!browserReady) state = CampusRechargeState.Failed("支付页面组件暂不可用，未创建订单。")
      }
    } catch (_: TimeoutCancellationException) {
      currentCoroutineContext().ensureActive()
      state =
          CampusRechargeState.Failed(
              if (initializingBrowser) "支付页面组件初始化超时，未创建订单。请点击刷新充值准备重试。"
              else "准备支付会话超时，未创建订单。请点击刷新充值准备重试。"
          )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      state = CampusRechargeState.Failed("支付页面组件暂不可用，未创建订单。")
    } finally {
      busy = false
    }
  }
  LaunchedEffect(kind, networkVerified) {
    if (kind == RechargeKind.Card || networkVerified) prepare()
    else state = CampusRechargeState.Unavailable("请先完成上方校园网验证码验证。")
  }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (kind == RechargeKind.Internet && state is CampusRechargeState.Loading) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
      } else {
        Text(
            if (kind == RechargeKind.Card) "校园卡充值" else "网费充值",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("充值金额（元）") },
            enabled = !busy && controller.canPrepare,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        captcha?.let { bytes ->
          AsyncImage(
              model = bytes,
              contentDescription = "充值验证码",
              modifier = Modifier.height(64.dp).fillMaxWidth(),
          )
          OutlinedTextField(
              value = verificationCode,
              onValueChange = { verificationCode = it },
              label = { Text("充值验证码") },
              enabled = !busy && controller.canSubmit,
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )
        }
        when (val current = state) {
          is CampusRechargeState.Unavailable ->
              Text(current.reason, color = MaterialTheme.colorScheme.error)
          is CampusRechargeState.Failed ->
              Text(current.reason, color = MaterialTheme.colorScheme.error)
          CampusRechargeState.Loading -> Text("正在准备支付会话…")
          else ->
              Text("提交后直接在应用内打开官方支付页面，由你自行选择支付方式和扫码。", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            enabled =
                !busy &&
                    browserReady &&
                    controller.canSubmit &&
                    (captcha == null || verificationCode.isNotBlank()),
            onClick = {
              if (!busy) {
                busy = true
                scope.launch {
                  try {
                    withTimeout(20_000) { controller.create(amount, verificationCode) }
                    state = controller.state
                    verificationCode = ""
                    (state as? CampusRechargeState.OfficialPage)?.let { onOpenPayment(it.page) }
                  } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    state = controller.state
                  } catch (cancelled: CancellationException) {
                    state = controller.state
                    throw cancelled
                  } finally {
                    state = controller.state
                    busy = false
                  }
                }
              }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (busy) "正在处理…" else "提交充值并打开支付页面")
        }
        (state as? CampusRechargeState.Failed)
            ?.takeIf { it.continuation != null }
            ?.let {
              TextButton(
                  enabled = !busy,
                  onClick = {
                    if (!busy) {
                      busy = true
                      scope.launch {
                        try {
                          controller.resumePayment()
                          state = controller.state
                          (state as? CampusRechargeState.OfficialPage)?.let { page ->
                            onOpenPayment(page.page)
                          }
                        } finally {
                          busy = false
                        }
                      }
                    }
                  },
              ) {
                Text(if (busy) "正在接续支付页…" else "重新接续支付页（不会重新建单）")
              }
            }
        TextButton(
            enabled = !busy && controller.canPrepare,
            onClick = { scope.launch { prepare() } },
        ) {
          Text("刷新充值准备")
        }
      }
    }
  }
}

@Composable
private fun FinanceTrafficRow(label: String, value: String?) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Text(
        value?.takeIf(String::isNotBlank) ?: "暂无数据",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun FinanceReminderCard(reminders: List<String>) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
          Icons.Default.ErrorOutline,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onErrorContainer,
      )
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "余额提醒",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        reminders.forEach { reminder ->
          Text(
              reminder,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      }
    }
  }
}

@Composable
private fun FinanceBalanceCard(
    title: String,
    balance: FinanceBalance?,
    traffic: CampusNetworkTraffic? = null,
    showTraffic: Boolean = false,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    icon: @Composable () -> Unit,
) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          androidx.compose.foundation.layout.Box(
              modifier = Modifier.padding(10.dp),
              contentAlignment = Alignment.Center,
          ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides
                    MaterialTheme.colorScheme.onPrimaryContainer
            ) {
              icon()
            }
          }
        }
        Text(
            text = title,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
      }
      Spacer(Modifier.height(10.dp))
      val amount = balance?.value
      val formattedAmount = amount?.let(::formatFinanceAmount)
      if (!formattedAmount.isNullOrBlank()) {
        Text(
            text = "$formattedAmount ${balance.unit}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        balance.error?.let {
          Text(
              "上次读取值；本次刷新失败：$it",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
          )
        }
      } else {
        Text(
            text = balance?.error ?: if (!amount.isNullOrBlank()) "官方余额格式无法识别。" else "正在查询官方余额…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (showTraffic) {
        Spacer(Modifier.height(10.dp))
        FinanceTrafficRow("已用流量", traffic?.used)
        FinanceTrafficRow("免费流量剩余（不含套餐）", traffic?.freeRemaining)
        FinanceTrafficRow("计费流量剩余（不含套餐）", traffic?.chargedRemaining)
        traffic?.error?.let {
          Text(
              it,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
          )
        }
      }
      Spacer(Modifier.height(12.dp))
      androidx.compose.material3.OutlinedButton(
          onClick = onRefresh,
          enabled = !refreshing,
          modifier = Modifier.fillMaxWidth(),
      ) {
        if (refreshing) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Spacer(Modifier.size(8.dp))
        } else {
          Icon(
              Icons.Default.AccountBalanceWallet,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.size(8.dp))
        }
        Text(if (showTraffic) "刷新网费信息" else "刷新余额")
      }
    }
  }
}

internal fun retainFinanceValueOnRefresh(
    refreshed: FinanceBalance,
    previous: FinanceBalance?,
): FinanceBalance {
  if (!refreshed.value.isNullOrBlank() || refreshed.error == null) return refreshed
  val previousValue = previous?.value?.takeIf(String::isNotBlank) ?: return refreshed
  return refreshed.copy(value = previousValue, unit = previous.unit)
}

internal fun retainTrafficValueOnRefresh(
    refreshed: CampusNetworkTraffic,
    previous: CampusNetworkTraffic?,
): CampusNetworkTraffic {
  if (refreshed.error == null) return refreshed
  val previousTraffic = previous ?: return refreshed
  if (
      previousTraffic.used.isNullOrBlank() &&
          previousTraffic.freeRemaining.isNullOrBlank() &&
          previousTraffic.chargedRemaining.isNullOrBlank()
  ) {
    return refreshed
  }
  return refreshed.copy(
      used = previousTraffic.used,
      freeRemaining = previousTraffic.freeRemaining,
      chargedRemaining = previousTraffic.chargedRemaining,
  )
}

@Composable
private fun FinanceSectionHeading(title: String) {
  Text(
      text = title,
      modifier = Modifier.padding(top = 4.dp),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.primary,
  )
}
