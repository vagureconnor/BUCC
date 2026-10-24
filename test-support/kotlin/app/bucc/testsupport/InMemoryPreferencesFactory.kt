package app.bucc.testsupport

import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/** JVM tests must never clear the application's real login or cached data. */
class InMemoryPreferencesFactory : PreferencesFactory {
  private val user = MemoryNode(null, "")
  private val system = MemoryNode(null, "")

  override fun userRoot(): Preferences = user

  override fun systemRoot(): Preferences = system

  private class MemoryNode(parent: AbstractPreferences?, name: String) :
      AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>()
    private val children = mutableMapOf<String, MemoryNode>()

    override fun putSpi(key: String, value: String) {
      values[key] = value
    }

    override fun getSpi(key: String): String? = values[key]

    override fun removeSpi(key: String) {
      values.remove(key)
    }

    override fun removeNodeSpi() {
      values.clear()
      children.clear()
      (parent() as? MemoryNode)?.children?.remove(name())
    }

    override fun keysSpi(): Array<String> = values.keys.toTypedArray()

    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()

    override fun childSpi(name: String): AbstractPreferences =
        children.getOrPut(name) { MemoryNode(this, name) }

    override fun syncSpi() {}

    override fun flushSpi() {}
  }
}
