package app.bucc.ui.common.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import app.bucc.api.storage.AvatarStore
import app.bucc.api.storage.StoredAvatar
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

/** Shared by the profile and sidebar for one authenticated account. */
class AccountAvatarState(val accountId: String) {
  var avatar by mutableStateOf(AvatarStore.get(accountId))
    private set

  private var active = true

  fun save(bytes: ByteArray, mimeType: String): Boolean {
    if (!active || !AvatarStore.save(accountId, bytes, mimeType)) return false
    avatar = AvatarStore.get(accountId)
    return true
  }

  fun clear() {
    if (!active) return
    AvatarStore.clear(accountId)
    avatar = null
  }

  fun deactivate() {
    active = false
  }
}

@Composable
fun rememberAccountAvatar(accountId: String): AccountAvatarState {
  val state = remember(accountId) { AccountAvatarState(accountId) }
  DisposableEffect(state) { onDispose { state.deactivate() } }
  return state
}

@Composable
fun AccountAvatar(avatar: StoredAvatar?, modifier: Modifier = Modifier) {
  val context = LocalPlatformContext.current
  val request =
      remember(avatar, context) {
        avatar?.let { ImageRequest.Builder(context).data(it.bytes).build() }
      }
  if (request != null) {
    AsyncImage(
        request,
        contentDescription = "自定义头像",
        modifier = modifier.clip(CircleShape),
        contentScale = ContentScale.Crop,
    )
  } else {
    Icon(
        Icons.Outlined.Person,
        contentDescription = "默认头像",
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
