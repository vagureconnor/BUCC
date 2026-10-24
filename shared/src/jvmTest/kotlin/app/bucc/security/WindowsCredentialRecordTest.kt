package app.bucc.security

import com.sun.jna.Native
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsCredentialRecordTest {
  @Test
  fun credentialLayoutMatchesWin32FiletimeAndPointerAlignment() {
    // FILETIME consists of two DWORDs; it must not accidentally become a pointer.
    val record = WindowsCredentialRecord()
    assertEquals(if (Native.POINTER_SIZE == 8) 80 else 52, record.size())
    val fieldOffset =
        com.sun.jna.Structure::class
            .java
            .getDeclaredMethod("fieldOffset", String::class.java)
            .apply { isAccessible = true }
    assertEquals(if (Native.POINTER_SIZE == 8) 40 else 28, fieldOffset.invoke(record, "blob"))
    assertEquals(if (Native.POINTER_SIZE == 8) 72 else 48, fieldOffset.invoke(record, "userName"))
  }
}
