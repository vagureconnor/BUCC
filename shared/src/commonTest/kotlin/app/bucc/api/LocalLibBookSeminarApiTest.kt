package app.bucc.api

import app.bucc.api.local.LocalLibBookSeminarApi
import app.bucc.model.dto.*
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class LocalLibBookSeminarApiTest {
  private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

  @Test
  fun mapsOfficialAxisAndReadsRules() = runTest {
    val paths = mutableListOf<String>()
    val api = LocalLibBookSeminarApi { _, path, body, retry ->
      paths += path
      assertTrue(retry)
      if (path == "Help/should") json("""{"code":0,"data":{"seat_rule":"使用规则"}}""")
      else {
        assertEquals("156", body["id"]?.jsonPrimitive?.content)
        json(
            """{"code":0,"data":{"detail":{"id":156,"name_merge":"测试研讨室","type_id":6,"earlierPeriods":"0","minPerson":2,"maxPerson":"4","readonlyTitle":"1","titles":[{"id":3,"title":"学习"}]},"axis":{"list":[{"date":"2026-10-17","status":1,"info":{"start_time":480,"end_time":1080,"step":30,"min_time":60,"max_time":180,"list":[{"begin_num":600,"end_num":660}]}},{"date":"2026-10-18","status":1,"info":{"start_time":540,"end_time":1020,"step":15,"min_time":30,"max_time":120,"list":[]}}]}}}"""
        )
      }
    }
    val result = api.detail("156", "2026-10-17").getOrThrow()
    assertEquals(listOf("seminar/seminar", "Help/should"), paths)
    assertEquals("使用规则", result.rules)
    assertEquals(30, result.days[0].step)
    assertEquals(15, result.days[1].step)
    assertEquals(4, result.maxPeople)
    assertTrue(result.requiresTitleChoice)
    assertFalse(result.days[0].permits(570, 630))
  }

  @Test
  fun allPagesPreservedByStableId() = runTest {
    var calls = 0
    val api = LocalLibBookSeminarApi { _, path, body, _ ->
      assertEquals("seminar/list", path)
      calls++
      val page = body["page"]?.jsonPrimitive?.int
      json(
          if (page == 1)
              """{"code":0,"data":{"data":[{"id":1,"name":"同名研讨室"},{"id":2,"name":"同名研讨室"}],"last_page":2}}"""
          else
              """{"code":0,"data":{"data":[{"id":2,"name":"同名研讨室"},{"id":3,"name":"另一个研讨室"}],"last_page":2}}"""
      )
    }
    assertEquals(listOf("1", "2", "3"), api.rooms("2026-10-17").getOrThrow().map { it.id })
    assertEquals(2, calls)
  }

  @Test
  fun submitMatchesBranchAndCannotRetry() = runTest {
    val calls = mutableListOf<String>()
    val api = LocalLibBookSeminarApi { _, path, body, retry ->
      assertFalse(retry)
      calls += path
      assertEquals("2026-10-17", body["start_date"]?.jsonPrimitive?.content)
      assertEquals(
          "09:00",
          body["time"]?.jsonArray?.first()?.jsonObject?.get("start_time")?.jsonPrimitive?.content,
      )
      assertEquals("fixture", body["team"]?.jsonPrimitive?.content)
      json("""{"code":0,"message":"已申请"}""")
    }
    val day = LibBookSeminarDay("2026-10-17", 480, 1080, 30, 30, 180, emptyList())
    val detail =
        LibBookSeminarDetail(LibBookSeminarRoom("156", "测试"), listOf(day), "0", maxPeople = 3)
    val request =
        LibBookSeminarRequest(
            "156",
            day.date,
            540,
            600,
            "讨论",
            content = "测试内容",
            mobile = "13000000000",
            members = listOf(LibBookSeminarMember("fixture", "测试")),
        )
    assertTrue(api.submit(detail, request).getOrThrow().success)
    assertTrue(api.submit(detail.copy(earlierPeriods = "1"), request).getOrThrow().success)
    assertEquals(listOf("seminar/confirm", "seminar/submit"), calls)
    assertTrue(api.submit(detail, request.copy(mobile = "bad")).isFailure)
    assertEquals(2, calls.size)
  }

  @Test
  fun requiredAttachmentUsesOfficialUploadMetadataAndIsIncludedInSubmit() = runTest {
    var uploaded = 0
    var submitted = 0
    val metadata =
        json("""{"file_name":"fixture.pdf","file_size":"4B","file_url":"/test-fixture.pdf"}""")
    val api =
        LocalLibBookSeminarApi(
            upload = { name, type, bytes ->
              uploaded++
              assertEquals("fixture.pdf", name)
              assertEquals("application/pdf", type)
              assertEquals(4, bytes.size)
              buildJsonObject {
                put("code", 0)
                put("data", metadata)
              }
            },
            request = { _, _, body, retry ->
              submitted++
              assertFalse(retry)
              assertEquals(metadata, body["file"]?.jsonArray?.single())
              json("""{"code":0,"message":"已申请"}""")
            },
        )
    val detail =
        LibBookSeminarDetail(
            LibBookSeminarRoom("156", "测试"),
            listOf(LibBookSeminarDay("2026-10-17", 480, 1080, 30, 60, 180, emptyList())),
            "0",
            requiresAttachment = true,
        )
    val request =
        LibBookSeminarRequest(
            "156",
            "2026-10-17",
            540,
            600,
            "学习",
            content = "讨论",
            mobile = "13000000000",
        )
    assertTrue(api.submit(detail, request).isFailure)
    assertEquals(0, submitted)
    val attachment =
        api.uploadAttachment("fixture.pdf", "application/pdf", byteArrayOf(1, 2, 3, 4)).getOrThrow()
    assertTrue(api.submit(detail, request.copy(attachment = attachment)).isSuccess)
    assertEquals(1, uploaded)
    assertEquals(1, submitted)
    assertTrue(api.uploadAttachment("fixture.txt", "text/plain", byteArrayOf(1)).isFailure)
    assertEquals(1, uploaded)
  }

  @Test
  fun officialFiltersPreserveCampusBuildingAndFloorRelationships() = runTest {
    val api = LocalLibBookSeminarApi { _, path, _, _ ->
      if (path == "seminar/sift")
          json(
              """{"code":0,"data":{"premises":[{"id":9,"name":"图书馆A"},{"id":"10","name":"图书馆B"}],"storey":[{"name":"一层","list":[{"id":"91","parentId":"9"},{"id":101,"parentId":10}]},{"name":"二层","list":[{"id":"92","parentId":9}]}]}}"""
          )
      else
          json(
              """{"code":0,"data":{"list":[{"id":"9","name":"学院路校区图书馆"},{"id":10,"name":"沙河校区图书馆"}]}}"""
          )
    }
    val filters = api.filters("2026-10-17").getOrThrow()
    assertEquals(listOf("学院路校区", "沙河校区"), filters.campuses.map { it.name })
    assertEquals(listOf("91", "92"), filters.campuses[0].buildings.single().floors.map { it.id })
    assertEquals(listOf("101"), filters.campuses[1].buildings.single().floors.map { it.id })
  }

  @Test
  fun filtersLoadIndependentCampusAndLibraryRequestsConcurrently() = runTest {
    var active = 0
    var peak = 0
    val api = LocalLibBookSeminarApi { _, path, _, _ ->
      active++
      peak = maxOf(peak, active)
      delay(10)
      active--
      if (path == "seminar/sift") json("""{"code":0,"data":{"premises":[]}}""")
      else json("""{"code":0,"data":{"list":[]}}""")
    }
    api.filters("2026-10-17").getOrThrow()
    assertEquals(2, peak)
  }

  @Test
  fun selectedLocationIdsAreSentForEveryPage() = runTest {
    var calls = 0
    val api = LocalLibBookSeminarApi { _, _, body, _ ->
      calls++
      assertEquals(listOf("9"), body["premises"]?.jsonArray?.map { it.jsonPrimitive.content })
      assertEquals(listOf("92"), body["floor"]?.jsonArray?.map { it.jsonPrimitive.content })
      json("""{"code":0,"data":{"data":[],"last_page":2}}""")
    }
    api.rooms("2026-10-17", "9", "92").getOrThrow()
    assertEquals(2, calls)
  }

  @Test
  fun missingCampusMetadataIsNotGuessedFromRoomTitles() = runTest {
    val api = LocalLibBookSeminarApi { _, path, _, _ ->
      if (path == "seminar/sift")
          json("""{"code":0,"data":{"premises":[{"id":9,"name":"未知馆"}],"storey":[]}}""")
      else json("""{"code":0,"data":{"list":[]}}""")
    }
    assertEquals("未标注校区", api.filters("2026-10-17").getOrThrow().campuses.single().name)
  }

  @Test
  fun officialLibraryLabelsWithoutCampusSuffixStillResolveKnownCampuses() = runTest {
    val api = LocalLibBookSeminarApi { _, path, _, _ ->
      if (path == "seminar/sift")
          json(
              """{"code":0,"data":{"premises":[{"id":9,"name":"学院路图书馆"},{"id":10,"name":"图书馆（沙河）"},{"id":11,"name":"未知馆"}],"storey":[]}}"""
          )
      else json("""{"code":0,"data":{"list":[]}}""")
    }
    val campuses = api.filters("2026-10-17").getOrThrow().campuses
    assertEquals(listOf("学院路校区", "沙河校区", "未标注校区"), campuses.map { it.name })
    assertEquals(listOf("9", "10", "11"), campuses.map { it.buildings.single().id })
  }
}
