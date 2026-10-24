package app.bucc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bucc.api.ConnectionModeStore
import app.bucc.security.CampusCredential
import app.bucc.security.createPlatformCampusCredentialStorage
import app.bucc.td.createTdService
import app.bucc.ui.common.util.PlatformQrScanner
import app.bucc.ui.common.util.supportsQrCamera
import com.russhwolf.settings.Settings
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
  fun scannerDeniedPermissionOffersRecoveryAndCancelWithoutDecoding() {
    val activity = compose.activity
    assumeTrue(activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
    assumeTrue(
        "Camera permission must start denied on the fresh emulator",
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED,
    )
    val requested = AtomicInteger()
    val decoded = AtomicInteger()
    val canceled = AtomicInteger()
    val registry =
        object : ActivityResultRegistry() {
          override fun <I, O> onLaunch(
              requestCode: Int,
              contract: ActivityResultContract<I, O>,
              input: I,
              options: ActivityOptionsCompat?,
          ) {
            assertEquals(Manifest.permission.CAMERA, input)
            requested.incrementAndGet()
            // Exercise the denial callback deterministically without a physical camera/system
            // dialog.
            dispatchResult(requestCode, false)
          }
        }
    val owner =
        object : ActivityResultRegistryOwner {
          override val activityResultRegistry = registry
        }
    compose.runOnUiThread {
      activity.setContent {
        MaterialTheme {
          CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            PlatformQrScanner(
                onDecoded = { decoded.incrementAndGet() },
                onCancel = { canceled.incrementAndGet() },
            )
          }
        }
      }
    }
    compose.waitUntil(10_000) { requested.get() == 1 }
    compose.onNodeWithText("相机权限未开启。请允许使用相机，或在系统设置中开启后返回。").assertIsDisplayed()
    compose.onNodeWithText("打开系统设置").assertIsDisplayed()
    compose.onNodeWithText("允许相机").performClick()
    compose.waitUntil(10_000) { requested.get() == 2 }
    compose.onNodeWithText("取消扫码").performClick()
    assertEquals(1, canceled.get())
    assertEquals(0, decoded.get())
    assertTrue(supportsQrCamera())
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
