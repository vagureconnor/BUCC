package app.bucc

import android.content.Context
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bucc.api.ConnectionModeStore
import app.bucc.security.CampusCredential
import app.bucc.security.createPlatformCampusCredentialStorage
import app.bucc.td.createTdService
import com.russhwolf.settings.Settings
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: run only on a disposable fresh emulator with no real accounts. */
@RunWith(AndroidJUnit4::class)
class AndroidPlatformSmokeTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Before
  fun requireFreshTestInstall() {
    assumeTrue(
        "Requires disposable fresh install; pass buccFreshInstall=true",
        InstrumentationRegistry.getArguments().getString("buccFreshInstall") == "true",
    )
    assertNull(
        "Do not run against an installation with a saved connection mode",
        ConnectionModeStore.get(),
    )
    assertFalse(
        "Do not run against an installation with a TD account",
        Settings().hasKey("td_student_id"),
    )
  }

  @Test
  fun freshLaunchShowsAllConnectionChoicesBeforeLogin() {
    compose.waitUntil(10_000) {
      compose.onAllNodesWithText("选择连接模式").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("选择连接模式").assertIsDisplayed()
    compose.onNodeWithText("直连模式").assertIsDisplayed()
    compose.onNodeWithText("WebVPN模式").assertIsDisplayed()
    compose.onNodeWithText("继续").performScrollTo().assertIsDisplayed()
    assertNull(ConnectionModeStore.get())
  }

  @Test
  fun initializedTdLoadsThreeBundledImagesWithoutAccountOrNetworkOperation() = runBlocking {
    val service = createTdService()
    try {
      service.load()
      val state = service.state.value
      assertNull(state.error)
      assertNull(state.account)
      assertEquals(
          setOf("image1.jpg", "image2.jpg", "image3.jpg"),
          state.images.map { it.name }.toSet(),
      )
      assertEquals(3, state.images.size)
      assertTrue(state.images.all { it.sizeBytes > 0 })
      val context = compose.activity.applicationContext
      state.images.forEach { image ->
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open("td-default-images/${image.name}").use {
          android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        assertTrue("Bundled image must decode", bounds.outWidth > 0 && bounds.outHeight > 0)
      }
    } finally {
      service.close()
    }
  }

  @Test
  fun androidKeystoreRoundTripAndDeletionUseOnlySyntheticCredential() = runBlocking {
    val storage = createPlatformCampusCredentialStorage()
    val username = "instrumentation-only-${UUID.randomUUID()}"
    val password = "synthetic-not-a-campus-password"
    val preferenceKey =
        "credential." +
            Base64.encodeToString(
                username.toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
    val preferences =
        compose.activity.getSharedPreferences("campus_secure_credentials", Context.MODE_PRIVATE)
    try {
      assertNull(storage.load(username))
      storage.save(CampusCredential(username, password))
      val reloaded = createPlatformCampusCredentialStorage().load(username)
      assertEquals(CampusCredential(username, password), reloaded)
      val encrypted = preferences.getString(preferenceKey, null)
      assertNotNull(encrypted)
      assertFalse("Password must not be stored as plaintext", encrypted!!.contains(password))
      storage.delete(username)
      assertNull(storage.load(username))
      assertFalse(preferences.contains(preferenceKey))
    } finally {
      storage.delete(username)
    }
  }
}
