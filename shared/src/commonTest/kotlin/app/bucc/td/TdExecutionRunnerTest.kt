package app.bucc.td

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class TdExecutionRunnerTest {
  @Test
  fun oneRoundReportsProgressAndSendsEntranceThenExit() = runTest {
    val gateway = RecordingTdGateway()
    val waits = mutableListOf<Int>()
    val progress = mutableListOf<TdExecutionState>()
    val runner =
        TdExecutionRunner(
            gateway = gateway,
            delay = { seconds, _ -> waits += seconds },
            waitSeconds = { 180 },
            timestampMillis = { 1234L },
        )

    val completed = runner.run(plan(rounds = 1), progress::add)

    assertEquals(1, completed)
    assertEquals(
        listOf("check:2", "photo:2:entrance", "check:6", "photo:6:exit"),
        gateway.actions,
    )
    assertEquals(listOf(180), waits)
    assertEquals(TdExecutionPhase.ENTRANCE, progress.first().phase)
    assertEquals(1, progress.last().completedRounds)
  }

  @Test
  fun cancellationDuringDelayPreventsFutureActions() = runTest {
    val gateway = RecordingTdGateway()
    val runner =
        TdExecutionRunner(
            gateway = gateway,
            delay = { _, _ -> awaitCancellation() },
            waitSeconds = { 180 },
            timestampMillis = { 1234L },
        )

    val execution = launch { runner.run(plan(rounds = 3)) }
    testScheduler.runCurrent()
    execution.cancelAndJoin()

    assertEquals(listOf("check:2", "photo:2:entrance"), gateway.actions)
  }

  @Test
  fun rejectedEntranceStopsBeforePhotoAndExit() = runTest {
    val gateway = RecordingTdGateway(checkSuccess = false)
    val runner = TdExecutionRunner(gateway = gateway, delay = { _, _ -> })

    val error = assertFailsWith<TdProtocolException> { runner.run(plan(rounds = 1)) }

    assertTrue(error.message.orEmpty().contains("服务器拒绝"))
    assertEquals(listOf("check:2"), gateway.actions)
  }

  @Test
  fun transportFailureStopsBeforeLaterActions() = runTest {
    val gateway = RecordingTdGateway(checkFailure = TdProtocolException("socket closed"))
    val runner = TdExecutionRunner(gateway = gateway, delay = { _, _ -> })

    val error = assertFailsWith<TdProtocolException> { runner.run(plan(rounds = 1)) }

    assertEquals("socket closed", error.message)
    assertEquals(listOf("check:2"), gateway.actions)
  }
}

private fun plan(rounds: Int) =
    TdExecutionPlan(
        studentId = "20370001",
        cardId = "136D731",
        entrance = tdMachines.first { it.id == 2 },
        exit = tdMachines.first { it.id == 6 },
        rounds = rounds,
        imageProvider = { machine ->
          if (machine.entrance) "entrance".encodeToByteArray() else "exit".encodeToByteArray()
        },
    )

private class RecordingTdGateway(
    private val checkSuccess: Boolean = true,
    private val checkFailure: Throwable? = null,
) : TdGateway {
  val actions = mutableListOf<String>()

  override suspend fun check(
      studentId: String,
      cardId: String,
      machine: TdMachine,
      timestampMillis: Long,
  ): TdCheckResponse {
    actions += "check:${machine.id}"
    checkFailure?.let { throw it }
    return TdCheckResponse(
        success = checkSuccess,
        message = if (checkSuccess) "成功" else "服务器拒绝",
    )
  }

  override suspend fun uploadPhoto(machine: TdMachine, photo: ByteArray, timestampMillis: Long) {
    actions += "photo:${machine.id}:${photo.decodeToString()}"
  }
}
