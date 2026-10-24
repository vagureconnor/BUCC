package app.bucc.api.plane

import app.bucc.api.auth.ApiCallException
import app.bucc.api.auth.toUserFacingApiException
import app.bucc.api.local.LocalAuthSessionStore
import app.bucc.api.local.LocalSpocAuthenticationException
import app.bucc.api.local.LocalSpocClient
import app.bucc.api.local.LocalSpocException
import app.bucc.diagnostics.*
import io.ktor.http.HttpStatusCode
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class PaperPlaneTaskRequest(
    val xh: String,
    val sqlid: String = "1735016624911c96383fbe7599718fb11ff9d47622722887e",
)

@Serializable
data class PaperPlaneIntegralInfoRequest(
    val sqlid: String = "17344889783862e8c23ff995c1da1988237c1354632a9f8a",
    @SerialName("initData_props")
    val initDataProps: String = "1734488371938fd76e8e5bd29e4f87a39cf253bb2e",
    val minjf: String = "",
    val maxjf: String = "",
    val xh: String,
    val pageNum: Int = 1,
    val pageSize: Int = 10,
)

@Serializable
data class PaperPlaneFeedRequest(
    val pageSize: Int = 5,
    val pageNum: Int = 1,
    val sqlid: String = "17446090892651c8782524f35177e4f76937cee4694d36",
    val kcid: String? = null,
    val pllx: String? = null,
    val hdktid: String? = null,
    val dqxh: String? = null,
    val sfdt: String? = null,
)

@Serializable
data class PaperPlaneMutationRequest(
    val sqlid: String,
    val zfjid: String? = null,
    val jllx: String? = null,
    val yhdm: String? = null,
    val xh: String? = null,
    val sfpjzj: String? = null,
    val pjid: String? = null,
    val dzlx: String? = null,
    val nr: String? = null,
    val zpjxh: String? = null,
    val zpjid: String? = null,
    val xsxh: String? = null,
    val sfnm: String? = null,
    val id: String? = null,
)

@Serializable
data class PaperPlanePointTask(
    @Serializable(with = FlexibleNullableStringSerializer::class) val id: String? = null,
    @Serializable(with = FlexibleNullableStringSerializer::class) val bz: String? = null,
    @Serializable(with = FlexibleNullableStringSerializer::class) val gzmc: String? = null,
    @Serializable(with = FlexibleNullableStringSerializer::class) val jf: String? = null,
    @Serializable(with = FlexibleNullableStringSerializer::class) val num: String? = null,
    @Serializable(with = FlexibleNullableStringSerializer::class) val sx: String? = null,
)

/** Official rule labels quote the phrase “课堂纸飞机”; accept its unquoted label too. */
fun normalizedPaperPlaneTaskName(value: String?): String? =
    value?.trim()?.replace("“", "")?.replace("”", "")

@Serializable
data class PaperPlanePointInfo(
    @Serializable(with = FlexibleNullableStringSerializer::class) val jf: String? = null,
)

@Serializable
data class PaperPlanePointTasksResponse(
    val info: PaperPlanePointInfo? = null,
    val list: List<PaperPlanePointTask> = emptyList(),
    @kotlinx.serialization.Transient val integralError: String? = null,
)

@Serializable
data class PaperPlaneFeedItem(
    @Serializable(with = FlexibleNullableStringSerializer::class) val id: String? = null,
    @Serializable(with = FlexibleNullableStringSerializer::class) val xh: String? = null,
    @Serializable(with = FlexibleNullableStringSerializer::class) val xm: String? = null,
    @Serializable(with = FlexibleNullableStringSerializer::class) val nr: String? = null,
    @SerialName("sfdz")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val liked: Int? = null,
    @SerialName("dzsl")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val likes: Int? = null,
    @SerialName("sfyjl")
    @Serializable(with = FlexibleNullableIntSerializer::class)
    val viewed: Int? = null,
)

/** SPOC alternates between JSON numbers and numeric strings for these query fields. */
internal object FlexibleNullableStringSerializer : KSerializer<String?> {
  override val descriptor =
      PrimitiveSerialDescriptor("FlexibleNullableString", PrimitiveKind.STRING).nullable

  override fun deserialize(decoder: Decoder): String? {
    val jsonDecoder = decoder as? JsonDecoder ?: error("JSON decoder required")
    return when (val value = jsonDecoder.decodeJsonElement()) {
      JsonNull -> null
      is JsonPrimitive -> value.contentOrNull
      else -> error("Expected a scalar SPOC field")
    }
  }

  override fun serialize(encoder: Encoder, value: String?) {
    val jsonEncoder = encoder as? JsonEncoder ?: error("JSON encoder required")
    jsonEncoder.encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
  }
}

internal object FlexibleNullableIntSerializer : KSerializer<Int?> {
  override val descriptor =
      PrimitiveSerialDescriptor("FlexibleNullableInt", PrimitiveKind.INT).nullable

  override fun deserialize(decoder: Decoder): Int? {
    val jsonDecoder = decoder as? JsonDecoder ?: error("JSON decoder required")
    return when (val value = jsonDecoder.decodeJsonElement()) {
      JsonNull -> null
      is JsonPrimitive ->
          value.contentOrNull?.toIntOrNull() ?: error("Expected an integer SPOC field")
      else -> error("Expected an integer SPOC field")
    }
  }

  override fun serialize(encoder: Encoder, value: Int?) {
    val jsonEncoder = encoder as? JsonEncoder ?: error("JSON encoder required")
    jsonEncoder.encodeJsonElement(value?.let { JsonPrimitive(it) } ?: JsonNull)
  }
}

@Serializable
data class PaperPlaneFeedResponse(
    val list: List<PaperPlaneFeedItem>? = null,
)

@Serializable
data class PaperPlaneReplyPermission(
    @SerialName("sfyjl") val viewed: String? = null,
    @SerialName("sfkhf") val canReply: String? = null,
)

@Serializable data class PaperPlaneTerm(val xnxq: String? = null, val sfdqxq: String? = null)

@Serializable
data class PaperPlaneQueryRequest(
    val sqlid: String,
    val xnxq: String? = null,
    val zksrq: String? = null,
    val zjsrq: String? = null,
    val hdktid: String? = null,
    val kcid: String? = null,
    val nr: String? = null,
)

@Serializable
data class PaperPlaneWeek(
    @Serializable(with = FlexibleNullableStringSerializer::class) val zc: String? = null,
    val zksrq: String? = null,
    val zjsrq: String? = null,
)

@Serializable
data class PaperPlaneCourseSession(
    val kcid: String? = null,
    val kcmc: String? = null,
    val hdktid: String? = null,
    val skkssj: String? = null,
    val skjssj: String? = null,
    val xn: String? = null,
    val xq: String? = null,
    val kkyx: String? = null,
    val kcdm: String? = null,
    val kczc: String? = null,
    val bjm: String? = null,
    val kcjc: String? = null,
    val skrq: String? = null,
)

@Serializable
data class PaperPlaneSendConfig(
    val yxskfskg: String? = null,
    val fzsrk: String? = null,
    val tssrk: String? = null,
)

@Serializable
data class PaperPlaneInstructor(
    @SerialName("zgh") val dm: String? = null,
    val type: String? = "1",
    @SerialName("jsxm") val name: String? = null,
)

@Serializable
data class PaperPlaneFeeling(
    @Serializable(with = FlexibleNullableStringSerializer::class) val id: String? = null,
    val tbmc: String? = null,
    val icon: String? = null,
    val fz: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class PaperPlaneKeyword(
    @Serializable(with = FlexibleNullableStringSerializer::class) val bankid: String? = null,
    val word: String? = null,
)

@Serializable
data class PaperPlaneFeelingRecord(val hdktid: String? = null, val gstb: String? = null)

data class PaperPlaneEvaluationOptions(
    val feelingRecorded: Boolean?,
    val feelings: List<PaperPlaneFeeling>,
    val keywords: List<PaperPlaneKeyword>,
)

data class PaperPlaneThrowPreparation(
    val course: PaperPlaneCourseSession,
    val instructors: List<PaperPlaneInstructor>,
    val feeling: PaperPlaneFeeling?,
    val keyword: PaperPlaneKeyword,
)

data class PaperPlaneThrowProgress(val phase: String, val sent: Int = 0)

@Serializable
data class PaperPlaneFeelingQuery(val sqlid: String, val hdktids: String, val xsxh: String)

@Serializable
data class PaperPlaneFeelingRequest(
    val sqlid: String = "1735277058152663b2b7e86d6d4ec71e8e1c865121bcea",
    val id: String,
    val gstb: String,
    val gsdf: kotlinx.serialization.json.JsonElement,
    val jszgh: String,
    val tbid: String,
    val xsxh: String,
    val hdktid: String,
    val xn: String,
    val xq: String,
    val kkyx: String,
    val kcdm: String,
    val kczc: String,
    val bjm: String,
    val kcjc: String,
    val skrq: String,
)

@Serializable
data class PaperPlaneThrowMessage(
    val id: String,
    val plgjc: String = "",
    val nr: String = "",
    val pllx: String = "0",
    val sfnm: String = "1",
    val xsxh: String,
    val kcid: String,
    val nrtp: String = "",
    val nrtp1: String = "",
    val nrtp2: String = "",
    val kcmc: String,
    val hdktid: String,
    val jsr: String,
)

@Serializable
data class PaperPlaneThrowRequest(
    val sqlid: String = "17346757180196a8d6cbd3e8f3a38b6b4d6c7bd35717",
    val list: List<PaperPlaneThrowMessage>,
)

data class PaperPlaneThrowSummary(
    val course: PaperPlaneCourseSession,
    val attempted: Int,
    val sent: Int,
    val stoppedOnFailure: Boolean,
    val sendError: String? = null,
    val feed: PaperPlaneFeedResponse,
    val feedError: String? = null,
    val points: PaperPlanePointTasksResponse?,
    val pointsError: String? = null,
)

data class PaperPlaneOneClickSummary(
    val sent: Int,
    val browsed: Int,
    val points: PaperPlanePointTasksResponse?,
    val error: String? = null,
)

interface PaperPlaneBackend {
  suspend fun completeOneClick(): Result<PaperPlaneOneClickSummary> =
      Result.failure(IllegalStateException("一键投掷暂不可用"))

  suspend fun getTasks(): Result<PaperPlanePointTasksResponse>

  suspend fun getCurrentTermCourses(): Result<List<PaperPlaneCourseSession>>

  suspend fun getFeed(
      course: PaperPlaneCourseSession,
      pageSize: Int = 10,
      pageNum: Int = 1,
  ): Result<PaperPlaneFeedResponse>

  suspend fun markViewed(plane: PaperPlaneFeedItem): Result<Boolean>

  suspend fun cheer(plane: PaperPlaneFeedItem): Result<Unit>

  suspend fun reply(plane: PaperPlaneFeedItem): Result<Unit>

  suspend fun prepareThrow(): Result<PaperPlaneThrowPreparation>

  suspend fun throwFiveForLatestEndedClass(
      instructorId: String? = null,
      expectedClassId: String? = null,
      onProgress: (PaperPlaneThrowProgress) -> Unit = {},
  ): Result<PaperPlaneThrowSummary>
}

internal interface PaperPlaneRemote {
  suspend fun tasks(studentId: String): PaperPlanePointTasksResponse

  suspend fun feed(
      course: PaperPlaneCourseSession,
      studentId: String,
      pageSize: Int,
      pageNum: Int,
  ): PaperPlaneFeedResponse

  suspend fun hasViewed(planeId: String, studentId: String): Boolean

  suspend fun markViewed(planeId: String, studentId: String)

  suspend fun cheer(planeId: String, studentId: String, planeAuthorId: String)

  suspend fun replyPermission(planeId: String): PaperPlaneReplyPermission

  suspend fun reply(
      planeId: String,
      studentId: String,
      planeAuthorId: String,
      replyId: String,
      message: String,
  )
}

internal interface PaperPlaneThrowRemote {
  suspend fun currentTermSchedule(): PaperPlaneCurrentTermSchedule

  suspend fun instructors(course: PaperPlaneCourseSession): List<PaperPlaneInstructor>

  suspend fun evaluationOptions(
      course: PaperPlaneCourseSession,
      studentId: String,
  ): PaperPlaneEvaluationOptions

  suspend fun recordFeeling(
      course: PaperPlaneCourseSession,
      feeling: PaperPlaneFeeling,
      studentId: String,
  )

  suspend fun throwOne(
      course: PaperPlaneCourseSession,
      instructorId: String,
      studentId: String,
      keywordId: String,
  )

  suspend fun classFeed(course: PaperPlaneCourseSession, studentId: String): PaperPlaneFeedResponse

  suspend fun points(studentId: String): PaperPlanePointTasksResponse
}

internal data class PaperPlaneCurrentTermSchedule(
    val currentTermCode: String?,
    val sendConfig: PaperPlaneSendConfig?,
    val sessions: List<PaperPlaneCourseSession>,
)

/** Direct-device paper-plane operations. Mutations are guarded by current server state. */
class PaperPlaneApi(private val backend: PaperPlaneBackend = LocalPaperPlaneBackend()) :
    PaperPlaneBackend by backend

internal class LocalPaperPlaneBackend(
    private val studentIdProvider: () -> String? = {
      LocalAuthSessionStore.get()?.let { session ->
        session.user.schoolid.ifBlank { session.username }.takeIf(String::isNotBlank)
      }
    },
    private val client: LocalSpocClient = LocalSpocClient(),
    private val remote: PaperPlaneRemote = LocalPaperPlaneRemote(client),
    private val throwRemote: PaperPlaneThrowRemote = LocalPaperPlaneThrowRemote(client),
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val timeZone: () -> TimeZone = { TimeZone.of("Asia/Shanghai") },
) : PaperPlaneBackend {
  private val mutationMutex = Mutex()
  private val attemptedCheers = mutableSetOf<String>()
  private val attemptedReplies = mutableSetOf<String>()
  private val attemptedThrowClasses = mutableSetOf<String>()
  private val attemptedViews = mutableSetOf<String>()
  private val oneClickMutex = Mutex()

  override suspend fun getTasks(): Result<PaperPlanePointTasksResponse> =
      call("无法加载纸飞机积分任务") { remote.tasks(requireStudentId()) }

  override suspend fun getCurrentTermCourses(): Result<List<PaperPlaneCourseSession>> =
      call("无法加载纸飞机课程") {
        throwRemote
            .currentTermSchedule()
            .sessions
            .filter { !it.kcid.isNullOrBlank() && !it.hdktid.isNullOrBlank() }
            .distinctBy { it.hdktid }
            .sortedBy { it.skkssj.orEmpty() }
      }

  override suspend fun getFeed(
      course: PaperPlaneCourseSession,
      pageSize: Int,
      pageNum: Int,
  ): Result<PaperPlaneFeedResponse> =
      call("无法加载纸飞机列表") {
        require(pageSize in 1..20 && pageNum > 0) { "纸飞机列表参数无效" }
        require(!course.kcid.isNullOrBlank() && !course.hdktid.isNullOrBlank()) { "课程缺少有效课堂编号" }
        remote.feed(course, requireStudentId(), pageSize, pageNum)
      }

  override suspend fun markViewed(plane: PaperPlaneFeedItem): Result<Boolean> =
      mutationMutex.withLock { markViewedLocked(plane) }

  private suspend fun markViewedLocked(plane: PaperPlaneFeedItem): Result<Boolean> =
      call("无法记录纸飞机查看状态") {
        val id = requirePlaneId(plane)
        val studentId = requireStudentId()
        if (remote.hasViewed(id, studentId)) false
        else {
          require(id !in attemptedViews) { "浏览结果尚未确认，请勿重复提交" }
          attemptedViews += id
          remote.markViewed(id, studentId)
          true
        }
      }

  override suspend fun cheer(plane: PaperPlaneFeedItem): Result<Unit> =
      mutationMutex.withLock {
        call("无法为纸飞机加油") {
          val id = requirePlaneId(plane)
          require(plane.liked == 0) { "这架纸飞机已加过油" }
          require(id !in attemptedCheers) { "这架纸飞机的加油请求已提交，请刷新列表确认结果" }
          attemptedCheers += id
          remote.cheer(
              id,
              requireStudentId(),
              plane.xh?.takeIf(String::isNotBlank) ?: error("纸飞机缺少作者编号"),
          )
          Unit
        }
      }

  @OptIn(ExperimentalUuidApi::class)
  override suspend fun reply(plane: PaperPlaneFeedItem): Result<Unit> =
      mutationMutex.withLock {
        call("无法回复纸飞机") {
          val id = requirePlaneId(plane)
          val permission = remote.replyPermission(id)
          require(permission.canReply == "1") { "这架纸飞机目前不允许回复或权限状态未知" }
          require(id !in attemptedReplies) { "这架纸飞机的回复请求已提交，请刷新列表确认结果" }
          attemptedReplies += id
          remote.reply(
              id,
              requireStudentId(),
              plane.xh?.takeIf(String::isNotBlank) ?: error("纸飞机缺少作者编号"),
              Uuid.random().toString().replace("-", "").take(20),
              PAPER_PLANE_REPLY_MESSAGE,
          )
          Unit
        }
      }

  override suspend fun prepareThrow(): Result<PaperPlaneThrowPreparation> =
      call("无法准备纸飞机投掷") { prepare(requireStudentId()) }

  override suspend fun completeOneClick(): Result<PaperPlaneOneClickSummary> {
    val diagnostic =
        LocalDiagnostics.session(DiagnosticModule.PAPER_PLANE, DiagnosticOperation.ONE_CLICK)
    return diagnostic.run {
      diagnostic.record(DiagnosticStage.START, DiagnosticOutcome.STARTED)
      try {
        completeOneClickRun().also { result ->
          diagnostic.record(
              DiagnosticStage.FINISH,
              when {
                result.isFailure -> DiagnosticOutcome.FAILURE
                result.getOrNull()?.error != null -> DiagnosticOutcome.UNCERTAIN
                else -> DiagnosticOutcome.SUCCESS
              },
              error = result.exceptionOrNull(),
          )
        }
      } catch (error: Throwable) {
        diagnostic.record(DiagnosticStage.FINISH, DiagnosticOutcome.UNCERTAIN, error = error)
        throw error
      }
    }
  }

  private suspend fun completeOneClickRun(): Result<PaperPlaneOneClickSummary> {
    if (!oneClickMutex.tryLock()) return Result.failure(IllegalStateException("正在处理，请勿重复点击"))
    try {
      return mutationMutex.withLock {
        call("一键投掷未完成") {
          val student = requireStudentId()
          fun count(tasks: PaperPlanePointTasksResponse, name: String): Int =
              tasks.list
                  .singleOrNull { normalizedPaperPlaneTaskName(it.gzmc) == name }
                  ?.num
                  ?.toIntOrNull()
                  ?.takeIf { it >= 0 } ?: throw PaperPlaneFlowException("查看状态查询失败")
          val prepared = prepare(student)
          val teacher = prepared.instructors.first()
          val classId = prepared.course.hdktid ?: error("课堂编号缺失，未执行投掷")
          require(classId !in attemptedThrowClasses) { "该课堂的投掷请求已提交，请查看列表确认结果；不会重复执行" }
          attemptedThrowClasses += classId
          val batch = sendFive(prepared, student, teacher)
          if (batch.stoppedOnFailure)
              return@call PaperPlaneOneClickSummary(batch.sent, 0, batch.points, "投掷未全部完成，请核对后再操作")
          var points = batch.points
          var browseCount = points?.let { runCatching { count(it, "浏览课堂纸飞机") }.getOrNull() }
          if (browseCount == null) {
            points = capturePlaneCall { remote.tasks(student) }.getOrNull()
            browseCount = points?.let { runCatching { count(it, "浏览课堂纸飞机") }.getOrNull() }
          }
          if (browseCount == null)
              return@call PaperPlaneOneClickSummary(batch.sent, 0, points, "查看状态查询失败")
          var browsed = 0
          var page = 1
          val visited = mutableSetOf<String>()
          var pageItems: List<PaperPlaneFeedItem>? =
              if (batch.feedError == null) batch.feed.list else null
          var viewFailure = false
          while (browseCount < 5) {
            val items =
                pageItems
                    ?: capturePlaneCall { remote.feed(batch.course, student, 20, page) }
                        .getOrNull()
                        ?.list
            pageItems = null
            if (items == null) {
              viewFailure = true
              break
            }
            var newItems = false
            for (item in items) {
              val id = item.id?.takeIf(String::isNotBlank) ?: continue
              if (!visited.add(id)) continue
              newItems = true
              val view = markViewedLocked(item)
              if (view.isFailure) {
                viewFailure = true
                break
              }
              if (!view.getOrThrow()) continue
              browsed++
              browseCount++
              if (browseCount >= 5) break
            }
            if (viewFailure) break
            if (browseCount >= 5) break
            if (items.size < 20 || !newItems) break
            page++
          }
          val refreshed = capturePlaneCall { remote.tasks(student) }.getOrNull()
          if (refreshed != null) {
            points = refreshed
            browseCount = runCatching { count(refreshed, "浏览课堂纸飞机") }.getOrNull()
          } else browseCount = null
          if (viewFailure)
              return@call PaperPlaneOneClickSummary(batch.sent, browsed, points, "查看记录失败")
          if (browseCount == null)
              return@call PaperPlaneOneClickSummary(batch.sent, browsed, points, "查看状态查询失败")
          if (browseCount < 5)
              return@call PaperPlaneOneClickSummary(batch.sent, browsed, points, "查看记录失败")
          // A later explicit click is a new five-throw batch after this one is fully confirmed.
          val confirmedPoints =
              points ?: return@call PaperPlaneOneClickSummary(batch.sent, browsed, null, "查看状态查询失败")
          if (count(confirmedPoints, "投掷课堂纸飞机") < 5)
              return@call PaperPlaneOneClickSummary(
                  batch.sent,
                  browsed,
                  points,
                  "投掷任务次数尚未更新，请稍后刷新核对",
              )
          attemptedThrowClasses.remove(batch.course.hdktid)
          PaperPlaneOneClickSummary(batch.sent, browsed, points)
        }
      }
    } finally {
      oneClickMutex.unlock()
    }
  }

  private suspend fun prepare(studentId: String): PaperPlaneThrowPreparation {
    recordDiagnostic(DiagnosticStage.COURSE, DiagnosticOutcome.STARTED)
    val snapshot = throwRemote.currentTermSchedule()
    val course =
        selectLatestEligibleEndedClass(
            snapshot.currentTermCode,
            snapshot.sendConfig,
            snapshot.sessions,
            nowMillis(),
            timeZone(),
        ) ?: throw PaperPlaneFlowException("最近已结束课程的时间或投掷规则无法确认，请刷新课程后重试")
    recordDiagnostic(DiagnosticStage.COURSE, DiagnosticOutcome.SUCCESS)
    recordDiagnostic(DiagnosticStage.KEYWORD, DiagnosticOutcome.STARTED)
    val options = throwRemote.evaluationOptions(course, studentId)
    if (options.feelingRecorded == null) throw PaperPlaneFlowException("课堂感受状态无法确认，请稍后重试")
    val feeling =
        if (options.feelingRecorded == true) null
        else {
          options.feelings.singleOrNull {
            it.tbmc == "收获满满" &&
                !it.id.isNullOrBlank() &&
                !it.icon.isNullOrBlank() &&
                (it.fz as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
          } ?: throw PaperPlaneFlowException("未找到可用的“收获满满”选项")
        }
    val keyword =
        options.keywords.singleOrNull { it.word == "案例教学" && !it.bankid.isNullOrBlank() }
            ?: throw PaperPlaneFlowException("未找到可用的“案例教学”关键词")
    recordDiagnostic(DiagnosticStage.KEYWORD, DiagnosticOutcome.SUCCESS)
    val teachers =
        throwRemote
            .instructors(course)
            .filter { it.type == "1" && !it.dm.isNullOrBlank() }
            .distinctBy { it.dm }
    if (teachers.isEmpty()) throw PaperPlaneFlowException("该课堂暂时没有可选择的教师")
    return PaperPlaneThrowPreparation(course, teachers, feeling, keyword)
  }

  override suspend fun throwFiveForLatestEndedClass(
      instructorId: String?,
      expectedClassId: String?,
      onProgress: (PaperPlaneThrowProgress) -> Unit,
  ): Result<PaperPlaneThrowSummary> =
      mutationMutex.withLock {
        call("无法投掷纸飞机") {
          onProgress(PaperPlaneThrowProgress("正在确认最近已结束课程"))
          val studentId = requireStudentId()
          val preparation = prepare(studentId)
          val course = preparation.course
          val classId = course.hdktid ?: error("课堂编号缺失，未执行投掷")
          require(expectedClassId == null || expectedClassId == classId) {
            "最近已结束课程发生变化，请重新确认课程；未执行投掷"
          }
          val teacher =
              if (instructorId == null) preparation.instructors.singleOrNull()
              else preparation.instructors.singleOrNull { it.dm == instructorId }
          require(teacher != null) { "请先选择该课堂的教师，未执行投掷" }
          require(classId !in attemptedThrowClasses) { "该课堂的投掷请求已提交，请查看列表确认结果；不会重复执行" }
          attemptedThrowClasses += classId
          sendFive(preparation, studentId, teacher, onProgress)
        }
      }

  private suspend fun sendFive(
      preparation: PaperPlaneThrowPreparation,
      studentId: String,
      teacher: PaperPlaneInstructor,
      onProgress: (PaperPlaneThrowProgress) -> Unit = {},
  ): PaperPlaneThrowSummary {
    val course = preparation.course
    preparation.feeling?.let {
      onProgress(PaperPlaneThrowProgress("正在评价课堂感受：收获满满"))
      recordDiagnostic(DiagnosticStage.FEELING, DiagnosticOutcome.STARTED)
      throwRemote.recordFeeling(course, it, studentId)
      recordDiagnostic(DiagnosticStage.FEELING, DiagnosticOutcome.SUCCESS)
    }
    var attempted = 0
    var sent = 0
    var sendError: String? = null
    for (index in 0 until PAPER_PLANE_THROW_COUNT) {
      onProgress(PaperPlaneThrowProgress("正在匿名投掷第 ${index + 1}/5 次", sent))
      attempted++
      try {
        recordDiagnostic(DiagnosticStage.THROW, DiagnosticOutcome.STARTED)
        throwRemote.throwOne(course, teacher.dm!!, studentId, preparation.keyword.bankid!!)
        recordDiagnostic(DiagnosticStage.THROW, DiagnosticOutcome.SUCCESS)
        sent++
        onProgress(PaperPlaneThrowProgress("已匿名投掷 $sent/5 次", sent))
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        recordDiagnostic(DiagnosticStage.THROW, DiagnosticOutcome.UNCERTAIN, error = error)
        sendError = error.toPaperPlaneApiException("该次投掷结果不确定，请查看列表确认").message
        break
      }
    }
    onProgress(
        PaperPlaneThrowProgress(if (sendError == null) "五次投掷完成，可查看纸飞机" else "投掷已停止，正在只读核对", sent)
    )
    val feedResult = capturePlaneCall { throwRemote.classFeed(course, studentId) }
    val pointsResult = capturePlaneCall { throwRemote.points(studentId) }
    return PaperPlaneThrowSummary(
        course,
        attempted,
        sent,
        sendError != null,
        sendError,
        feedResult.getOrDefault(PaperPlaneFeedResponse()),
        feedResult.exceptionOrNull()?.toPaperPlaneApiException("列表刷新失败")?.message,
        pointsResult.getOrNull(),
        pointsResult.exceptionOrNull()?.toPaperPlaneApiException("积分刷新失败")?.message,
    )
  }

  private fun requireStudentId(): String =
      studentIdProvider()?.takeIf(String::isNotBlank) ?: error("请先登录学校账号")

  private fun requirePlaneId(plane: PaperPlaneFeedItem): String =
      plane.id?.takeIf(String::isNotBlank) ?: error("纸飞机缺少有效编号")

  private suspend fun <T> call(message: String, block: suspend () -> T): Result<T> =
      try {
        Result.success(block())
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        recordDiagnostic(DiagnosticStage.FINISH, DiagnosticOutcome.FAILURE, error = error)
        Result.failure(error.toPaperPlaneApiException(message))
      }

  private fun Throwable.toPaperPlaneApiException(defaultMessage: String): Exception =
      when (this) {
        is PaperPlaneFlowException -> this
        is LocalSpocAuthenticationException ->
            ApiCallException(
                    message = "SPOC 登录状态异常，请重新登录后重试",
                    status = HttpStatusCode.Unauthorized,
                    code = "spoc_auth_failed",
                    cause = this,
                )
                .also { app.bucc.api.auth.LocalSessionExpiryEvents.notifyExpired() }
        is LocalSpocException ->
            ApiCallException(
                message = defaultMessage,
                status = HttpStatusCode.BadGateway,
                code = "spoc_error",
                cause = this,
            )
        is ApiCallException -> ApiCallException(defaultMessage, status, code, this)
        else -> toUserFacingApiException(defaultMessage)
      }
}

private class PaperPlaneFlowException(message: String) : IllegalStateException(message)

internal const val PAPER_PLANE_REPLY_MESSAGE = "好！"
internal const val PAPER_PLANE_THROW_COUNT = 5

internal fun selectLatestEligibleEndedClass(
    currentTermCode: String?,
    config: PaperPlaneSendConfig?,
    sessions: List<PaperPlaneCourseSession>,
    nowMillis: Long,
    timeZone: TimeZone,
): PaperPlaneCourseSession? {
  if (currentTermCode.isNullOrBlank() || config == null || config.yxskfskg !in setOf("0", "1"))
      return null
  if (config.yxskfskg == "0" && (config.fzsrk?.toLongOrNull()?.let { it >= 0 } != true)) return null
  val days = config.tssrk?.takeIf(String::isNotBlank)?.toLongOrNull()
  if (!config.tssrk.isNullOrBlank() && (days == null || days < 0 || days > 3650)) return null
  // Choose by the actual end first. Missing identifiers must never redirect a write to an older
  // class.
  val latest =
      sessions
          .filter { session ->
            session.skjssj?.let { parsePaperPlaneTime(it, timeZone) }?.let { it <= nowMillis } ==
                true
          }
          .maxByOrNull { parsePaperPlaneTime(it.skjssj!!, timeZone)!! } ?: return null
  val end = parsePaperPlaneTime(latest.skjssj!!, timeZone) ?: return null
  if (
      sessions.count { it.skjssj?.let { value -> parsePaperPlaneTime(value, timeZone) } == end } !=
          1
  )
      return null
  val start = latest.skkssj?.let { parsePaperPlaneTime(it, timeZone) } ?: return null
  if (
      start >= end ||
          latest.kcid.isNullOrBlank() ||
          latest.hdktid.isNullOrBlank() ||
          latest.kcmc.isNullOrBlank()
  )
      return null
  if (
      sessions.any { session ->
        val possibleStart = session.skkssj?.let { parsePaperPlaneTime(it, timeZone) }
        possibleStart != null &&
            possibleStart in end..nowMillis &&
            session.skjssj?.let { parsePaperPlaneTime(it, timeZone) } == null
      }
  )
      return null
  if (days != null && nowMillis > end + days * 86_400_000L) return null
  return latest
}

private fun parsePaperPlaneTime(value: String, timeZone: TimeZone): Long? =
    runCatching {
          val normalized = value.trim().replace(' ', 'T')
          LocalDateTime.parse(normalized).toInstant(timeZone).toEpochMilliseconds()
        }
        .getOrNull()

private suspend fun <T> capturePlaneCall(block: suspend () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (error: kotlinx.coroutines.CancellationException) {
      throw error
    } catch (error: Exception) {
      Result.failure(error)
    }

private class LocalPaperPlaneRemote(private val client: LocalSpocClient) : PaperPlaneRemote {
  override suspend fun tasks(studentId: String) = client.getPaperPlaneTasks(studentId)

  override suspend fun feed(
      course: PaperPlaneCourseSession,
      studentId: String,
      pageSize: Int,
      pageNum: Int,
  ) =
      client.getPaperPlaneFeed(
          PaperPlaneFeedRequest(
              pageSize = pageSize,
              pageNum = pageNum,
              kcid = course.kcid,
              pllx = "0",
              hdktid = course.hdktid,
              dqxh = studentId,
              sfdt = "0",
          )
      )

  override suspend fun hasViewed(planeId: String, studentId: String) =
      client.hasPaperPlaneViewed(planeId, studentId)

  override suspend fun markViewed(planeId: String, studentId: String) {
    client.markPaperPlaneViewed(planeId, studentId)
  }

  override suspend fun cheer(planeId: String, studentId: String, planeAuthorId: String) {
    client.cheerPaperPlane(planeId, studentId, planeAuthorId)
  }

  override suspend fun replyPermission(planeId: String) =
      client.getPaperPlaneReplyPermission(planeId)

  override suspend fun reply(
      planeId: String,
      studentId: String,
      planeAuthorId: String,
      replyId: String,
      message: String,
  ) {
    client.replyPaperPlane(planeId, studentId, planeAuthorId, replyId, message)
  }
}

private class LocalPaperPlaneThrowRemote(private val client: LocalSpocClient) :
    PaperPlaneThrowRemote {
  override suspend fun currentTermSchedule() = client.getPaperPlaneCurrentTermSchedule()

  override suspend fun instructors(course: PaperPlaneCourseSession) =
      client.getPaperPlaneInstructors(course)

  override suspend fun evaluationOptions(course: PaperPlaneCourseSession, studentId: String) =
      client.getPaperPlaneEvaluationOptions(course, studentId)

  override suspend fun recordFeeling(
      course: PaperPlaneCourseSession,
      feeling: PaperPlaneFeeling,
      studentId: String,
  ) {
    client.recordPaperPlaneFeeling(course, feeling, studentId)
  }

  override suspend fun throwOne(
      course: PaperPlaneCourseSession,
      instructorId: String,
      studentId: String,
      keywordId: String,
  ) {
    client.throwPaperPlane(course, instructorId, studentId, keywordId)
  }

  override suspend fun classFeed(course: PaperPlaneCourseSession, studentId: String) =
      client.getPaperPlaneClassFeed(course, studentId)

  override suspend fun points(studentId: String) = client.getPaperPlaneTasks(studentId)
}
