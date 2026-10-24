package app.bucc.homework

import app.bucc.model.dto.Week
import app.bucc.security.CampusCredential
import app.bucc.security.CampusCredentialStorage
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json

class HomeworkTest {
  private val now = LocalDateTime.parse("2026-10-12T12:00")

  private fun item(source: HomeworkSource = HomeworkSource.ACCODING) =
      HomeworkItem("test:${source.name}", source, "1", "作业", canMarkComplete = true)

  @Test
  fun deadlinesAndPartialSubmissions() {
    val pending =
        item()
            .copy(
                startAt = now,
                dueAt = LocalDateTime.parse("2026-10-11T12:00"),
                makeupDueAt = LocalDateTime.parse("2026-10-13T12:00"),
                submission = HomeworkSubmission.PARTIAL,
            )
    assertTrue(pending.showOnHome(now))
    assertTrue(pending.overdue(now))
    assertFalse(pending.expired(now))
    assertTrue(pending.expired(pending.makeupDueAt!!))
    assertFalse(pending.copy(submission = HomeworkSubmission.SUBMITTED).showOnHome(now))
    assertTrue(item().showOnHome(now)) // Unknown deadline stays unknown and actionable.
    assertNull(item().effectiveDueAt)
  }

  @Test
  fun datesAreConvertedToBeijing() {
    assertEquals(now, parseHomeworkDate("2026-10-12T04:00:00Z"))
    assertEquals(now, parseHomeworkDate("2026-10-12T06:00:00+02:00"))
    assertEquals(now, parseHomeworkDate("2026/10/12 12:00:00"))
    assertNull(parseHomeworkDate("未设置"))
  }

  @Test
  fun reportRoundsMergeAndUnstartedLabsRemainVisibleOnHome() {
    val data =
        Json.parseToJsonElement(
            """[{"taskId":"course","courseName":"实验","rounds":[{"roundId":"intro","reports":null},{"roundId":"round","projectName":"稳态法","roundName":"第5轮","weeks":"5周","dayOfWeek":"星期五","sectionPeriod":"第2节-第5节[08:50-11:50]","reports":[{"reportAttachmentToken":"uploaded","endTime":"2026-10-11 00:00:00"},{"reportAttachmentToken":null,"startTime":"2026-10-11 00:00:00","endTime":"2026-10-20 00:00:00"}]}]}]"""
        )
    val rows =
        parseByxtReports(
            data,
            "2026-2027-1",
            listOf(
                Week(
                    startDate = "2026-10-05",
                    endDate = "2026-10-11",
                    term = "2026-2027-1",
                    curWeek = false,
                    serialNumber = 5,
                    name = "第5周",
                )
            ),
        )
    assertEquals(1, rows.size)
    val report = rows.single()
    assertEquals(listOf(true, false), report.reports.map { it.uploaded })
    assertEquals(HomeworkSubmission.SUBMITTED, report.submission)
    assertEquals(
        false,
        report.reports[1].uploaded,
    ) // A later opportunity remains visible as missing.
    assertEquals(LocalDateTime.parse("2026-10-09T08:50"), report.experimentAt)
    assertFalse(report.showOnHome(now))
    assertFalse(report.copy(experimentAt = LocalDateTime.parse("2026-10-16T08:50")).showOnHome(now))
    assertFalse(report.copy(experimentAt = null).showOnHome(now))
    assertFalse(report.copy(startAt = LocalDateTime.parse("2026-10-13T08:50")).showOnHome(now))
    assertFalse(report.copy(submission = HomeworkSubmission.SUBMITTED).showOnHome(now))
    val partiallyMissed =
        report.copy(
            submission = HomeworkSubmission.PARTIAL,
            reports =
                report.reports.map {
                  if (it.uploaded) it else it.copy(dueAt = LocalDateTime.parse("2026-10-11T00:00"))
                },
        )
    assertFalse(partiallyMissed.overdue(now))
    assertFalse(partiallyMissed.expired(now))
    assertFalse(partiallyMissed.showOnHome(now))
    assertFalse(
        item(HomeworkSource.ACCODING)
            .copy(startAt = LocalDateTime.parse("2026-10-13T08:50"))
            .showOnHome(now)
    )
  }

  @Test
  fun naturalHomeworkTitleComparisonUsesNumericChunksAndKeepsZeroPaddingEquivalent() {
    val titles = listOf("稳态法 · 第10轮", "杨氏模量 · 第2轮", "拉伸2次第10组", "弯曲9次第1组", "无数字B", "无数字A")
    assertEquals(
        listOf("杨氏模量 · 第2轮", "拉伸2次第10组", "弯曲9次第1组", "稳态法 · 第10轮", "无数字B", "无数字A"),
        titles.sortedWith(::compareHomeworkTitlesNaturally),
    )
    assertEquals(0, compareHomeworkTitlesNaturally("第2轮", "第02轮"))
    assertEquals(0, compareHomeworkTitlesNaturally("第02轮", "第0002轮"))
    assertTrue(compareHomeworkTitlesNaturally("拉伸2次第10组", "弯曲2次第9组") > 0)
    assertTrue(
        compareHomeworkTitlesNaturally(
            "A999999999999999999999999999999",
            "Z1000000000000000000000000000000",
        ) < 0
    )
    assertEquals(
        listOf("A第2轮", "C第0002轮", "B第02轮", "无数字B", "无数字A"),
        listOf("无数字B", "A第2轮", "C第0002轮", "无数字A", "B第02轮")
            .sortedWith(::compareHomeworkTitlesNaturally),
    )
  }

  @Test
  fun repositoryRestoresByxtSnapshotAsSubmittedWhenAnyReportWasUploaded() = runTest {
    val settings = MapSettings()
    val cached =
        HomeworkItem(
            id = "byxt:term:course:round",
            source = HomeworkSource.BYXT,
            upstreamId = "round",
            title = "报告",
            submission = HomeworkSubmission.PARTIAL,
            reports =
                listOf(
                    ReportUpload(1, uploaded = true),
                    ReportUpload(
                        2,
                        uploaded = false,
                        dueAt = LocalDateTime.parse("2026-10-11T00:00"),
                    ),
                ),
        )
    val adapter =
        object : HomeworkAdapter {
          override val source = HomeworkSource.BYXT

          override suspend fun load() = listOf(cached)
        }
    val repository = HomeworkRepository("A", listOf(adapter), settings) { true }
    repository.refresh()
    val restored =
        HomeworkRepository("A", listOf(adapter), settings) { true }.state.value.items.single()
    assertEquals(HomeworkSubmission.SUBMITTED, restored.submission)
    assertTrue(restored.completed)
    assertFalse(restored.overdue(now))
    assertFalse(restored.expired(now))
    assertFalse(restored.showOnHome(now))
    assertFalse(restored.reports[1].uploaded)
  }

  @Test
  fun accodingFiltersTextbookAndFindsEveryPageLink() {
    fun row(id: Int, title: String, status: String = "进行中") =
        "<tr><td>$id</td><td>$status</td><td><a href='/contest-ng/index.html#/$id'>$title</a></td><td>算法</td><td>2026-10-01 10:00:00</td><td>2026-10-20 10:00:00</td></tr>"
    val parsed =
        parseAccodingPage(
            row(1, "练习") +
                row(2, "《C 语言程序设计实训教程》第2章") +
                row(3, "旧作业", "已结束") +
                "<a href='?page=2'>2</a><a href='/contest/index?page=9'>末页</a>"
        )
    assertEquals(listOf("accoding:1", "accoding:3"), parsed.items.map { it.id })
    assertEquals(setOf(2, 9), parsed.pages)
    assertTrue(parsed.items.none { it.canMarkComplete })
  }

  @Test
  fun failureRetainsOtherSourcesCacheAndCompletionIsAccountScoped() = runTest {
    val settings = MapSettings()
    var fail = false
    val a =
        object : HomeworkAdapter {
          override val source = HomeworkSource.SPOC

          override suspend fun load(): List<HomeworkItem> {
            if (fail) error("timeout")
            return listOf(item(HomeworkSource.SPOC))
          }
        }
    val b =
        object : HomeworkAdapter {
          override val source = HomeworkSource.PATPAT

          override suspend fun load() = listOf(item(source))
        }
    val repo = HomeworkRepository("A", listOf(a, b), settings) { true }
    repo.refresh()
    repo.markCompleted(item(HomeworkSource.SPOC), true)
    fail = true
    repo.refresh()
    assertEquals(2, repo.state.value.items.size)
    assertEquals("timeout", repo.state.value.sources.getValue(a.source).error)
    assertTrue(repo.state.value.items.first { it.source == a.source }.completed)
    assertNull(repo.state.value.sources.getValue(b.source).error)
    assertEquals(
        listOf(item(HomeworkSource.PATPAT)),
        repo.state.value.sources.getValue(b.source).items,
    )
    val restored = HomeworkRepository("A", listOf(a), settings) { true }
    assertTrue(restored.state.value.items.first { it.source == a.source }.completed)
    restored.markCompleted(item(HomeworkSource.SPOC), false)
    assertFalse(restored.state.value.items.first { it.source == a.source }.completed)
    assertTrue(HomeworkRepository("B", listOf(a), settings) { true }.state.value.items.isEmpty())
  }

  @Test
  fun patpatIsRefreshableAndDoesNotStartInPausedErrorState() = runTest {
    val adapter =
        object : HomeworkAdapter {
          override val source = HomeworkSource.PATPAT

          override suspend fun load() = listOf(item(source))
        }
    val repository = HomeworkRepository("A", listOf(adapter), MapSettings()) { true }
    assertNull(repository.state.value.sources.getValue(HomeworkSource.PATPAT).error)
    repository.refresh(HomeworkSource.PATPAT)
    assertEquals(
        listOf(item(HomeworkSource.PATPAT)),
        repository.state.value.sources.getValue(HomeworkSource.PATPAT).items,
    )
    assertNull(repository.state.value.sources.getValue(HomeworkSource.PATPAT).error)
  }

  @Test
  fun cancelledRefreshKeepsCache() = runTest {
    var wait = false
    val a =
        object : HomeworkAdapter {
          override val source = HomeworkSource.ACCODING

          override suspend fun load(): List<HomeworkItem> {
            if (wait) awaitCancellation()
            return listOf(item())
          }
        }
    val repo = HomeworkRepository("A", listOf(a), MapSettings()) { true }
    repo.refresh()
    wait = true
    val job = launch { repo.refresh() }
    runCurrent()
    assertTrue(repo.state.value.isLoading)
    job.cancelAndJoin()
    assertEquals(listOf(item()), repo.state.value.items)
    assertFalse(repo.state.value.isLoading)
  }

  @Test
  fun largeHomeworkSnapshotsRespectDesktopStorageLimitAndSurviveRestart() = runTest {
    val backing = MapSettings()
    val settings =
        object : Settings by backing {
          override fun putString(key: String, value: String) {
            require(value.length <= 8192) { "Value too long" }
            backing.putString(key, value)
          }
        }
    val expected = (1..100).map { item().copy(id = "test:$it", content = "作业说明".repeat(100)) }
    val adapter =
        object : HomeworkAdapter {
          override val source = HomeworkSource.ACCODING

          override suspend fun load() = expected
        }
    val repository = HomeworkRepository("A", listOf(adapter), settings) { true }
    repository.refresh()
    assertNull(repository.state.value.sources.getValue(adapter.source).error)
    assertEquals(expected, repository.state.value.items)
    assertEquals(
        expected,
        HomeworkRepository("A", listOf(adapter), settings) { true }.state.value.items,
    )
    assertTrue(backing.keys.any { it.startsWith("homework_current_part_") })
  }

  @Test
  fun separateCredentialsPersistAcrossRepositoryAndModeReplacement() = runTest {
    val previous = app.bucc.api.ConnectionModeStore.settings
    app.bucc.api.ConnectionModeStore.settings = MapSettings()
    val secureStorage = MemoryCampusCredentialStorage()
    try {
      app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.DIRECT)
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
      val firstAdapter = LoggedInAdapter(HomeworkSource.ACCODING)
      HomeworkRepository(
              "A",
              listOf(firstAdapter),
              MapSettings(),
              credentialStorage = secureStorage,
              isCurrentAccount = { true },
          )
          .login(HomeworkSource.ACCODING, "teacher@example.test", "fixture-password")

      app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
      val replacementAdapter = LoggedInAdapter(HomeworkSource.ACCODING)
      val replacement =
          HomeworkRepository(
              "A",
              listOf(replacementAdapter),
              MapSettings(),
              credentialStorage = secureStorage,
              isCurrentAccount = { true },
          )
      replacement.refresh(HomeworkSource.ACCODING)

      assertEquals(1, replacementAdapter.loginCalls)
      assertEquals("teacher@example.test", replacementAdapter.receivedUsername)
      assertEquals("fixture-password", replacementAdapter.receivedPassword)
      assertEquals(listOf("homework:A:ACCODING"), secureStorage.entries.keys.toList())
      assertNull(HomeworkCredentialStore(secureStorage).load("B", HomeworkSource.ACCODING))
      assertNull(HomeworkCredentialStore(secureStorage).load("A", HomeworkSource.PATPAT))
      assertEquals(listOf(item()), replacement.state.value.items)
    } finally {
      app.bucc.api.ConnectionModeStore.settings = previous
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    }
  }

  @Test
  fun credentialsAreSavedOnlyAfterSuccessfulSeparateLoginAndStorageFailureIsClear() = runTest {
    val storage = MemoryCampusCredentialStorage()
    val failed = LoggedInAdapter(HomeworkSource.ACCODING).apply { loginFailure = true }
    val failedRepository =
        HomeworkRepository(
            "A",
            listOf(failed),
            MapSettings(),
            credentialStorage = storage,
            isCurrentAccount = { true },
        )
    assertFails { failedRepository.login(HomeworkSource.ACCODING, "user", "password") }
    assertTrue(storage.entries.isEmpty())

    val failingStorage = MemoryCampusCredentialStorage().apply { failSave = true }
    val successfulAdapter = LoggedInAdapter(HomeworkSource.ACCODING)
    val repository =
        HomeworkRepository(
            "A",
            listOf(successfulAdapter),
            MapSettings(),
            credentialStorage = failingStorage,
            isCurrentAccount = { true },
        )
    repository.login(HomeworkSource.ACCODING, "user", "password")

    assertEquals(1, successfulAdapter.loginCalls)
    assertContains(
        repository.state.value.sources.getValue(HomeworkSource.ACCODING).error.orEmpty(),
        "已成功登录",
    )
    assertTrue(failingStorage.entries.isEmpty())
  }

  @Test
  fun concurrentRefreshRecoversOnceAndRepositoryGuardRequiresManualLoginAfterAnotherExpiry() =
      runTest {
        val storage = MemoryCampusCredentialStorage()
        HomeworkCredentialStore(storage)
            .save("A", HomeworkSource.ACCODING, "saved-user", "saved-password")
        val adapter = LoggedInAdapter(HomeworkSource.ACCODING)
        val repository =
            HomeworkRepository(
                "A",
                listOf(adapter),
                MapSettings(),
                credentialStorage = storage,
                isCurrentAccount = { true },
            )

        coroutineScope {
          val first = launch { repository.refresh(HomeworkSource.ACCODING) }
          val second = launch { repository.refresh(HomeworkSource.ACCODING) }
          joinAll(first, second)
        }
        assertEquals(1, adapter.loginCalls)
        assertEquals(listOf(item()), repository.state.value.items)

        adapter.authenticated = false
        repository.refresh(HomeworkSource.ACCODING)
        assertEquals(1, adapter.loginCalls)
        assertContains(
            repository.state.value.sources.getValue(HomeworkSource.ACCODING).error.orEmpty(),
            "请手动登录",
        )
      }

  @Test
  fun genericTimeoutNeverAttemptsSavedCredentialRecovery() = runTest {
    val storage = MemoryCampusCredentialStorage()
    HomeworkCredentialStore(storage)
        .save("A", HomeworkSource.ACCODING, "saved-user", "saved-password")
    val adapter =
        LoggedInAdapter(HomeworkSource.ACCODING).apply {
          loadFailure = IllegalStateException("timeout")
        }
    val repository =
        HomeworkRepository(
            "A",
            listOf(adapter),
            MapSettings(),
            credentialStorage = storage,
            isCurrentAccount = { true },
        )

    repository.refresh(HomeworkSource.ACCODING)

    assertEquals(0, adapter.loginCalls)
    assertEquals("timeout", repository.state.value.sources.getValue(HomeworkSource.ACCODING).error)
  }

  @Test
  fun expiredSessionWithoutSavedCredentialsRequestsManualLogin() = runTest {
    val adapter = LoggedInAdapter(HomeworkSource.ACCODING)
    val repository =
        HomeworkRepository(
            "A",
            listOf(adapter),
            MapSettings(),
            credentialStorage = MemoryCampusCredentialStorage(),
            isCurrentAccount = { true },
        )

    repository.refresh(HomeworkSource.ACCODING)

    assertEquals(0, adapter.loginCalls)
    assertContains(
        repository.state.value.sources.getValue(HomeworkSource.ACCODING).error.orEmpty(),
        "没有可用的已保存凭据",
    )
    assertContains(
        repository.state.value.sources.getValue(HomeworkSource.ACCODING).error.orEmpty(),
        "手动登录",
    )
  }

  @Test
  fun independentCredentialDebugStringRedactsBothFields() {
    val credentials = HomeworkIndependentCredentials("private-user", "private-password").toString()
    assertFalse("private-user" in credentials)
    assertFalse("private-password" in credentials)
  }

  @Test
  fun patpatDoesNotLoadOrUseCredentialsWhileWebVpnModeDisablesIt() = runTest {
    val previous = app.bucc.api.ConnectionModeStore.settings
    app.bucc.api.ConnectionModeStore.settings = MapSettings()
    val storage = MemoryCampusCredentialStorage()
    HomeworkCredentialStore(storage)
        .save("A", HomeworkSource.PATPAT, "saved-user", "saved-password")
    try {
      app.bucc.api.ConnectionModeStore.save(app.bucc.api.ConnectionMode.WEBVPN)
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
      val adapter = LoggedInAdapter(HomeworkSource.PATPAT)
      val repository =
          HomeworkRepository(
              "A",
              listOf(adapter),
              MapSettings(),
              credentialStorage = storage,
              isCurrentAccount = { true },
          )

      repository.refresh(HomeworkSource.PATPAT)
      assertFails { repository.login(HomeworkSource.PATPAT, "manual-user", "manual-password") }

      assertEquals(0, adapter.loadCalls)
      assertEquals(0, adapter.loginCalls)
    } finally {
      app.bucc.api.ConnectionModeStore.settings = previous
      app.bucc.api.ConnectionRuntime.resolveSelectedMode()
    }
  }

  private inner class LoggedInAdapter(override val source: HomeworkSource) : HomeworkAdapter {
    override val requiresSeparateLogin = true
    var authenticated = false
    var loginFailure = false
    var loadFailure: Exception? = null
    var loginCalls = 0
    var loadCalls = 0
    var receivedUsername: String? = null
    var receivedPassword: String? = null

    override suspend fun login(username: String, password: String) {
      loginCalls++
      if (loginFailure) error("fixture login failure")
      receivedUsername = username
      receivedPassword = password
      authenticated = true
    }

    override suspend fun load(): List<HomeworkItem> {
      loadCalls++
      loadFailure?.let { throw it }
      if (!authenticated) throw HomeworkLoginRequiredException("expired")
      return listOf(item(source))
    }
  }

  private class MemoryCampusCredentialStorage : CampusCredentialStorage {
    val entries = linkedMapOf<String, CampusCredential>()
    var failSave = false

    override suspend fun save(credential: CampusCredential) {
      if (failSave) error("fixture storage failure")
      entries[credential.username] = credential
    }

    override suspend fun load(username: String): CampusCredential? = entries[username]

    override suspend fun delete(username: String) {
      entries.remove(username)
    }
  }
}
