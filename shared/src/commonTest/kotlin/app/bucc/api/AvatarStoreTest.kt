package app.bucc.api

import app.bucc.api.storage.AvatarFileStorage
import app.bucc.api.storage.AvatarStore
import app.bucc.api.storage.MAX_AVATAR_BYTES
import app.bucc.api.storage.MAX_AVATAR_INPUT_BYTES
import app.bucc.api.storage.isValidAvatarInput
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarStoreTest {
  private lateinit var fileStorage: MemoryAvatarFileStorage

  @BeforeTest
  fun setUp() {
    fileStorage = MemoryAvatarFileStorage()
    AvatarStore.fileStorage = fileStorage
  }

  @AfterTest
  fun tearDown() {
    AvatarStore.fileStorage = MemoryAvatarFileStorage()
  }

  @Test
  fun `avatar persists for its account`() {
    val avatar = byteArrayOf(1, 2, 3, 4)

    assertTrue(AvatarStore.save("account-a", avatar, "image/jpeg"))

    val stored = AvatarStore.get("account-a")
    assertContentEquals(avatar, stored?.bytes)
    assertEquals("image/jpeg", stored?.mimeType)
  }

  @Test
  fun `changing the active account clears the previous avatar`() {
    assertTrue(AvatarStore.save("account-a", byteArrayOf(1, 2), "image/png"))

    assertNull(AvatarStore.get("account-b"))
    assertNull(AvatarStore.get("account-a"))
    assertNull(fileStorage.bytes)
  }

  @Test
  fun `large avatar persists without a preference value size limit`() {
    val avatar = ByteArray(100_000) { (it % 251).toByte() }

    assertTrue(AvatarStore.save("account", avatar, "image/jpeg"))

    assertContentEquals(avatar, AvatarStore.get("account")?.bytes)
  }

  @Test
  fun `clear only removes the avatar belonging to the current account`() {
    assertTrue(AvatarStore.save("account-a", byteArrayOf(1, 2), "image/png"))

    AvatarStore.clear("account-b")

    assertContentEquals(byteArrayOf(1, 2), AvatarStore.get("account-a")?.bytes)
  }

  @Test
  fun `invalid local avatar data is ignored and removed`() {
    fileStorage.bytes =
        "BUCCAV1".encodeToByteArray() + byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, -1, -1, 1)

    assertNull(AvatarStore.get("account"))
    assertNull(fileStorage.bytes)
  }

  @Test
  fun `save rejects missing account empty data invalid media and oversized data`() {
    assertFalse(AvatarStore.save("", byteArrayOf(1), "image/jpeg"))
    assertFalse(AvatarStore.save("account", byteArrayOf(), "image/jpeg"))
    assertFalse(AvatarStore.save("account", byteArrayOf(1), "text/plain"))
    assertFalse(AvatarStore.save("account", ByteArray(MAX_AVATAR_BYTES + 1), "image/jpeg"))
    assertNull(AvatarStore.get("account"))
  }

  @Test
  fun `compressed avatar limit is inclusive and source limit accepts up to 20 MB`() {
    assertTrue(AvatarStore.save("account", ByteArray(MAX_AVATAR_BYTES), "image/jpeg"))
    assertTrue(isValidAvatarInput(ByteArray(MAX_AVATAR_INPUT_BYTES), "image/png"))
    assertFalse(isValidAvatarInput(ByteArray(MAX_AVATAR_INPUT_BYTES + 1), "image/png"))
    assertFalse(isValidAvatarInput(byteArrayOf(1), "application/octet-stream"))
  }

  private class MemoryAvatarFileStorage : AvatarFileStorage {
    var bytes: ByteArray? = null

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun write(bytes: ByteArray): Boolean {
      this.bytes = bytes.copyOf()
      return true
    }

    override fun clear() {
      bytes = null
    }
  }
}
