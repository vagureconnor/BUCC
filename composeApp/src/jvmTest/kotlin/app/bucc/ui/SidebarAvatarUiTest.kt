package app.bucc.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import app.bucc.api.storage.AvatarFileStorage
import app.bucc.api.storage.AvatarStore
import app.bucc.model.dto.UserData
import app.bucc.ui.common.components.AccountAvatarState
import app.bucc.ui.common.components.Sidebar
import app.bucc.ui.common.components.rememberAccountAvatar
import app.bucc.ui.screens.menu.MyScreen
import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class SidebarAvatarUiTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `profile changes update sidebar immediately and old account tasks cannot restore an avatar`() {
    val previous = AvatarStore.fileStorage
    AvatarStore.fileStorage =
        object : AvatarFileStorage {
          private var bytes: ByteArray? = null

          override fun read() = bytes

          override fun write(bytes: ByteArray): Boolean {
            this.bytes = bytes
            return true
          }

          override fun clear() {
            bytes = null
          }
        }
    var account by mutableStateOf("fixture-a")
    lateinit var state: AccountAvatarState
    val image =
        java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
          createGraphics().apply {
            color = java.awt.Color(32, 120, 220)
            fillRect(0, 0, 32, 32)
            dispose()
          }
        }
    val bytes =
        java.io
            .ByteArrayOutputStream()
            .apply { javax.imageio.ImageIO.write(image, "png", this) }
            .toByteArray()
    try {
      compose.setContent {
        state = rememberAccountAvatar(account)
        MaterialTheme {
          Row(Modifier.size(900.dp, 650.dp)) {
            Sidebar(UserData("Fixture", account), {}, {}, {}, {}, avatarState = state)
            MyScreen(null, accountId = account, avatarState = state)
          }
        }
      }
      compose.onAllNodesWithContentDescription("默认头像").assertCountEquals(2)
      compose.runOnIdle { assertTrue(state.save(bytes, "image/png")) }
      compose.onAllNodesWithContentDescription("自定义头像").assertCountEquals(2)
      saveRenderedEvidence("avatar-profile-sidebar", compose.onRoot().captureToImage())
      compose.onNodeWithText("恢复默认").performClick()
      compose.onAllNodesWithContentDescription("默认头像").assertCountEquals(2)
      compose.runOnIdle { assertTrue(state.save(bytes, "image/png")) }
      val old = state
      compose.runOnIdle { account = "fixture-b" }
      compose.waitForIdle()
      compose.onAllNodesWithContentDescription("默认头像").assertCountEquals(2)
      compose.runOnIdle {
        assertFalse(old.save(bytes, "image/png"))
        assertNull(state.avatar)
        assertNull(AvatarStore.get("fixture-b"))
      }
    } finally {
      AvatarStore.fileStorage = previous
    }
  }

  @Test
  fun `sidebar displays the custom avatar saved for its authenticated account`() {
    val previous = AvatarStore.fileStorage
    AvatarStore.fileStorage =
        object : AvatarFileStorage {
          private var bytes: ByteArray? = null

          override fun read() = bytes

          override fun write(bytes: ByteArray): Boolean {
            this.bytes = bytes
            return true
          }

          override fun clear() {
            bytes = null
          }
        }
    try {
      AvatarStore.save(
          "fixture-account",
          Base64.getDecoder()
              .decode(
                  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aAXsAAAAASUVORK5CYII="
              ),
          "image/png",
      )
      compose.setContent {
        MaterialTheme { Sidebar(UserData("Fixture", "fixture-account"), {}, {}, {}, {}) }
      }
      compose.onNodeWithContentDescription("自定义头像").assertIsDisplayed()
    } finally {
      AvatarStore.fileStorage = previous
    }
  }
}
