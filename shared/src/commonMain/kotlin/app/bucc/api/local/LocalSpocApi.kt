package app.bucc.api.local

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.auth.ApiCallException
import app.bucc.api.auth.toUserFacingApiException
import app.bucc.api.feature.SpocApiBackend
import app.bucc.api.plane.PaperPlaneCourseSession
import app.bucc.api.plane.PaperPlaneCurrentTermSchedule
import app.bucc.api.plane.PaperPlaneEvaluationOptions
import app.bucc.api.plane.PaperPlaneFeedRequest
import app.bucc.api.plane.PaperPlaneFeedResponse
import app.bucc.api.plane.PaperPlaneFeeling
import app.bucc.api.plane.PaperPlaneFeelingQuery
import app.bucc.api.plane.PaperPlaneFeelingRecord
import app.bucc.api.plane.PaperPlaneFeelingRequest
import app.bucc.api.plane.PaperPlaneInstructor
import app.bucc.api.plane.PaperPlaneIntegralInfoRequest
import app.bucc.api.plane.PaperPlaneKeyword
import app.bucc.api.plane.PaperPlaneMutationRequest
import app.bucc.api.plane.PaperPlanePointInfo
import app.bucc.api.plane.PaperPlanePointTask
import app.bucc.api.plane.PaperPlanePointTasksResponse
import app.bucc.api.plane.PaperPlaneQueryRequest
import app.bucc.api.plane.PaperPlaneReplyPermission
import app.bucc.api.plane.PaperPlaneSendConfig
import app.bucc.api.plane.PaperPlaneTaskRequest
import app.bucc.api.plane.PaperPlaneTerm
import app.bucc.api.plane.PaperPlaneThrowMessage
import app.bucc.api.plane.PaperPlaneThrowRequest
import app.bucc.api.plane.PaperPlaneWeek
import app.bucc.diagnostics.*
import app.bucc.model.dto.SpocAssignmentDetailDto
import app.bucc.model.dto.SpocAssignmentSummaryDto
import app.bucc.model.dto.SpocAssignmentsResponse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

internal class LocalSpocApiBackend : SpocApiBackend {
  private val clientMutex = Mutex()
  private val clientCache = mutableMapOf<String, LocalSpocClient>()

  internal fun clearCache() {
    clientCache.clear()
  }

  override suspend fun getAssignments(): Result<SpocAssignmentsResponse> =
      runLocalSpocCall("SPOC 作业列表加载失败，请稍后重试") { getAssignmentsResponse() }

  override suspend fun getAssignmentDetail(assignmentId: String): Result<SpocAssignmentDetailDto> =
      runLocalSpocCall("SPOC 作业详情加载失败，请稍后重试") { getAssignmentDetailResponse(assignmentId) }

  private suspend fun LocalSpocClient.getAssignmentsResponse(): SpocAssignmentsResponse {
    val term = getCurrentTerm()
    val termCode =
        term.mrxq?.takeIf { it.isNotBlank() }
            ?: throw ApiCallException("无法获取 SPOC 当前学期代码", code = "spoc_error")
    val courseMap =
        try {
          getCourses(termCode).associateBy { it.kcid }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (_: Exception) {
          emptyMap()
        }
    val rawAssignments = getAllAssignments(termCode)
    val assignments =
        rawAssignments
            .map { assignment ->
              val course = assignment.sskcid?.let { courseMap[it] }
              val status =
                  LocalSpocParsers.mapSubmissionStatus(
                      rawStatus = assignment.tjzt,
                      hasContent = !assignment.tjzt.isNullOrBlank(),
                  )
              SpocAssignmentSummaryDto(
                  assignmentId = assignment.zyid,
                  courseId = assignment.sskcid.orEmpty(),
                  courseName = assignment.kcmc ?: course?.kcmc.orEmpty(),
                  teacherName = course?.skjs,
                  title = assignment.zymc,
                  startTime = LocalSpocParsers.normalizeDateTime(assignment.zykssj),
                  dueTime = LocalSpocParsers.normalizeDateTime(assignment.zyjzsj),
                  score = LocalSpocParsers.normalizeScore(assignment.mf),
                  submissionStatus = status,
                  submissionStatusText =
                      LocalSpocParsers.submissionStatusText(status, assignment.tjzt),
              )
            }
            .sortedWith(
                compareBy<SpocAssignmentSummaryDto> { it.dueTime ?: "9999-99-99 99:99:99" }
                    .thenBy { it.courseName }
                    .thenBy { it.title }
            )

    summaries = assignments
    return SpocAssignmentsResponse(
        termCode = termCode,
        termName = term.dqxq,
        assignments = assignments,
    )
  }

  private suspend fun LocalSpocClient.getAssignmentDetailResponse(
      assignmentId: String
  ): SpocAssignmentDetailDto {
    val summary =
        (summaries ?: getAssignmentsResponse().assignments).firstOrNull {
          it.assignmentId == assignmentId
        }
            ?: throw ApiCallException(
                "未找到指定的 SPOC 作业",
                status = HttpStatusCode.NotFound,
                code = "spoc_error",
            )
    val detail = getAssignmentDetail(assignmentId)
    val submission = getSubmission(assignmentId)
    val hasSubmission = submission != null
    val status = LocalSpocParsers.mapSubmissionStatus(submission?.tjzt, hasSubmission)

    return summary
        .copy(
            score = LocalSpocParsers.normalizeScore(detail.zyfs) ?: summary.score,
            startTime = LocalSpocParsers.normalizeDateTime(detail.zykssj) ?: summary.startTime,
            dueTime = LocalSpocParsers.normalizeDateTime(detail.zyjzsj) ?: summary.dueTime,
            submissionStatus = status,
            submissionStatusText = LocalSpocParsers.submissionStatusText(status, submission?.tjzt),
        )
        .toLocalSpocDetail(
            contentPlainText = LocalSpocParsers.toPlainText(detail.zynr),
            contentHtml = detail.zynr,
            submittedAt = LocalSpocParsers.normalizeDateTime(submission?.tjsj),
        )
  }

  private suspend fun <T> runLocalSpocCall(
      defaultMessage: String,
      block: suspend LocalSpocClient.() -> T,
  ): Result<T> {
    val session =
        LocalAuthSessionStore.get() ?: return Result.failure(localUnauthenticatedApiException())
    val username = session.user.schoolid.ifBlank { session.username }
    if (username.isBlank()) return Result.failure(localUnauthenticatedApiException())

    return try {
      val client = currentClient(username)
      Result.success(client.block())
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: LocalSpocAuthenticationException) {
      Result.failure(resolveLocalBusinessAuthenticationFailure("spoc_error"))
    } catch (e: Exception) {
      Result.failure(e.toUserFacingApiException(defaultMessage))
    }
  }

  private suspend fun currentClient(username: String): LocalSpocClient =
      clientMutex.withLock { clientCache.getOrPut(username) { LocalSpocClient() } }
}

internal class LocalSpocClient {
  private val json = Json { ignoreUnknownKeys = true }
  private val requestJson = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  var summaries: List<SpocAssignmentSummaryDto>? = null
  private val loginMutex = Mutex()

  private var token: String? = null
  private var roleCode: String? = null
  private val planeStudentMutex = Mutex()
  private var planeStudent: Pair<String, String>? = null
  private var authenticatedScope: LoginScope? = null

  private data class LoginScope(
      val mode: ConnectionMode?,
      val account: String?,
      val authenticatedAt: String?,
  )

  suspend fun getCurrentTerm(): LocalSpocCurrentTermContent {
    return withAuthenticatedCall {
      postEnvelope(
          operation = "get_current_term",
          url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne"),
          body = LocalSpocQueryOneRequest(CURRENT_TERM_PARAM),
      )
    }
  }

  suspend fun getCourses(termCode: String): List<LocalSpocCourseRaw> {
    return withAuthenticatedCall {
      getEnvelope<List<LocalSpocCourseRaw>>(
          operation = "get_courses",
          url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/jxkj/queryKclb"),
      ) {
        parameter("kcmc", "")
        parameter("xnxq", termCode)
      }
    }
  }

  suspend fun getAssignmentsPage(
      termCode: String?,
      pageNum: Int,
      pageSize: Int = DEFAULT_PAGE_SIZE,
  ): LocalSpocAssignmentsPageContent {
    return withAuthenticatedCall {
      val plainText =
          requestJson.encodeToString(
              LocalSpocAssignmentsPageRequest(
                  pageSize = pageSize,
                  pageNum = pageNum,
                  sqlid = ASSIGNMENTS_PAGE_SQL_ID,
                  xnxq = termCode,
              )
          )
      postEnvelope(
          operation = "get_assignments_page",
          url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryListByPage"),
          body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(plainText)),
      )
    }
  }

  suspend fun getAllAssignments(termCode: String?): List<LocalSpocPagedAssignmentRaw> {
    val assignments = mutableListOf<LocalSpocPagedAssignmentRaw>()
    var pageNum = 1
    while (true) {
      val page = getAssignmentsPage(termCode = termCode, pageNum = pageNum)
      assignments += page.list
      if (!page.hasNextPage || pageNum >= page.pages || page.list.isEmpty()) {
        break
      }
      pageNum++
    }
    return assignments
  }

  suspend fun getAssignmentDetail(assignmentId: String): LocalSpocAssignmentDetailRaw {
    return withAuthenticatedCall {
      getEnvelope(
          operation = "get_assignment_detail",
          url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/kczy/queryKczyInfoByid"),
      ) {
        parameter("id", assignmentId)
      }
    }
  }

  suspend fun getSubmission(assignmentId: String): LocalSpocSubmissionRaw? {
    return withAuthenticatedCall {
      getEnvelope(
          operation = "get_submission",
          url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/kczy/queryXsSubmitKczyInfo"),
      ) {
        parameter("kczyid", assignmentId)
      }
    }
  }

  internal suspend fun getPaperPlaneTasks(studentId: String): PaperPlanePointTasksResponse =
      withAuthenticatedCall {
        val payload = requestJson.encodeToString(PaperPlaneTaskRequest(studentId))
        val content =
            postEnvelope<JsonElement, LocalSpocEncryptedParamRequest>(
                operation = "get_paper_plane_tasks",
                url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryList"),
                body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(payload)),
            )
        val tasks =
            when (content) {
              is JsonArray ->
                  runCatching {
                        content.map { entry ->
                          val fields =
                              entry as? JsonObject ?: throw LocalSpocException("SPOC 纸飞机积分记录格式无效")
                          PaperPlanePointTask(
                              id = fields["id"].asSPOCFieldText(),
                              bz = fields["bz"].asSPOCFieldText(),
                              gzmc = fields["gzmc"].asSPOCFieldText(),
                              jf = fields["jf"].asSPOCFieldText(),
                              num = fields["num"].asSPOCFieldText(),
                              sx = fields["sx"].asSPOCFieldText(),
                          )
                        }
                      }
                      .fold(
                          onSuccess = { PaperPlanePointTasksResponse(list = it) },
                          onFailure = { throw LocalSpocException("SPOC 纸飞机积分列表解析失败", it) },
                      )
              is JsonObject ->
                  runCatching { json.decodeFromJsonElement<PaperPlanePointTasksResponse>(content) }
                      .getOrElse { throw LocalSpocException("SPOC 纸飞机积分数据解析失败", it) }
              else -> throw LocalSpocException("SPOC 纸飞机积分数据格式无效")
            }
        val infoPayload = requestJson.encodeToString(PaperPlaneIntegralInfoRequest(xh = studentId))
        try {
          val info =
              postEnvelope<PaperPlanePointInfo, LocalSpocEncryptedParamRequest>(
                  operation = "get_paper_plane_integral_info",
                  url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne"),
                  body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(infoPayload)),
              )
          tasks.copy(info = info)
        } catch (error: kotlinx.coroutines.CancellationException) {
          throw error
        } catch (error: LocalSpocAuthenticationException) {
          throw error
        } catch (error: Exception) {
          tasks.copy(integralError = error.message ?: "累计积分查询失败")
        }
      }

  private fun JsonElement?.asSPOCFieldText(): String? =
      when (this) {
        null,
        JsonNull -> null
        is JsonPrimitive -> contentOrNull
        else -> null
      }

  internal suspend fun getPaperPlaneFeed(request: PaperPlaneFeedRequest): PaperPlaneFeedResponse =
      withAuthenticatedCall {
        val payload = requestJson.encodeToString(request)
        val response =
            postEnvelope<PaperPlaneFeedResponse, LocalSpocEncryptedParamRequest>(
                operation = "get_paper_plane_feed",
                url =
                    localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryListByPage"),
                body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(payload)),
            )
        if (response.list == null) throw LocalSpocException("SPOC 纸飞机列表响应缺少 list 字段")
        response
      }

  internal suspend fun hasPaperPlaneViewed(planeId: String, studentId: String): Boolean =
      withAuthenticatedCall {
        val officialStudentId = resolvePlaneStudentId(studentId)
        val payload =
            requestJson.encodeToString(
                PaperPlaneMutationRequest(
                    sqlid = "1735118896932fd7e6c7cf6ca8bb78adf3341b1634e5a4322",
                    zfjid = planeId,
                    jllx = "1",
                    yhdm = officialStudentId,
                )
            )
        val permission =
            postEnvelope<JsonObject, LocalSpocEncryptedParamRequest>(
                operation = "check_paper_plane_view",
                url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne"),
                body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(payload)),
            )
        when ((permission["sfyjl"] as? JsonPrimitive)?.contentOrNull) {
          "1" -> true
          "0" -> false
          else -> throw LocalSpocException("纸飞机查看状态未知，未提交查看记录")
        }
      }

  internal suspend fun markPaperPlaneViewed(planeId: String, studentId: String): JsonElement? =
      withAuthenticatedMutation {
        val officialStudentId = resolvePlaneStudentId(studentId)
        val payload =
            requestJson.encodeToString(
                PaperPlaneMutationRequest(
                    sqlid = "1735117159271ee372cf4898785aa6f4a519394f76b119c4",
                    zfjid = planeId,
                    jllx = "1",
                    yhdm = officialStudentId,
                )
            )
        postEnvelope<JsonElement?, LocalSpocEncryptedParamRequest>(
            operation = "mark_paper_plane_viewed",
            url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/add"),
            body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(payload)),
        )
      }

  private suspend fun resolvePlaneStudentId(studentId: String): String =
      planeStudentMutex.withLock {
        val currentToken = token ?: throw LocalSpocAuthenticationException("SPOC token 未初始化")
        planeStudent
            ?.takeIf { it.first == currentToken && it.second == studentId }
            ?.let {
              return@withLock it.second
            }
        val profile =
            postEnvelope<JsonObject, JsonObject>(
                operation = "get_paper_plane_student",
                url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/grzx/queryByInfo"),
                body = JsonObject(emptyMap()),
            )
        val officialId = profile["yhdm"].asSPOCFieldText()?.takeIf(String::isNotBlank)
        if (officialId == null || officialId != studentId) {
          throw LocalSpocException("纸飞机学生身份无法确认，未提交查看记录")
        }
        val payload = """{"sqlid":"17126473942303c5f6d76786468fdeb4af2c1192a3bd"}"""
        val type =
            postEnvelope<JsonObject, LocalSpocEncryptedParamRequest>(
                operation = "get_paper_plane_student_type",
                url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne"),
                body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(payload)),
            )
        if (type["yhlx"].asSPOCFieldText() != "2") {
          throw LocalSpocException("纸飞机学生权限未知，未提交查看记录")
        }
        // The official student page stores jsdm=1; it is distinct from HTTP RoleCode.
        planeStudent = currentToken to officialId
        officialId
      }

  internal suspend fun cheerPaperPlane(
      planeId: String,
      studentId: String,
      planeAuthorId: String,
  ): JsonElement? = withAuthenticatedMutation {
    val payload =
        requestJson.encodeToString(
            PaperPlaneMutationRequest(
                sqlid = "173502131712886a4619b6c6b1d4f3583957a8aeaac6bc33",
                xh = studentId,
                sfpjzj = if (planeAuthorId == studentId) "1" else "0",
                pjid = planeId,
                dzlx = roleCode ?: throw LocalSpocAuthenticationException("SPOC roleCode 未初始化"),
            )
        )
    postEnvelope<JsonElement?, LocalSpocEncryptedParamRequest>(
        operation = "cheer_paper_plane",
        url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/add"),
        body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(payload)),
    )
  }

  internal suspend fun getPaperPlaneReplyPermission(planeId: String): PaperPlaneReplyPermission =
      withAuthenticatedCall {
        val payload =
            requestJson.encodeToString(
                PaperPlaneMutationRequest(
                    sqlid = "1740647562144146548213d653cbe7796671fe5f65ae296",
                    zfjid = planeId,
                )
            )
        postEnvelope(
            operation = "check_paper_plane_reply",
            url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne"),
            body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(payload)),
        )
      }

  internal suspend fun replyPaperPlane(
      planeId: String,
      studentId: String,
      planeAuthorId: String,
      replyId: String,
      message: String,
  ): JsonElement? = withAuthenticatedMutation {
    val payload =
        requestJson.encodeToString(
            PaperPlaneMutationRequest(
                sqlid = "17350257502147d5a25c84e2ab83e7d79aaa68a31fb1a27d",
                nr = message,
                zpjxh = planeAuthorId,
                zpjid = planeId,
                xsxh = studentId,
                sfnm = "0",
                id = replyId,
            )
        )
    postEnvelope<JsonElement?, LocalSpocEncryptedParamRequest>(
        operation = "reply_paper_plane",
        url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/add"),
        body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(payload)),
    )
  }

  internal suspend fun getPaperPlaneCurrentTermSchedule(): PaperPlaneCurrentTermSchedule =
      withAuthenticatedCall {
        val terms =
            queryPlaneList<PaperPlaneTerm>(
                PaperPlaneQueryRequest(
                    sqlid = "17139418053476da3da1fa8ba8e717e1aaa5d5682d5be31",
                )
            )
        val term = terms.firstOrNull { it.sfdqxq == "1" } ?: throw LocalSpocException("学校未返回当前学期")
        val termCode = term.xnxq?.takeIf(String::isNotBlank) ?: throw LocalSpocException("当前学期代码为空")
        val weeks =
            queryPlaneList<PaperPlaneWeek>(
                PaperPlaneQueryRequest(
                    sqlid = "17272305190027285c6d5e1a68532c347c6efc5755da1",
                    xnxq = termCode,
                )
            )
        val sessions =
            weeks.chunked(4).flatMap { batch ->
              coroutineScope {
                batch
                    .map { week ->
                      async {
                        val start = week.zksrq?.takeIf(String::isNotBlank)
                        val end = week.zjsrq?.takeIf(String::isNotBlank)
                        if (start == null || end == null) emptyList<PaperPlaneCourseSession>()
                        else
                            queryPlaneList(
                                PaperPlaneQueryRequest(
                                    sqlid = "1738910626208d6e2f6f196f7221bf754c2891cafe9f84ff",
                                    zksrq = start,
                                    zjsrq = end,
                                    xnxq = termCode,
                                )
                            )
                      }
                    }
                    .awaitAll()
                    .flatten()
              }
            }
        // Viewing the class feed only needs the term and course sessions. The send-rule
        // query is used only for the separate write flow and can return an array rather
        // than the expected object; keep that failure from blocking read-only browsing.
        val config =
            try {
              postEnvelope<PaperPlaneSendConfig, LocalSpocEncryptedParamRequest>(
                  operation = "get_paper_plane_send_config",
                  url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryOne"),
                  body =
                      LocalSpocEncryptedParamRequest(
                          LocalSpocCrypto.encryptParam(
                              requestJson.encodeToString(
                                  PaperPlaneQueryRequest(
                                      sqlid = "173491496021491356781b468c27af6db1cdddc6ab39c86d",
                                  )
                              )
                          )
                      ),
              )
            } catch (error: kotlinx.coroutines.CancellationException) {
              throw error
            } catch (error: LocalSpocAuthenticationException) {
              throw error
            } catch (_: Exception) {
              null
            }
        PaperPlaneCurrentTermSchedule(
            currentTermCode = termCode,
            sendConfig = config,
            sessions = sessions.distinctBy { "${it.hdktid}:${it.skkssj}" },
        )
      }

  internal suspend fun getPaperPlaneInstructors(
      course: PaperPlaneCourseSession
  ): List<PaperPlaneInstructor> = withAuthenticatedCall {
    queryPlaneList(
        PaperPlaneQueryRequest(
            sqlid = "17351717617689bb6d2856fa68b951fd449743ad125da8b1",
            hdktid = course.hdktid,
        )
    )
  }

  internal suspend fun getPaperPlaneEvaluationOptions(
      course: PaperPlaneCourseSession,
      studentId: String,
  ): PaperPlaneEvaluationOptions = withAuthenticatedCall {
    val feelings =
        queryPlaneList<PaperPlaneFeeling>(
            PaperPlaneQueryRequest(
                sqlid = "173406145116435b4757f4d39d3338cda1daf71e59732b8e",
            )
        )
    val keywords =
        queryPlaneList<PaperPlaneKeyword>(
            PaperPlaneQueryRequest(
                sqlid = "1734923858382e1226bbb2655f545e9d8cd4459c5fdef8e",
                kcid = course.kcid,
            )
        )
    val history =
        postEnvelope<List<PaperPlaneFeelingRecord>, LocalSpocEncryptedParamRequest>(
            operation = "get_paper_plane_feeling_history",
            url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryList"),
            body =
                encryptedPlaneBody(
                    PaperPlaneFeelingQuery(
                        "1735277243511d86648d54c365a91a523748bf9e4eebd55e",
                        course.hdktid ?: throw LocalSpocException("课堂编号缺失"),
                        studentId,
                    )
                ),
        )
    val matching = history.filter { it.hdktid == course.hdktid }
    val recorded =
        when {
          history.any { it.hdktid.isNullOrBlank() || it.gstb.isNullOrBlank() } -> null
          matching.any { record -> feelings.none { it.icon == record.gstb } } -> null
          else -> matching.isNotEmpty()
        }
    PaperPlaneEvaluationOptions(recorded, feelings, keywords)
  }

  private inline fun <reified T> encryptedPlaneBody(value: T) =
      LocalSpocEncryptedParamRequest(
          LocalSpocCrypto.encryptParam(requestJson.encodeToString(value))
      )

  @OptIn(ExperimentalUuidApi::class)
  internal suspend fun recordPaperPlaneFeeling(
      course: PaperPlaneCourseSession,
      feeling: PaperPlaneFeeling,
      studentId: String,
  ) {
    fun required(value: String?): String =
        value?.takeIf(String::isNotBlank) ?: throw LocalSpocException("课堂感受所需信息缺失，未提交评价")
    val payload =
        PaperPlaneFeelingRequest(
            id = Uuid.random().toString().replace("-", "").take(20),
            gstb = required(feeling.icon),
            gsdf =
                feeling.fz?.takeIf { it is JsonPrimitive && it.contentOrNull != null }
                    ?: throw LocalSpocException("课堂感受分值缺失，未提交评价"),
            jszgh = studentId,
            tbid = required(feeling.id),
            xsxh = studentId,
            hdktid = required(course.hdktid),
            xn = required(course.xn),
            xq = required(course.xq),
            kkyx = required(course.kkyx),
            kcdm = required(course.kcdm),
            kczc = required(course.kczc),
            bjm = required(course.bjm),
            kcjc = required(course.kcjc),
            skrq = required(course.skrq),
        )
    ensureLogin()
    // Writes are not wrapped in the read-call authentication retry.
    postEnvelope<JsonElement?, LocalSpocEncryptedParamRequest>(
        operation = "record_paper_plane_feeling",
        url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/add"),
        body = encryptedPlaneBody(payload),
    )
  }

  @OptIn(ExperimentalUuidApi::class)
  internal suspend fun throwPaperPlane(
      course: PaperPlaneCourseSession,
      instructorId: String,
      studentId: String,
      keywordId: String,
  ) {
    // Do not use withAuthenticatedCall here: its auth retry replays the entire
    // mutation block, which could duplicate an already accepted add operation.
    ensureLogin()
    val kcid = course.kcid?.takeIf(String::isNotBlank) ?: throw LocalSpocException("课程编号缺失，未发送纸飞机")
    val kcmc = course.kcmc?.takeIf(String::isNotBlank) ?: throw LocalSpocException("课程名称缺失，未发送纸飞机")
    val hdktid =
        course.hdktid?.takeIf(String::isNotBlank) ?: throw LocalSpocException("课堂编号缺失，未发送纸飞机")
    val payload =
        PaperPlaneThrowRequest(
            list =
                listOf(
                    PaperPlaneThrowMessage(
                        id = Uuid.random().toString().replace("-", "").take(20),
                        plgjc =
                            keywordId.takeIf(String::isNotBlank)
                                ?: throw LocalSpocException("课程关键词缺失，未投掷"),
                        xsxh = studentId,
                        kcid = kcid,
                        kcmc = kcmc,
                        hdktid = hdktid,
                        jsr = instructorId,
                    )
                ),
        )
    val encrypted =
        LocalSpocEncryptedParamRequest(
            LocalSpocCrypto.encryptParam(requestJson.encodeToString(payload))
        )
    postEnvelope<JsonElement?, LocalSpocEncryptedParamRequest>(
        operation = "add_paper_plane_message",
        url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/add"),
        body = encrypted,
    )
    postEnvelope<JsonElement?, LocalSpocEncryptedParamRequest>(
        operation = "split_paper_plane",
        url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/ansj/splitPaperAirplanes"),
        body = encrypted,
    )
  }

  internal suspend fun getPaperPlaneClassFeed(
      course: PaperPlaneCourseSession,
      studentId: String,
  ): PaperPlaneFeedResponse = withAuthenticatedCall {
    getPaperPlaneFeed(
        PaperPlaneFeedRequest(
            pageSize = 20,
            pageNum = 1,
            kcid = course.kcid,
            pllx = "0",
            hdktid = course.hdktid,
            dqxh = studentId,
            sfdt = "0",
        )
    )
  }

  private suspend inline fun <reified T> queryPlaneList(request: PaperPlaneQueryRequest): List<T> {
    val payload = requestJson.encodeToString(request)
    return postEnvelope(
        operation = "query_paper_plane_list",
        url = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/inco/ht/queryList"),
        body = LocalSpocEncryptedParamRequest(LocalSpocCrypto.encryptParam(payload)),
    )
  }

  private suspend fun ensureLogin(forceRefresh: Boolean = false) {
    var refresh = forceRefresh
    while (true) {
      val scope = currentLoginScope()
      if (!refresh && hasCredentialsFor(scope)) return

      val loggedInForCurrentScope =
          loginMutex.withLock {
            val activeScope = currentLoginScope()
            if (!refresh && hasCredentialsFor(activeScope)) return@withLock true

            if (authenticatedScope != activeScope) clearLoginState()
            val tokens = fetchLoginTokens()
            val casLogin = performCasLogin(tokens.token)
            val resolvedRoleCode =
                LocalSpocParsers.resolveRoleCode(casLogin)
                    ?: throw LocalSpocAuthenticationException("SPOC 登录成功但未获取到角色信息")

            if (currentLoginScope() != activeScope) {
              clearLoginState()
              return@withLock false
            }
            token = tokens.token
            roleCode = resolvedRoleCode
            authenticatedScope = activeScope
            true
          }

      if (loggedInForCurrentScope && authenticatedScope == currentLoginScope()) return
      refresh = false
    }
  }

  private fun hasCredentialsFor(scope: LoginScope): Boolean =
      !token.isNullOrBlank() && !roleCode.isNullOrBlank() && authenticatedScope == scope

  private fun currentLoginScope(): LoginScope {
    val session = LocalAuthSessionStore.get()
    return LoginScope(
        mode = ConnectionRuntime.currentMode(),
        account = session?.user?.schoolid?.takeIf(String::isNotBlank) ?: session?.username,
        authenticatedAt = session?.authenticatedAt,
    )
  }

  private suspend fun clearLoginState() {
    token = null
    roleCode = null
    authenticatedScope = null
    planeStudentMutex.withLock { planeStudent = null }
  }

  private suspend fun fetchLoginTokens(): LocalSpocLoginTokens {
    val noRedirectClient = LocalUpstreamClientProvider.newNoRedirectClient()
    return try {
      var currentUrl = localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/cas")
      repeat(8) {
        val response = noRedirectClient.get(currentUrl)
        LocalSpocParsers.extractLoginTokens(response.call.request.url.toString())?.let {
          return it
        }
        val location =
            response.headers[HttpHeaders.Location]
                ?: throw LocalSpocAuthenticationException("SPOC 登录跳转缺少 Location")
        LocalSpocParsers.extractLoginTokens(location)?.let {
          return it
        }
        currentUrl = resolveRedirectUrl(response.call.request.url, location)
      }
      throw LocalSpocAuthenticationException("未能在 SPOC 登录跳转链中获取 token")
    } finally {
      noRedirectClient.close()
    }
  }

  private suspend fun performCasLogin(loginToken: String): LocalSpocCasLoginContent {
    return LocalUpstreamClientProvider.shared()
        .post(localUpstreamUrl("https://spoc.buaa.edu.cn/spocnewht/sys/casLogin")) {
          contentType(ContentType.Application.Json)
          header("X-Requested-With", "XMLHttpRequest")
          header("Token", "Inco-$loginToken")
          setBody(LocalSpocCasLoginRequest(loginToken))
        }
        .let { response ->
          val bodyText = response.bodyAsText()
          localSpocSessionFailure(response, bodyText)?.let {
            throw LocalSpocAuthenticationException(it)
          }
          val envelope = decodeEnvelope<LocalSpocCasLoginContent>(bodyText)
          unwrapEnvelope(envelope)
        }
  }

  private suspend inline fun <T> withAuthenticatedCall(crossinline block: suspend () -> T): T {
    ensureLogin()
    return try {
      block()
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: LocalSpocAuthenticationException) {
      ensureLogin(forceRefresh = true)
      block()
    }
  }

  private suspend inline fun <T> withAuthenticatedMutation(crossinline block: suspend () -> T): T {
    // Authenticate before sending, but never replay a write after an auth error:
    // the upstream may have accepted the mutation before its response was lost.
    ensureLogin()
    return block()
  }

  private suspend inline fun <reified T> getEnvelope(
      operation: String,
      url: String,
      crossinline builder: HttpRequestBuilder.() -> Unit = {},
  ): T {
    val currentToken = token ?: throw LocalSpocAuthenticationException("SPOC token 未初始化")
    val currentRoleCode = roleCode ?: throw LocalSpocAuthenticationException("SPOC roleCode 未初始化")
    val response =
        LocalUpstreamClientProvider.shared().get(url) {
          header("X-Requested-With", "XMLHttpRequest")
          header("Token", "Inco-$currentToken")
          header("RoleCode", currentRoleCode)
          builder()
        }
    val bodyText = response.bodyAsText()
    localSpocSessionFailure(response, bodyText)?.let { throw LocalSpocAuthenticationException(it) }
    val envelope = decodeEnvelope<T>(bodyText)
    return unwrapEnvelope(envelope)
  }

  private suspend inline fun <reified T, reified B : Any> postEnvelope(
      operation: String,
      url: String,
      body: B,
  ): T {
    val stage =
        when (operation) {
          "check_paper_plane_view" -> DiagnosticStage.VIEW_STATUS
          "mark_paper_plane_viewed" -> DiagnosticStage.VIEW_WRITE
          "get_paper_plane_student",
          "get_paper_plane_student_type" -> DiagnosticStage.IDENTITY
          "add_paper_plane_message",
          "split_paper_plane" -> DiagnosticStage.THROW
          else -> DiagnosticStage.HTTP
        }
    val execute: suspend () -> T = {
      var status: Int? = null
      recordDiagnostic(stage, DiagnosticOutcome.STARTED, url)
      try {
        val currentToken = token ?: throw LocalSpocAuthenticationException("SPOC token 未初始化")
        val currentRoleCode =
            roleCode ?: throw LocalSpocAuthenticationException("SPOC roleCode 未初始化")
        val response =
            LocalUpstreamClientProvider.shared().post(url) {
              contentType(ContentType.Application.Json)
              header("X-Requested-With", "XMLHttpRequest")
              header("Token", "Inco-$currentToken")
              header("RoleCode", currentRoleCode)
              setBody(body)
            }
        status = response.status.value
        val bodyText = response.bodyAsText()
        localSpocSessionFailure(response, bodyText)?.let {
          throw LocalSpocAuthenticationException(it)
        }
        val envelope = decodeEnvelope<T>(bodyText)
        val value = unwrapEnvelope(envelope)
        recordDiagnostic(stage, DiagnosticOutcome.SUCCESS, url, status, envelope.code.toString())
        value
      } catch (error: Throwable) {
        recordDiagnostic(
            stage,
            if (stage == DiagnosticStage.VIEW_WRITE || stage == DiagnosticStage.THROW)
                DiagnosticOutcome.UNCERTAIN
            else DiagnosticOutcome.FAILURE,
            url,
            status,
            error = error,
        )
        throw error
      }
    }
    val diagnosticOperation =
        when {
          operation.contains("student") -> DiagnosticOperation.SPOC_IDENTITY
          operation.contains("view") -> DiagnosticOperation.VIEW
          operation.contains("feed") -> DiagnosticOperation.FEED
          operation.contains("message") || operation.contains("split") -> DiagnosticOperation.THROW
          else -> DiagnosticOperation.TASKS
        }
    return if (operation.contains("paper_plane"))
        withLocalDiagnostics(DiagnosticModule.PAPER_PLANE, diagnosticOperation) { execute() }
    else execute()
  }

  private inline fun <reified T> decodeEnvelope(bodyText: String): LocalSpocEnvelope<T> {
    return try {
      json.decodeFromString<LocalSpocEnvelope<T>>(bodyText)
    } catch (e: Exception) {
      val root = runCatching { json.parseToJsonElement(bodyText) as? JsonObject }.getOrNull()
      val responseMessage =
          listOf("msg", "message", "msgEn", "error").firstNotNullOfOrNull { key ->
            (root?.get(key) as? JsonPrimitive)?.contentOrNull
          }
      if (!responseMessage.isNullOrBlank() && looksLikeAuthenticationFailure(responseMessage)) {
        throw LocalSpocAuthenticationException(responseMessage)
      }
      val code = (root?.get("code") as? JsonPrimitive)?.contentOrNull ?: "unknown"
      val contentType =
          when (root?.get("content")) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> "primitive"
            else -> "missing"
          }
      if (root != null) {
        val contentFields =
            (root["content"] as? JsonArray)
                ?.firstOrNull()
                .let { it as? JsonObject }
                ?.keys
                ?.sorted()
                ?.joinToString(",")
                ?.takeIf(String::isNotBlank)
                ?.let { "，fields=$it" }
                .orEmpty()
        throw LocalSpocException("SPOC 响应解析失败（code=$code，content=$contentType$contentFields）", e)
      }
      throw LocalSpocException("SPOC 响应解析失败", e)
    }
  }

  private inline fun <reified T> unwrapEnvelope(envelope: LocalSpocEnvelope<T>): T {
    if (envelope.code == 200 && envelope.content != null) return envelope.content
    if (envelope.code == 200 && kotlin.reflect.typeOf<T>().isMarkedNullable) {
      @Suppress("UNCHECKED_CAST")
      return null as T
    }

    val message = envelope.msg ?: envelope.msgEn ?: "SPOC 请求失败"
    if (looksLikeAuthenticationFailure(message)) {
      throw LocalSpocAuthenticationException(message)
    }
    throw ApiCallException(
        message = message,
        status = HttpStatusCode.BadGateway,
        code = "spoc_error",
    )
  }

  private fun looksLikeAuthenticationFailure(message: String): Boolean {
    return listOf(
            "登录",
            "未认证",
            "未登录",
            "未授权",
            "无权限",
            "权限不足",
            "token expired",
            "token invalid",
            "token 已过期",
            "token 无效",
        )
        .any { message.contains(it, ignoreCase = true) }
  }

  private fun resolveRedirectUrl(currentUrl: Url, location: String): String {
    if (location.startsWith("http://") || location.startsWith("https://")) {
      return localUpstreamUrl(location)
    }
    if (location.startsWith("//")) {
      return "${currentUrl.protocol.name}:$location"
    }
    val authority =
        "${currentUrl.protocol.name}://${currentUrl.host}${if (currentUrl.specifiedPort != currentUrl.protocol.defaultPort) ":${currentUrl.specifiedPort}" else ""}"
    if (location.startsWith("/")) {
      return "$authority$location"
    }
    val basePath = currentUrl.encodedPath.substringBeforeLast('/', "")
    val separator = if (basePath.endsWith("/")) "" else "/"
    return "$authority$basePath$separator$location"
  }

  companion object {
    private const val CURRENT_TERM_PARAM =
        "YHrxtTavu6raCwC0/qdgYffB9evWHBkTng/XS4W6j3f/TPo02iEPSoegscDTRNzIPRG49o3RHl4JiFCXAiBkkA=="
    private const val ASSIGNMENTS_PAGE_SQL_ID = "1713252980496efac7d5d9985e81693116d3e8a52ebf2b"
    private const val DEFAULT_PAGE_SIZE = 15
  }
}

private fun localSpocSessionFailure(response: HttpResponse, body: String): String? {
  if (response.status == HttpStatusCode.Unauthorized) {
    return "SPOC API 返回 HTTP ${response.status.value}"
  }
  val finalUrl = response.call.request.url.toString()
  if (localIsSsoUrl(finalUrl)) return "SPOC API 跳转到统一认证登录页"
  val trimmed = body.trimStart()
  if (trimmed.startsWith("<")) {
    return when {
      body.contains("input name=\"execution\"") -> "SPOC API 返回 CAS 登录页"
      body.contains("统一身份认证", ignoreCase = true) -> "SPOC API 返回统一认证登录页"
      else -> "SPOC API 返回登录 HTML 页面"
    }
  }
  return null
}
