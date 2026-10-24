package app.bucc.api.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CampusCardContractTest {
  @Test
  fun `encodes with a fixed key and matches an independent AES ECB PKCS7 vector`() {
    val payload = buildJsonObject { put("a", 1) }

    val encoded = CampusCardCodec.encode(payload, key = "0123456789ABCDEF")

    assertEquals(
        "9876543210FEDCBA0cDZS/Z+QU47s4wbbDXj5Q==",
        encoded.getValue("datajson").jsonPrimitive.content,
    )
    assertEquals(payload, CampusCardCodec.decode(encoded.toString()))
  }

  @Test
  fun `accepts a plaintext JSON object without datajson`() {
    val plaintext = "{\"balance\":\"12.34\",\"status\":1}"

    assertEquals(
        buildJsonObject {
          put("balance", "12.34")
          put("status", 1)
        },
        CampusCardCodec.decode(plaintext),
    )
  }

  @Test
  fun `rejects damaged encrypted bodies`() {
    assertFailsWith<IllegalArgumentException> { CampusCardCodec.decode("{\"datajson\":\"short\"}") }
    assertFailsWith<IllegalArgumentException> {
      CampusCardCodec.decode("{\"datajson\":\"0123456789ABCDEF%%%\"}")
    }
  }

  @Test
  fun `converts positive yuan to fen exactly and enforces amount limits`() {
    assertEquals("1", campusCardAmountInFen("0.01"))
    assertEquals("1010", campusCardAmountInFen("10.10"))
    assertEquals("100000", campusCardAmountInFen("1000"))
    assertNull(campusCardAmountInFen("0"))
    assertNull(campusCardAmountInFen("0.00"))
    assertNull(campusCardAmountInFen("1000.01"))
    assertNull(campusCardAmountInFen("10.001"))
    assertNull(campusCardAmountInFen("-1"))
    assertNull(campusCardAmountInFen("1e2"))
  }

  @Test
  fun `accepts official CAS homepage callback without trailing slash`() {
    val identity =
        campusCardIdentityFromUrl(
            "https://m.icw.buaa.edu.cn/card#/pages/homepage/index/index?openid=synthetic&usertype=1",
        )
    assertEquals("synthetic", identity?.openid)
    assertEquals("1", identity?.usertype)
  }

  @Test
  fun `parses only official card identity callbacks and redacts identity`() {
    val homepage =
        campusCardIdentityFromUrl(
            "https://m.icw.buaa.edu.cn/card/#/pages/homepage/index/index?openid=synthetic-openid-42&usertype=1",
        )
    assertEquals("synthetic-openid-42", homepage?.openid)
    assertEquals("1", homepage?.usertype)

    assertNull(
        campusCardIdentityFromUrl(
            "http://m.icw.buaa.edu.cn/card/#/pages/homepage/index/index?openid=synthetic&usertype=1",
        )
    )
    assertNull(
        campusCardIdentityFromUrl(
            "https://evil.example/card/#/pages/homepage/index/index?openid=synthetic&usertype=1",
        )
    )
    assertNull(
        campusCardIdentityFromUrl(
            "https://m.icw.buaa.edu.cn/other/#/pages/homepage/index/index?openid=synthetic&usertype=1",
        )
    )
    assertNull(
        campusCardIdentityFromUrl(
            "https://m.icw.buaa.edu.cn/card/#/pages/homepage/index/index?openid=synthetic",
        )
    )
    assertNull(
        campusCardIdentityFromUrl(
            "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/1/auth",
        )
    )
    assertNull(
        campusCardIdentityFromUrl(
            "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/1/auth?ticket=synthetic-ticket",
        )
    )
    assertFalse(homepage.toString().contains("synthetic-openid-42"))
    assertFalse(homepage.toString().contains("usertype=1"))
  }
}
