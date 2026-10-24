package app.bucc.api

import app.bucc.api.auth.ApiCallException
import app.bucc.api.feature.JudgeApi
import app.bucc.api.finance.*
import app.bucc.api.local.*
import app.bucc.api.storage.CredentialStore
import app.bucc.homework.*
import app.bucc.repository.ScheduleStore
import app.bucc.security.CampusSecureCredentials
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * Explicit diagnostic: business GETs only, optional one-shot saved-credential authentication. All
 * sessions are cloned in memory. Only counts, shapes and safe failure codes are printed.
 */
object HomeworkSavedSessionProbe {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    val memory = MapSettings()
    Settings().let { saved ->
      saved.keys.forEach { key -> saved.getStringOrNull(key)?.let { memory.putString(key, it) } }
    }
    ConnectionModeStore.settings = memory
    LocalAuthSessionStore.settings = memory
    LocalCookieStore.settings = memory
    ScheduleStore.settings = memory
    ConnectionRuntime.resolveSelectedMode()
    val preferredMode = ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT
    val modes = (listOf(preferredMode) + ConnectionMode.entries).distinct()
    val sessionAndMode =
        modes.firstNotNullOfOrNull { mode ->
          ConnectionModeStore.save(mode)
          ConnectionRuntime.resolveSelectedMode()
          LocalAuthSessionStore.get()?.let { it to mode }
        }
    val account = sessionAndMode?.first?.username ?: CredentialStore.getUsername()
    if (account.isNullOrBlank()) {
      println("PROBE_FAILURE stage=session code=missing")
      return@runBlocking
    }
    val mode = sessionAndMode?.second ?: preferredMode
    ConnectionModeStore.save(mode)
    ConnectionRuntime.resolveSelectedMode()
    println("SESSION mode=${mode.storageKey} present=${sessionAndMode != null}")
    val restoreLogin = "--restore-login" in args
    val authPosts = mutableMapOf<String, Int>()
    var selectedJudgeCourse: String? = null
    val judgeSiteIds = mutableSetOf<Pair<String, String>>()

    fun client(redirects: Boolean, cookies: CookiesStorage) =
        HttpClient(
                if ("--production-transport" in args) app.bucc.api.core.getDefaultEngine()
                else CIO.create { proxy = null },
            ) {
              followRedirects = redirects
              install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 30000
              }
              install(HttpCookies) { storage = cookies }
            }
            .apply {
              plugin(HttpSend).intercept { request ->
                val upstream = Url(LocalWebVpnSupport.fromWebVpnUrl(request.url.buildString()))
                val path = upstream.encodedPath
                if (request.method.value != "GET") {
                  check(
                      restoreLogin &&
                          request.method.value == "POST" &&
                          ((upstream.host == "sso.buaa.edu.cn" && path == "/login") ||
                              (upstream.host == "accoding.buaa.edu.cn" && path == "/user/login"))
                  )
                  authPosts[upstream.host] = authPosts.getOrElse(upstream.host) { 0 } + 1
                  check(authPosts.getValue(upstream.host) == 1) { "Authentication replay blocked" }
                }
                val allowed =
                    when (upstream.host) {
                      "d.buaa.edu.cn",
                      "sso.buaa.edu.cn",
                      "uc.buaa.edu.cn" -> true
                      "byxt.buaa.edu.cn" ->
                          path in
                              setOf(
                                  "/jwapp/sys/homeapp/api/home/currentUser.do",
                                  "/jwapp/sys/homeapp/api/home/student/schoolCalendars.do",
                                  "/jwapp/sys/homeapp/api/home/getTermWeeks.do",
                              )
                      "zfw.buaa.edu.cn" -> path == "/pay/card" || path.endsWith(".js")
                      "m.icw.buaa.edu.cn" ->
                          path == "/card/" ||
                              path == "/card/home/openHomePageApp" ||
                              path.startsWith("/card/home/sisUstbMiniReturnPage/")
                      "judge.buaa.edu.cn" ->
                          !Regex("(?i)(logout|submit|delete|remove|save|upload)")
                              .containsMatchIn(path)
                      "accoding.buaa.edu.cn" ->
                          path in setOf("/contest/index", "/api/users/me", "/user/login") ||
                              Regex("/api/contests/[0-9]+(?:/submissions)?").matches(path)
                      else -> false
                    }
                if (!allowed) println("PROBE_BLOCKED host=${upstream.host} path=$path")
                check(allowed) { "Unapproved diagnostic path" }
                if (upstream.host == "judge.buaa.edu.cn" && path == "/courselist.jsp") {
                  upstream.parameters["courseID"]
                      ?.takeIf { it != "0" }
                      ?.let { selectedJudgeCourse = it }
                }
                val call =
                    try {
                      execute(request)
                    } catch (error: Exception) {
                      println(
                          "HTTP_FAILURE host=${upstream.host} path=$path type=${error::class.simpleName}"
                      )
                      throw error
                    }
                if (
                    upstream.host == "judge.buaa.edu.cn" &&
                        path == "/assignment/index.jsp" &&
                        upstream.parameters["assignID"] == null
                ) {
                  val course = selectedJudgeCourse
                  val html = call.response.bodyAsText()
                  val ids =
                      Regex("""(?i)assignID=(\d+)""")
                          .findAll(html)
                          .map { it.groupValues[1] }
                          .toSet()
                  if (course != null) ids.forEach { judgeSiteIds += course to it }
                  println(
                      "JUDGE_LIST_SHAPE chars=${html.length} ids=${ids.size} selected=${course != null}"
                  )
                }
                call.response.headers["Location"]?.let { location ->
                  if (location.startsWith("http://") || location.startsWith("https://")) {
                    val next = Url(LocalWebVpnSupport.fromWebVpnUrl(location))
                    println("REDIRECT host=${next.host} path=${next.encodedPath}")
                  }
                }
                println(
                    "HTTP method=${request.method.value} host=${upstream.host} path=$path gateway=${request.url.host == "d.buaa.edu.cn"} status=${call.response.status.value}"
                )
                call
              }
            }
    val factory = LocalUpstreamClientProvider.clientFactory
    val isolatedFactory = LocalUpstreamClientProvider.isolatedClientFactory
    LocalUpstreamClientProvider.clientFactory = { redirects ->
      client(redirects, LocalCookieStore.storage())
    }
    LocalUpstreamClientProvider.isolatedClientFactory = { redirects, cookies ->
      client(redirects, cookies)
    }
    val accountClient =
        LocalUpstreamClientProvider.newClient(
            HomeworkCookies(account, HomeworkSource.ACCODING, memory)
        )
    val adapter = AccodingHomeworkAdapter(accountClient)
    var stage = "authentication"
    try {
      val auth = LocalAuthServiceBackend()
      val preload = auth.preloadLoginState().getOrThrow()
      var restored = preload.userData != null
      if (!restored && restoreLogin) {
        val saved = requireNotNull(CampusSecureCredentials.load(account))
        auth.login(saved.username, saved.password, null, preload.execution).getOrThrow()
        restored = true
      }
      println("AUTH restored=$restored credentialPosts=${authPosts.values.sum()}")
      stage = "gateway"
      ensureLocalWebVpnSession()
      if ("--finance-only" in args) {
        for (url in listOf("https://zfw.buaa.edu.cn/pay/card", "https://m.icw.buaa.edu.cn/card/")) {
          val target = localGatewayServiceUrl(url)
          val response = LocalUpstreamClientProvider.shared().get(target)
          val html = response.bodyAsText()
          println(
              "FINANCE_READ host=${Url(url).host} status=${response.status.value} chars=${html.length}"
          )
          val scripts =
              Regex("""(?i)<script[^>]*src\s*=\s*["']([^"']+)["']""")
                  .findAll(html)
                  .map { it.groupValues[1].substringBefore('?') }
                  .filter { !it.startsWith("http") || it.startsWith("https://zfw.buaa.edu.cn/") }
                  .toList()
          println("FINANCE_SCRIPT_PATHS paths=$scripts")
          println(
              "FINANCE_SHAPE markers=${listOf("qrcode", "QRCode", "canvas", "OneCardForm", "iframe", "verifyCode").filter { html.contains(it) }}"
          )
        }
        val card = LocalUpstreamClientProvider.newNoRedirectClient()
        try {
          var target =
              "https://sso.buaa.edu.cn/login?service=https%3A%2F%2Fm.icw.buaa.edu.cn%2Fcard%2Fhome%2FsisUstbMiniReturnPage%2F112233%2F1%2Fauth&noAutoRedirect=1"
          var identity: CampusCardIdentity? = null
          for (step in 0 until 8) {
            identity = campusCardIdentityFromUrl(target)
            if (identity != null) break
            val uri = Url(target)
            check(uri.host in setOf("sso.buaa.edu.cn", "m.icw.buaa.edu.cn"))
            val response =
                card.get(
                    if (uri.host == "sso.buaa.edu.cn") localServiceSsoUrl(target)
                    else localGatewayServiceUrl(target)
                )
            val location = requireNotNull(response.headers["Location"])
            target =
                java.net.URI(target).resolve(LocalWebVpnSupport.fromWebVpnUrl(location)).toString()
          }
          val user = requireNotNull(identity)
          val payload =
              CampusCardCodec.encode(
                  buildJsonObject {
                    put("openid", user.openid)
                    put("usertype", user.usertype)
                    put("orgid", "2")
                    put("ua", "mozilla/5.0 bucc/2.0.0")
                  }
              )
          val response =
              card.get(
                  localGatewayServiceUrl("https://m.icw.buaa.edu.cn/card/home/openHomePageApp")
              ) {
                url.parameters.append("datajson", payload["datajson"]!!.jsonPrimitive.content)
                headers.append("X-Requested-With", "XMLHttpRequest")
                headers.append("session-type", "uniapp")
                headers.append("isWechatApp", "true")
                headers.append("orgid", "2")
              }
          val result = CampusCardCodec.decode(response.bodyAsText())
          println(
              "FINANCE_CARD_MENU status=${response.status.value} success=${result["success"]?.jsonPrimitive?.booleanOrNull} menuCount=${(result["data"]?.jsonObject?.get("funcList") as? JsonArray)?.size}"
          )
        } finally {
          card.close()
        }
        return@runBlocking
      }
      stage = "judge"
      val judgeResult = JudgeApi().getAssignments(true, account)
      val judge = judgeResult.getOrNull()
      println(
          "JUDGE_RESULT success=${judgeResult.isSuccess} courses=${judge?.assignments?.map { it.courseId }?.distinct()?.size} rows=${judge?.assignments?.size} failedCourses=${judge?.failedCourseIds?.size} errorType=${judgeResult.exceptionOrNull()?.let { it::class.simpleName }}"
      )
      if (judgeResult.isFailure) {
        for (scheme in listOf("http", "https")) {
          val html =
              LocalUpstreamClientProvider.shared()
                  .get(localUpstreamUrl("$scheme://judge.buaa.edu.cn/main.jsp"))
                  .bodyAsText()
          val markers =
              listOf("permission", "login", "error", "redirect", "iframe", "课程", "权限", "登录")
                  .filter { html.contains(it, true) }
          println(
              "JUDGE_SHAPE scheme=$scheme chars=${html.length} courseIds=${Regex("(?i)courseID").findAll(html).count()} onclick=${Regex("(?i)onclick").findAll(html).count()} markers=${markers.joinToString(",")}"
          )
          val courseHtml =
              LocalUpstreamClientProvider.shared()
                  .get(localUpstreamUrl("$scheme://judge.buaa.edu.cn/courselist.jsp?courseID=0"))
                  .bodyAsText()
          println(
              "JUDGE_COURSES scheme=$scheme chars=${courseHtml.length} courseIds=${Regex("(?i)courseID").findAll(courseHtml).count()} onclick=${Regex("(?i)onclick").findAll(courseHtml).count()}"
          )
        }
      }
      if (judgeResult.isFailure) {
        val html =
            LocalUpstreamClientProvider.shared()
                .get(localUpstreamUrl("http://judge.buaa.edu.cn/indexcs/simple.jsp"))
                .bodyAsText()
        val paths =
            Regex("""(?:href|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1].substringBefore('?').substringBefore('#') }
                .filter { it.matches(Regex("[a-zA-Z0-9_./:-]{1,120}")) }
                .map { it.replace(Regex("[0-9]{4,}"), "#") }
                .distinct()
                .take(40)
                .toList()
        val handlers =
            Regex("""onclick\s*=\s*["']\s*([a-zA-Z0-9_.]+)""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        println(
            "JUDGE_DISCOVERY chars=${html.length} courseIds=${Regex("(?i)courseID").findAll(html).count()} paths=$paths handlers=$handlers"
        )
        val hrefs =
            Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).map {
              it.groupValues[1].replace("&amp;", "&")
            }
        for (href in hrefs) {
          val original =
              LocalWebVpnSupport.fromWebVpnUrl(
                  if (href.startsWith('/')) "https://d.buaa.edu.cn$href" else href
              )
          val link = runCatching { Url(original) }.getOrNull() ?: continue
          if (link.host == "sso.buaa.edu.cn") {
            val service = link.parameters["service"]?.let { runCatching { Url(it) }.getOrNull() }
            println(
                "JUDGE_SSO serviceScheme=${service?.protocol?.name} serviceHost=${service?.host} servicePath=${service?.encodedPath} serviceKeys=${service?.parameters?.names()?.sorted()}"
            )
          }
        }
      }
      println(
          "JUDGE_DATES parsedStarts=${judge?.assignments?.count { it.startTime != null }} parsedDue=${judge?.assignments?.count { it.dueTime != null }}"
      )
      val actualJudgeIds = judge?.assignments?.map { it.courseId to it.assignmentId }?.toSet()
      println(
          "JUDGE_COMPARE siteRows=${judgeSiteIds.size} adapterRows=${actualJudgeIds?.size} sameIds=${actualJudgeIds == judgeSiteIds}"
      )
      stage = "calendar"
      val term = HomeworkSemesterProvider().load()
      println("CALENDAR start=${term.start} end=${term.end}")
      println(
          "JUDGE_CURRENT rows=${judge?.assignments?.count { term.contains(parseHomeworkDate(it.startTime), parseHomeworkDate(it.dueTime)) }}"
      )
      if ("--judge-only" in args) return@runBlocking
      stage = "accoding_authentication"
      val identity =
          accountClient.get(localGatewayServiceUrl("https://accoding.buaa.edu.cn/api/users/me"))
      val hasIdentity =
          runCatching {
                Json.parseToJsonElement(identity.bodyAsText())
                    .jsonObject["id"]
                    ?.jsonPrimitive
                    ?.contentOrNull != null
              }
              .getOrDefault(false)
      if (!hasIdentity && restoreLogin) {
        val saved =
            requireNotNull(
                HomeworkCredentialStore(PlatformHomeworkCredentialStorage)
                    .load(account, HomeworkSource.ACCODING)
            )
        adapter.login(saved.username, saved.password)
      }
      stage = "accoding_pages"
      val visited = mutableSetOf<Int>()
      val pending = mutableSetOf(1)
      val rows = mutableListOf<HomeworkItem>()
      while (pending.isNotEmpty()) {
        val page = pending.min().also { pending.remove(it) }
        if (!visited.add(page)) continue
        val response =
            accountClient.get(
                localGatewayServiceUrl("https://accoding.buaa.edu.cn/contest/index?page=$page")
            )
        check(response.status.value == 200)
        val html = response.bodyAsText()
        val parsed = parseAccodingPage(html)
        println(
            "ACCODING_PAGE page=$page rows=${Regex("(?i)<tr\\b").findAll(html).count()} parsed=${parsed.items.size} nextPages=${parsed.pages.size}"
        )
        rows += parsed.items
        pending += parsed.pages - visited
      }
      val expected =
          rows
              .distinctBy { it.id }
              .filter { term.contains(it.startAt, it.dueAt) }
              .map { it.id }
              .toSet()
      stage = "accoding_adapter"
      val actual = adapter.load().map { it.id }.toSet()
      println(
          "FINAL_COMPARE pages=${visited.size} siteCurrent=${expected.size} adapterCurrent=${actual.size} sameIds=${expected == actual}"
      )
    } catch (error: Exception) {
      val api = error as? ApiCallException
      println(
          "PROBE_FAILURE stage=$stage type=${error::class.simpleName} code=${api?.code} status=${api?.status?.value}"
      )
    } finally {
      adapter.close()
      LocalUpstreamClientProvider.reset()
      LocalUpstreamClientProvider.clientFactory = factory
      LocalUpstreamClientProvider.isolatedClientFactory = isolatedFactory
    }
  }
}
