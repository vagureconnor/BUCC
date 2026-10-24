package app.bucc.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/** Generic credentials belong to the current Windows user's protected credential vault. */
internal class WindowsCampusCredentialStorage : CampusCredentialStorage {
  private val vault: WindowsCredentialApi by lazy {
    Native.load("Advapi32", WindowsCredentialApi::class.java)
  }

  override suspend fun save(credential: CampusCredential) {
    val bytes = credential.password.toByteArray(Charsets.UTF_16LE)
    require(bytes.size <= MAX_BLOB_BYTES) { "校园凭据超过系统安全存储长度限制" }
    try {
      Memory(maxOf(1, bytes.size).toLong()).use { secret ->
        try {
          if (bytes.isNotEmpty()) secret.write(0, bytes, 0, bytes.size)
          val record =
              WindowsCredentialRecord().apply {
                type = GENERIC
                targetName = target(credential.username)
                userName = WString(credential.username)
                blobSize = bytes.size
                blob = secret
                persist = LOCAL_MACHINE
              }
          check(vault.CredWriteW(record, 0)) { "无法保存校园凭据（系统错误 ${Native.getLastError()}）" }
        } finally {
          secret.clear()
        }
      }
    } finally {
      bytes.fill(0)
    }
  }

  override suspend fun load(username: String): CampusCredential? {
    val result = PointerByReference()
    if (!vault.CredReadW(target(username), GENERIC, 0, result)) {
      val status = Native.getLastError()
      if (status == NOT_FOUND) return null
      error("无法读取校园凭据（系统错误 $status）")
    }
    val pointer = result.value ?: error("校园凭据数据无效")
    try {
      val record = WindowsCredentialRecord(pointer)
      check(record.blobSize in 0..MAX_BLOB_BYTES && record.blobSize % 2 == 0) { "校园凭据数据无效" }
      if (record.blobSize == 0) return CampusCredential(username, "")
      val blob = record.blob ?: error("校园凭据数据无效")
      val bytes = blob.getByteArray(0, record.blobSize)
      try {
        return CampusCredential(username, bytes.toString(Charsets.UTF_16LE))
      } finally {
        bytes.fill(0)
        blob.clear(record.blobSize.toLong())
      }
    } finally {
      vault.CredFree(pointer)
    }
  }

  override suspend fun delete(username: String) {
    if (!vault.CredDeleteW(target(username), GENERIC, 0)) {
      val status = Native.getLastError()
      check(status == NOT_FOUND) { "无法删除校园凭据（系统错误 $status）" }
    }
  }

  // WinCred targets are case insensitive; hex preserves account case without collisions.
  private fun target(username: String) =
      WString(
          "BUCC:campus:" +
              username.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
      )

  private companion object {
    const val GENERIC = 1
    const val LOCAL_MACHINE = 2 // Persistent only for this user on this machine; no roaming.
    const val NOT_FOUND = 1168
    const val MAX_BLOB_BYTES = 2560
  }
}

internal interface WindowsCredentialApi : StdCallLibrary {
  fun CredWriteW(credential: WindowsCredentialRecord, flags: Int): Boolean

  fun CredReadW(target: WString, type: Int, flags: Int, output: PointerByReference): Boolean

  fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean

  fun CredFree(credential: Pointer)
}

@Structure.FieldOrder(
    "flags",
    "type",
    "targetName",
    "comment",
    "lastWrittenLow",
    "lastWrittenHigh",
    "blobSize",
    "blob",
    "persist",
    "attributeCount",
    "attributes",
    "targetAlias",
    "userName",
)
internal class WindowsCredentialRecord : Structure {
  @JvmField var flags: Int = 0
  @JvmField var type: Int = 0
  @JvmField var targetName: WString? = null
  @JvmField var comment: WString? = null
  @JvmField var lastWrittenLow: Int = 0
  @JvmField var lastWrittenHigh: Int = 0
  @JvmField var blobSize: Int = 0
  @JvmField var blob: Pointer? = null
  @JvmField var persist: Int = 0
  @JvmField var attributeCount: Int = 0
  @JvmField var attributes: Pointer? = null
  @JvmField var targetAlias: WString? = null
  @JvmField var userName: WString? = null

  constructor() : super()

  constructor(pointer: Pointer) : super(pointer) {
    read()
  }
}
