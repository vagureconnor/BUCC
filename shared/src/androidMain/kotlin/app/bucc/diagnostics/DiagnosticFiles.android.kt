package app.bucc.diagnostics

// Android SDK and runtime acceptance are explicitly deferred for this delivery.
internal actual object PlatformDiagnosticFiles : DiagnosticFiles {
  override var enabled = false

  override fun read(slot: Int): String? = null

  override fun write(slot: Int, value: String) = Unit

  override fun delete(slot: Int) = Unit

  override fun modifiedAt(slot: Int): Long? = null
}

internal actual fun diagnosticStackFrames(error: Throwable): List<String> =
    error.stackTrace.map { "${it.className}.${it.methodName}" }
