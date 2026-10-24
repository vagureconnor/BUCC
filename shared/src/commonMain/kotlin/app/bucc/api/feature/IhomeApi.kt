package app.bucc.api.feature

import app.bucc.api.ConnectionRuntime
import app.bucc.model.dto.*

interface IhomeApiBackend {
  suspend fun getPage(query: IhomeQuery): Result<IhomePage>

  suspend fun getDetail(id: Long, mine: Boolean = false): Result<IhomeAppeal>

  suspend fun getConfig(): Result<IhomeConfig>

  suspend fun getNotices(): Result<List<IhomeNotice>>

  suspend fun getNotice(id: Long): Result<IhomeNotice>

  suspend fun setReaction(id: Long, request: IhomeReactionRequest): Result<IhomeReactionResult>
}

open class IhomeApi(
    private val backend: () -> IhomeApiBackend = { ConnectionRuntime.apiFactory().ihomeApi() }
) : IhomeApiBackend {
  override suspend fun getPage(query: IhomeQuery) = backend().getPage(query)

  override suspend fun getDetail(id: Long, mine: Boolean) = backend().getDetail(id, mine)

  override suspend fun getConfig() = backend().getConfig()

  override suspend fun getNotices() = backend().getNotices()

  override suspend fun getNotice(id: Long) = backend().getNotice(id)

  override suspend fun setReaction(id: Long, request: IhomeReactionRequest) =
      backend().setReaction(id, request)
}
