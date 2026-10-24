package app.bucc.td

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopTdBackendPythonTest {
  @Test
  fun `selects first interpreter that can import cryptography`() {
    val root = Files.createTempDirectory("bucc-python-probe")
    try {
      val missing = executable(root.resolve("missing"), "#!/bin/sh\nexit 1\n")
      val working = executable(root.resolve("working"), "#!/bin/sh\nexit 0\n")
      assertEquals(
          working.toString(),
          resolvePythonCommand(candidates = listOf(missing.toString(), working.toString())),
      )
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `explicit interpreter is validated and timeout is bounded`() {
    val root = Files.createTempDirectory("bucc-python-timeout")
    try {
      val slow = executable(root.resolve("slow"), "#!/bin/sh\nsleep 5\n")
      val error =
          assertFailsWith<IllegalStateException> {
            resolvePythonCommand(explicit = slow.toString(), timeoutMillis = 50)
          }
      assertTrue(error.message.orEmpty().contains("BUCC_AUTOTD_PYTHON"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun executable(path: java.nio.file.Path, source: String): java.nio.file.Path {
    Files.writeString(path, source)
    check(path.toFile().setExecutable(true))
    return path
  }
}
