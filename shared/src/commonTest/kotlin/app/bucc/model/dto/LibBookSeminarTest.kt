package app.bucc.model.dto

import kotlin.test.*

class LibBookSeminarTest {
  private val day =
      LibBookSeminarDay(
          "2026-10-17",
          480,
          1080,
          30,
          60,
          180,
          listOf(LibBookSeminarInterval(600, 660)),
      )

  @Test
  fun optionsRespectGranularityDurationAndConflicts() {
    assertEquals(listOf(540, 570, 600), day.ends(480))
    assertFalse(day.permits(570, 630))
    assertFalse(day.permits(485, 545))
    assertFalse(day.permits(480, 540, 510))
    assertFalse(day.starts().contains(600))
    assertTrue(day.permits(660, 720))
  }

  @Test
  fun validationRequiresFieldsAndCountsApplicant() {
    val detail =
        LibBookSeminarDetail(
            LibBookSeminarRoom("156", "测试研讨室"),
            listOf(day),
            "0",
            minPeople = 2,
            maxPeople = 3,
        )
    val request = LibBookSeminarRequest("156", day.date, 480, 540, "学习", "", "讨论", "13000000000")
    assertNotNull(detail.validate(request))
    assertNull(
        detail.validate(request.copy(members = listOf(LibBookSeminarMember("fixture", "测试成员"))))
    )
    assertNotNull(detail.copy(requiresAttachment = true).validate(request))
    assertNotNull(detail.validate(request.copy(mobile = "invalid")))
  }

  @Test
  fun closedAndUnknownTimeConfigurationFailsClosed() {
    assertTrue(day.copy(step = 0).starts().isEmpty())
    assertFalse(day.copy(status = "-1").permits(480, 540))
    assertEquals("09:30", seminarTime(570))
  }

  @Test
  fun presetCategoriesAndServerTitlesAreValidated() {
    val detail =
        LibBookSeminarDetail(
            LibBookSeminarRoom("156", "测试"),
            listOf(day.copy(status = "-2")),
            "3",
            categories =
                listOf(
                    LibBookSeminarCategory("1", "上午", 480, 600),
                    LibBookSeminarCategory("2", "下午", 780, 900),
                ),
            requiresTitleChoice = true,
            titles = listOf("topic" to "学习"),
        )
    val request =
        LibBookSeminarRequest(
            "156",
            day.date,
            480,
            600,
            "",
            titleId = "topic",
            content = "讨论",
            mobile = "13000000000",
            categoryId = "1",
        )
    assertNotNull(detail.validate(request))
    assertNull(detail.validate(request.copy(begin = 780, finish = 900, categoryId = "2")))
    assertNotNull(
        detail.validate(
            request.copy(begin = 780, finish = 900, categoryId = "2", titleId = "forged")
        )
    )
  }
}
