package app.bucc.api.core

import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.auth.AuthServiceBackend
import app.bucc.api.auth.UserServiceBackend
import app.bucc.api.feature.BykcApiBackend
import app.bucc.api.feature.CgyyApiBackend
import app.bucc.api.feature.ClassroomApiBackend
import app.bucc.api.feature.EvaluationServiceBackend
import app.bucc.api.feature.GradeApiBackend
import app.bucc.api.feature.IhomeApiBackend
import app.bucc.api.feature.JudgeApiBackend
import app.bucc.api.feature.LibBookApiBackend
import app.bucc.api.feature.ScheduleApiBackend
import app.bucc.api.feature.SigninApiBackend
import app.bucc.api.feature.SpocApiBackend
import app.bucc.api.feature.YgdkApiBackend
import app.bucc.api.local.LocalAuthServiceBackend
import app.bucc.api.local.LocalBykcApiBackend
import app.bucc.api.local.LocalCgyyApiBackend
import app.bucc.api.local.LocalClassroomApiBackend
import app.bucc.api.local.LocalEvaluationServiceBackend
import app.bucc.api.local.LocalGradeApiBackend
import app.bucc.api.local.LocalIhomeApiBackend
import app.bucc.api.local.LocalJudgeApiBackend
import app.bucc.api.local.LocalLibBookApiBackend
import app.bucc.api.local.LocalScheduleApiBackend
import app.bucc.api.local.LocalSigninApiBackend
import app.bucc.api.local.LocalSpocApiBackend
import app.bucc.api.local.LocalUserServiceBackend
import app.bucc.api.local.LocalYgdkApiBackend

interface ApiFactory {
  fun authService(): AuthServiceBackend

  fun userService(): UserServiceBackend

  fun scheduleApi(): ScheduleApiBackend

  fun signinApi(): SigninApiBackend

  fun spocApi(): SpocApiBackend

  fun judgeApi(): JudgeApiBackend

  fun bykcApi(): BykcApiBackend

  fun cgyyApi(): CgyyApiBackend

  fun ygdkApi(): YgdkApiBackend

  fun classroomApi(): ClassroomApiBackend

  fun evaluationService(): EvaluationServiceBackend

  fun gradeApi(): GradeApiBackend

  fun libBookApi(): LibBookApiBackend

  fun ihomeApi(): IhomeApiBackend
}

internal object DefaultApiFactory : ApiFactory {
  private val directBackends = LocalBackendSet()
  private val webVpnBackends = LocalBackendSet()

  private fun mode(): ConnectionMode = ConnectionRuntime.currentMode() ?: ConnectionMode.DIRECT

  fun clearCachedBackends() {
    directBackends.clearCache()
    webVpnBackends.clearCache()
  }

  override fun authService(): AuthServiceBackend = localBackends(mode()).authService

  override fun userService(): UserServiceBackend = localBackends(mode()).userService

  override fun scheduleApi(): ScheduleApiBackend = localBackends(mode()).scheduleApi

  override fun signinApi(): SigninApiBackend = localBackends(mode()).signinApi

  override fun spocApi(): SpocApiBackend = localBackends(mode()).spocApi

  override fun judgeApi(): JudgeApiBackend = localBackends(mode()).judgeApi

  override fun bykcApi(): BykcApiBackend = localBackends(mode()).bykcApi

  override fun cgyyApi(): CgyyApiBackend = localBackends(mode()).cgyyApi

  override fun ygdkApi(): YgdkApiBackend = localBackends(mode()).ygdkApi

  override fun classroomApi(): ClassroomApiBackend = localBackends(mode()).classroomApi

  override fun evaluationService(): EvaluationServiceBackend =
      localBackends(mode()).evaluationService

  override fun gradeApi(): GradeApiBackend = localBackends(mode()).gradeApi

  override fun libBookApi(): LibBookApiBackend = localBackends(mode()).libBookApi

  override fun ihomeApi(): IhomeApiBackend = localBackends(mode()).ihomeApi

  private fun localBackends(mode: ConnectionMode): LocalBackendSet =
      when (mode) {
        ConnectionMode.DIRECT -> directBackends
        ConnectionMode.WEBVPN -> webVpnBackends
      }

  private class LocalBackendSet {
    val authService = LocalAuthServiceBackend()
    val userService = LocalUserServiceBackend()
    val scheduleApi = LocalScheduleApiBackend()
    val signinApi = LocalSigninApiBackend()
    val spocApi = LocalSpocApiBackend()
    val judgeApi = LocalJudgeApiBackend()
    val bykcApi = LocalBykcApiBackend()
    val cgyyApi = LocalCgyyApiBackend()
    val ygdkApi = LocalYgdkApiBackend()
    val classroomApi = LocalClassroomApiBackend()
    val evaluationService = LocalEvaluationServiceBackend()
    val gradeApi = LocalGradeApiBackend()
    val libBookApi = LocalLibBookApiBackend()
    val ihomeApi = LocalIhomeApiBackend()

    fun clearCache() {
      authService.resetCaptchaChallenge()
      signinApi.clearCache()
      spocApi.clearCache()
      judgeApi.clearCache()
      bykcApi.clearCache()
      cgyyApi.clearCache()
      ygdkApi.clearCache()
      classroomApi.clearCache()
      evaluationService.clearCache()
      libBookApi.clearCache()
      ihomeApi.clearCache()
    }
  }
}
