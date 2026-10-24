package app.bucc.td

enum class TdCampus(val displayName: String) {
  XUEYUANLU("学院路"),
  SHAHE("沙河"),
}

data class TdAccount(
    val studentId: String,
    val campus: TdCampus,
    val passwordConfigured: Boolean,
    val healthCloudUsername: String = studentId,
    val healthCloudUsernameOverridden: Boolean = false,
)

data class TdAccountDraft(
    val studentId: String,
    val campus: TdCampus,
    /** Blank keeps the existing Health Cloud password. */
    val healthCloudPassword: String = "",
)

data class TdHealthCloudCredentialDraft(
    /** Null resets the Health Cloud username to the active campus account. */
    val username: String?,
    /**
     * Blank keeps the existing secure password, or uses the active campus session if it matches.
     */
    val password: String = "",
)

data class TdImage(
    val name: String,
    val sizeBytes: Long,
    val updatedAt: String? = null,
)

data class TdCounts(
    val today: Int? = null,
    val semester: Int? = null,
    val term: String? = null,
    val updatedAt: String? = null,
    val cached: Boolean = false,
)

enum class TdExecutionStatus {
  IDLE,
  RUNNING,
  COMPLETED,
  CANCELLED,
  INTERRUPTED,
  FAILED,
}

enum class TdExecutionPhase {
  PREPARING,
  ENTRANCE,
  ENTRANCE_PHOTO,
  ENTRANCE_DELAY,
  EXIT,
  EXIT_PHOTO,
  ROUND_DELAY,
}

data class TdExecutionState(
    val status: TdExecutionStatus = TdExecutionStatus.IDLE,
    val requestedRounds: Int = 0,
    val completedRounds: Int = 0,
    val currentRound: Int = 0,
    val phase: TdExecutionPhase? = null,
    val remainingSeconds: Int? = null,
    val message: String? = null,
) {
  val isActive: Boolean
    get() = status == TdExecutionStatus.RUNNING
}

data class TdState(
    val available: Boolean = true,
    val availabilityMessage: String? = null,
    val loading: Boolean = false,
    val refreshingCounts: Boolean = false,
    val account: TdAccount? = null,
    val counts: TdCounts = TdCounts(),
    val images: List<TdImage> = emptyList(),
    val execution: TdExecutionState = TdExecutionState(),
    val message: String? = null,
    val error: String? = null,
)

internal data class TdMachine(
    val id: Int,
    val serialNumber: String,
    val campus: TdCampus,
    val entrance: Boolean,
)

internal val tdMachines =
    listOf(
        TdMachine(2, "20211025001", TdCampus.XUEYUANLU, entrance = true),
        TdMachine(3, "20220301004", TdCampus.XUEYUANLU, entrance = true),
        TdMachine(4, "20230417001", TdCampus.XUEYUANLU, entrance = true),
        TdMachine(5, "20210421003", TdCampus.XUEYUANLU, entrance = false),
        TdMachine(6, "20210420002", TdCampus.XUEYUANLU, entrance = false),
        TdMachine(7, "20220301003", TdCampus.XUEYUANLU, entrance = false),
        TdMachine(8, "20210511001", TdCampus.SHAHE, entrance = true),
        TdMachine(9, "20210511002", TdCampus.SHAHE, entrance = true),
        TdMachine(10, "20210511003", TdCampus.SHAHE, entrance = true),
        TdMachine(11, "20220218001", TdCampus.SHAHE, entrance = false),
        TdMachine(12, "20220218002", TdCampus.SHAHE, entrance = false),
        TdMachine(13, "20220218003", TdCampus.SHAHE, entrance = false),
    )
