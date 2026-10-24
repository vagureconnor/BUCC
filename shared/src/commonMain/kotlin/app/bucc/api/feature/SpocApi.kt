package app.bucc.api.feature

import app.bucc.api.ConnectionRuntime
import app.bucc.model.dto.SpocAssignmentDetailDto
import app.bucc.model.dto.SpocAssignmentsResponse

interface SpocApiBackend {
  suspend fun getAssignments(): Result<SpocAssignmentsResponse>

  suspend fun getAssignmentDetail(assignmentId: String): Result<SpocAssignmentDetailDto>
}

/** SPOC 作业查询 API。 */
open class SpocApi(
    private val backendProvider: () -> SpocApiBackend = { ConnectionRuntime.apiFactory().spocApi() }
) {
  internal constructor(backend: SpocApiBackend) : this({ backend })

  private fun currentBackend(): SpocApiBackend = backendProvider()

  /** 获取当前默认学期的所有作业摘要。 */
  open suspend fun getAssignments(): Result<SpocAssignmentsResponse> {
    return currentBackend().getAssignments()
  }

  /** 获取指定作业的详细信息。 */
  open suspend fun getAssignmentDetail(assignmentId: String): Result<SpocAssignmentDetailDto> {
    return currentBackend().getAssignmentDetail(assignmentId)
  }
}
