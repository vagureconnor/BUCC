package app.bucc.api.feature

import app.bucc.api.ConnectionRuntime
import app.bucc.model.dto.YgdkClockinSubmitRequest
import app.bucc.model.dto.YgdkClockinSubmitResponse
import app.bucc.model.dto.YgdkOverviewResponse
import app.bucc.model.dto.YgdkRecordsPageResponse

interface YgdkApiBackend {
  suspend fun getOverview(): Result<YgdkOverviewResponse>

  suspend fun getRecords(page: Int, size: Int): Result<YgdkRecordsPageResponse>

  suspend fun submitClockin(request: YgdkClockinSubmitRequest): Result<YgdkClockinSubmitResponse>
}

open class YgdkApi(
    private val backendProvider: () -> YgdkApiBackend = { ConnectionRuntime.apiFactory().ygdkApi() }
) {
  internal constructor(backend: YgdkApiBackend) : this({ backend })

  private fun currentBackend(): YgdkApiBackend = backendProvider()

  open suspend fun getOverview(): Result<YgdkOverviewResponse> {
    return currentBackend().getOverview()
  }

  open suspend fun getRecords(page: Int = 1, size: Int = 20): Result<YgdkRecordsPageResponse> {
    return currentBackend().getRecords(page, size)
  }

  open suspend fun submitClockin(
      request: YgdkClockinSubmitRequest
  ): Result<YgdkClockinSubmitResponse> {
    return currentBackend().submitClockin(request)
  }
}
