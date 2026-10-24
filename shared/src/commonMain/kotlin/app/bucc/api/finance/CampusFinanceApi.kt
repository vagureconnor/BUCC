package app.bucc.api.finance

import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalCampusFinanceApi
import com.russhwolf.settings.Settings

data class FinanceBalance(
    val value: String? = null,
    val unit: String = "元",
    val error: String? = null,
)

data class CampusNetworkTraffic(
    val used: String? = null,
    val freeRemaining: String? = null,
    val chargedRemaining: String? = null,
    val error: String? = null,
)

data class CampusFinanceBalances(
    val internetFee: FinanceBalance,
    val campusCard: FinanceBalance,
    val reminders: List<String> = emptyList(),
    val internetTraffic: CampusNetworkTraffic = CampusNetworkTraffic(),
)

data class InternetFinanceBalances(
    val internetFee: FinanceBalance,
    val internetTraffic: CampusNetworkTraffic,
    val reminders: List<String> = emptyList(),
)

private data class ExactDecimalAmount(
    val negative: Boolean,
    val integer: String,
    val fraction: String,
)

private fun parseFinanceAmount(value: String): ExactDecimalAmount? {
  val match =
      Regex("^([+-]?)(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?$").matchEntire(value.trim())
          ?: return null
  val integer = match.groupValues[2].replace(",", "").trimStart('0').ifEmpty { "0" }
  val fraction = match.groupValues[3].trimEnd('0')
  val isZero = integer == "0" && fraction.isEmpty()
  return ExactDecimalAmount(
      negative = match.groupValues[1] == "-" && !isZero,
      integer = integer,
      fraction = fraction,
  )
}

private fun compareFinanceAmounts(left: ExactDecimalAmount, right: ExactDecimalAmount): Int {
  if (left.negative != right.negative) return if (left.negative) -1 else 1
  val magnitudeComparison =
      compareAbsoluteFinanceAmounts(left, right) * if (left.negative) -1 else 1
  return magnitudeComparison
}

private fun compareAbsoluteFinanceAmounts(
    left: ExactDecimalAmount,
    right: ExactDecimalAmount,
): Int {
  val integerComparison =
      when {
        left.integer.length != right.integer.length ->
            left.integer.length.compareTo(right.integer.length)
        else -> left.integer.compareTo(right.integer)
      }
  if (integerComparison != 0) return integerComparison
  val fractionLength = maxOf(left.fraction.length, right.fraction.length)
  return left.fraction
      .padEnd(fractionLength, '0')
      .compareTo(right.fraction.padEnd(fractionLength, '0'))
}

private fun incrementDecimalDigits(digits: String): String {
  val result = digits.toCharArray()
  for (index in result.indices.reversed()) {
    if (result[index] < '9') {
      result[index] = result[index] + 1
      return result.concatToString()
    }
    result[index] = '0'
  }
  return "1${result.concatToString()}"
}

/** Parses a plain decimal amount and formats it to cents using decimal half-up rounding. */
fun formatFinanceAmount(value: String): String? {
  val amount = parseFinanceAmount(value) ?: return null
  val fraction = amount.fraction.padEnd(3, '0')
  var integer = amount.integer
  var cents = fraction.take(2)
  if (fraction[2] >= '5') {
    val roundedCents = incrementDecimalDigits(cents)
    if (roundedCents.length > 2) {
      integer = incrementDecimalDigits(integer)
      cents = "00"
    } else {
      cents = roundedCents
    }
  }
  val sign = if (amount.negative && (integer != "0" || cents != "00")) "-" else ""
  return "$sign$integer.$cents"
}

/** Reads balances from the official ITBUAA portal using the current local SSO session. */
class CampusFinanceApi {
  private val backend =
      LocalCampusFinanceApi(
          zfwClientFactory = {
            app.bucc.api.local.LocalUpstreamClientProvider.newFinanceClient(
                app.bucc.api.local.LocalCookieStore.storage(app.bucc.api.ConnectionMode.DIRECT)
            )
          },
          ensureZfwSession = {
            // Refresh the portal CAS bridge here too; /home is the same ZFW session used by
            // /pay/card.
            CampusFinanceSession.current().ensurePaymentSession() is ZfwSessionState.Ready
          },
      )

  suspend fun loadCampusCardBalance(): FinanceBalance =
      backend
          .loadBalances(LocalAuthSessionStore.get()?.username, includeInternet = false)
          .campusCard

  suspend fun loadInternetBalances(): InternetFinanceBalances {
    val account = LocalAuthSessionStore.get()?.username
    val balances = backend.loadBalances(account, includeCard = false)
    return InternetFinanceBalances(
        balances.internetFee,
        balances.internetTraffic,
        account?.let { FinanceBalanceReminderStore.update(it, balances) }.orEmpty(),
    )
  }

  suspend fun loadBalances(): CampusFinanceBalances {
    val session = LocalAuthSessionStore.get()
    val balances = backend.loadBalances(session?.username)
    val reminders =
        session
            ?.username
            ?.takeIf { it.isNotBlank() }
            ?.let { FinanceBalanceReminderStore.update(it, balances) }
            .orEmpty()
    return balances.copy(reminders = reminders)
  }
}

/** Remembers the last warning band per account and balance to avoid repeat alerts. */
object FinanceBalanceReminderStore {
  private const val KEY_PREFIX = "finance_balance_warning"
  private var _settings: Settings? = null
  var settings: Settings
    get() = _settings ?: Settings().also { _settings = it }
    set(value) {
      _settings = value
    }

  fun update(account: String, balances: CampusFinanceBalances): List<String> = buildList {
    addIfCrossed(account, "internet", "网费", balances.internetFee)?.let { add(it) }
    addIfCrossed(account, "card", "校园卡", balances.campusCard)?.let { add(it) }
  }

  fun clear(account: String) {
    settings.remove(key(account, "internet"))
    settings.remove(key(account, "card"))
  }

  private fun MutableList<String>.addIfCrossed(
      account: String,
      id: String,
      label: String,
      balance: FinanceBalance,
  ): String? {
    if (balance.error != null) return null
    val amount = balance.value?.let(::parseFinanceAmount) ?: return null
    val threshold = if (id == "internet") "5" else "50"
    val thresholdAmount = parseFinanceAmount(threshold) ?: return null
    val currentBand = if (compareFinanceAmounts(amount, thresholdAmount) < 0) "low" else "normal"
    val preferenceKey = key(account, id)
    val previousBand = settings.getStringOrNull(preferenceKey) ?: "normal"
    settings.putString(preferenceKey, currentBand)
    return when {
      currentBand == "low" && previousBand == "normal" ->
          "$label 余额低于 ${threshold.toInt()} 元，请及时充值。"
      else -> null
    }
  }

  private fun key(account: String, balanceId: String): String = "$KEY_PREFIX:$account:$balanceId"
}
