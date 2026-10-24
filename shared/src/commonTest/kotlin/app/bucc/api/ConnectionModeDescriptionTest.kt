package app.bucc.api

import kotlin.test.Test
import kotlin.test.assertTrue

class ConnectionModeDescriptionTest {
  @Test
  fun `connection mode descriptions explain direct features and WebVPN limits`() {
    assertTrue(ConnectionMode.DIRECT.description.contains("全能模式"))
    assertTrue(ConnectionMode.DIRECT.description.contains("TD 打卡和 PatPat"))
    assertTrue(ConnectionMode.WEBVPN.description.contains("TD 仅支持次数查询"))
    assertTrue(ConnectionMode.WEBVPN.description.contains("PatPat 暂不可用"))
  }

  @Test
  fun `connection mode introduction explains TD read-only limits`() {
    assertTrue(ConnectionMode.introduction.contains("直连模式可使用完整校园服务"))
    assertTrue(ConnectionMode.introduction.contains("包括 TD 打卡和 PatPat"))
    assertTrue(ConnectionMode.introduction.contains("WebVPN 模式下部分服务受限"))
    assertTrue(ConnectionMode.introduction.contains("TD 仅支持次数查询"))
    assertTrue(ConnectionMode.introduction.contains("PatPat 暂不可用"))
  }
}
