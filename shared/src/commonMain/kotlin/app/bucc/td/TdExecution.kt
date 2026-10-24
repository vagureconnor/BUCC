package app.bucc.td

import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class TdCheckResponse(
    val success: Boolean,
    val message: String,
    val semesterCount: Int? = null,
)

internal interface TdGateway {
  suspend fun check(
      studentId: String,
      cardId: String,
      machine: TdMachine,
      timestampMillis: Long,
  ): TdCheckResponse

  suspend fun uploadPhoto(machine: TdMachine, photo: ByteArray, timestampMillis: Long)
}

internal class TdProtocolGateway(private val client: TdProtocolClient) : TdGateway {
  override suspend fun check(
      studentId: String,
      cardId: String,
      machine: TdMachine,
      timestampMillis: Long,
  ): TdCheckResponse {
    val payload =
        buildJsonObject {
              put("cardno", cardId.uppercase())
              put("userno", studentId.uppercase())
              put("timestamp", timestampMillis.toString())
              put("type", 1)
              put("eventno", TdDefaults.eventNumber)
              put("ln", machine.id.toString())
              put("sn", machine.serialNumber)
              put("schoolno", TdDefaults.schoolNumber)
            }
            .toString()
            .encodeToByteArray()
    val response = client.request(payload, TD_CHECK_REQUEST_TYPE)
    response.requireSuccess("打卡")
    val message = response["srvresp"]?.jsonPrimitive?.content.orEmpty().cleanServerMessage()
    return TdCheckResponse(
        success = "成功" in message,
        message = message,
        semesterCount = extractSemesterCount(message),
    )
  }

  override suspend fun uploadPhoto(
      machine: TdMachine,
      photo: ByteArray,
      timestampMillis: Long,
  ) {
    val prefix = "${machine.serialNumber}_$timestampMillis".encodeToByteArray()
    val response = client.request(prefix + photo, TD_PHOTO_REQUEST_TYPE)
    response.requireSuccess("照片上传")
  }
}

internal data class TdExecutionPlan(
    val studentId: String,
    val cardId: String,
    val entrance: TdMachine,
    val exit: TdMachine,
    val rounds: Int,
    val imageProvider: suspend (TdMachine) -> ByteArray,
)

internal fun interface TdExecutionDelay {
  suspend fun wait(seconds: Int, onTick: (remainingSeconds: Int) -> Unit)
}

internal class TdExecutionRunner(
    private val gateway: TdGateway,
    private val delay: TdExecutionDelay = TdExecutionDelay(::countdownDelay),
    private val waitSeconds: () -> Int = { kotlin.random.Random.nextInt(180, 241) },
    private val timestampMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
  suspend fun run(
      plan: TdExecutionPlan,
      onProgress: (TdExecutionState) -> Unit = {},
  ): Int {
    require(plan.rounds in setOf(1, 3)) { "TD rounds must be 1 or 3" }
    var completedRounds = 0
    for (roundIndex in 0 until plan.rounds) {
      val currentRound = roundIndex + 1
      var progress =
          TdExecutionState(
              status = TdExecutionStatus.RUNNING,
              requestedRounds = plan.rounds,
              completedRounds = completedRounds,
              currentRound = currentRound,
              phase = TdExecutionPhase.ENTRANCE,
          )
      onProgress(progress)

      val entranceTimestamp = timestampMillis()
      gateway.check(plan.studentId, plan.cardId, plan.entrance, entranceTimestamp).requireAccepted()
      progress = progress.copy(phase = TdExecutionPhase.ENTRANCE_PHOTO)
      onProgress(progress)
      gateway.uploadPhoto(
          plan.entrance,
          plan.imageProvider(plan.entrance),
          entranceTimestamp,
      )

      progress = progress.copy(phase = TdExecutionPhase.ENTRANCE_DELAY)
      onProgress(progress)
      waitWithProgress(waitSeconds(), progress, onProgress)

      progress = progress.copy(phase = TdExecutionPhase.EXIT, remainingSeconds = null)
      onProgress(progress)
      val exitTimestamp = timestampMillis()
      gateway.check(plan.studentId, plan.cardId, plan.exit, exitTimestamp).requireAccepted()
      progress = progress.copy(phase = TdExecutionPhase.EXIT_PHOTO)
      onProgress(progress)
      gateway.uploadPhoto(plan.exit, plan.imageProvider(plan.exit), exitTimestamp)

      completedRounds++
      progress = progress.copy(completedRounds = completedRounds)
      onProgress(progress)
      if (currentRound < plan.rounds) {
        progress = progress.copy(phase = TdExecutionPhase.ROUND_DELAY)
        onProgress(progress)
        waitWithProgress(waitSeconds(), progress, onProgress)
      }
    }
    return completedRounds
  }

  private suspend fun waitWithProgress(
      seconds: Int,
      progress: TdExecutionState,
      onProgress: (TdExecutionState) -> Unit,
  ) {
    delay.wait(seconds) { remaining -> onProgress(progress.copy(remainingSeconds = remaining)) }
  }
}

private fun TdCheckResponse.requireAccepted() {
  if (!success) throw TdProtocolException(message.ifBlank { "TD 打卡请求未成功" })
}

private suspend fun countdownDelay(seconds: Int, onTick: (Int) -> Unit) {
  for (remaining in seconds downTo 1) {
    onTick(remaining)
    delay(1_000)
  }
  onTick(0)
}

private fun JsonObject.requireSuccess(action: String) {
  val status = this["status"]?.jsonPrimitive?.content
  if (status != "success") throw TdProtocolException("$action 请求失败: ${status ?: "未知状态"}")
}

private fun extractSemesterCount(message: String): Int? =
    Regex("本学期锻炼次数\\s*[:：]\\s*(\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()

private fun String.cleanServerMessage(): String = trim().replace("\n \n", "\n").replace("\n", ", ")
