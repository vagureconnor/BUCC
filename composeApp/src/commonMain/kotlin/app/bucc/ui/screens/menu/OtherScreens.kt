package app.bucc.ui.screens.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bucc.AppInfo
import app.bucc.api.storage.MAX_AVATAR_INPUT_BYTES
import app.bucc.api.storage.isValidAvatarInput
import app.bucc.model.dto.UserInfo
import app.bucc.ui.common.components.AccountAvatar
import app.bucc.ui.common.components.AccountAvatarState
import app.bucc.ui.common.components.BuccSlogan
import app.bucc.ui.common.components.rememberAccountAvatar
import app.bucc.ui.common.util.compressAvatarImage
import app.bucc.ui.common.util.rememberPlatformImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MyScreen(
    userInfo: UserInfo?,
    modifier: Modifier = Modifier,
    accountId: String? =
        userInfo?.schoolid?.takeIf { it.isNotBlank() }
            ?: userInfo?.username?.takeIf { it.isNotBlank() },
    avatarState: AccountAvatarState = rememberAccountAvatar(accountId.orEmpty()),
) {
  val stableAccountId = accountId?.takeIf { it.isNotBlank() }
  val avatarMessage = remember(stableAccountId) { mutableStateOf<String?>(null) }
  val avatarBusy = remember(stableAccountId) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val imagePicker =
      rememberPlatformImagePicker(
          onImagePicked = { picked ->
            when {
              stableAccountId == null -> avatarMessage.value = "当前账号信息不可用，无法保存头像"
              picked.sizeInBytes > MAX_AVATAR_INPUT_BYTES ->
                  avatarMessage.value = "图片超过 20 MB，请选择较小的图片"
              !isValidAvatarInput(picked.bytes, picked.mimeType) ->
                  avatarMessage.value = "无法识别图片格式，请重新选择"
              else -> {
                avatarBusy.value = true
                avatarMessage.value = "正在处理图片…"
                scope.launch {
                  runCatching { withContext(Dispatchers.Default) { compressAvatarImage(picked) } }
                      .onSuccess { compressed ->
                        if (avatarState.save(compressed.bytes, compressed.mimeType)) {
                          avatarMessage.value = "头像仅保存在本机，不会上传"
                        } else {
                          avatarMessage.value = "头像保存失败，请重新选择图片"
                        }
                      }
                      .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        avatarMessage.value = error.message ?: "图片处理失败，请重新选择图片"
                      }
                  avatarBusy.value = false
                }
              }
            }
          },
          onError = { avatarMessage.value = it },
      )

  Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
    // Text(
    //         text = "我的",
    //         style = MaterialTheme.typography.headlineMedium,
    //         fontWeight = FontWeight.Bold,
    //         modifier = Modifier.padding(bottom = 16.dp)
    // )

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
              modifier = Modifier.size(88.dp).clip(CircleShape),
              contentAlignment = Alignment.Center,
          ) {
            AccountAvatar(avatarState.avatar, Modifier.fillMaxSize())
          }
          Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = userInfo?.name?.takeIf { it.isNotBlank() } ?: "我的校园空间",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "头像按当前账号保存在本机，不会上传",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
              onClick = imagePicker::pickImage,
              enabled = stableAccountId != null && !avatarBusy.value,
          ) {
            Text(
                if (avatarBusy.value) "处理中…" else if (avatarState.avatar == null) "选择头像" else "更换头像"
            )
          }
          TextButton(
              onClick = {
                avatarState.clear()
                avatarMessage.value = "已恢复默认头像"
              },
              enabled = stableAccountId != null && avatarState.avatar != null,
          ) {
            Text("恢复默认")
          }
        }
        Text(
            text = "支持 20 MB 以内的图片，应用会自动缩放并压缩至 2 MB 内。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        avatarMessage.value?.let { message ->
          Text(
              text = message,
              style = MaterialTheme.typography.bodySmall,
              color =
                  if (message.contains("失败") || message.contains("超过") || message.contains("无法"))
                      MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 4.dp),
          )
        }
      }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          horizontalAlignment = Alignment.Start,
      ) {
        if (userInfo != null) {
          UserInfoItem("姓名", userInfo.name ?: "未知")
          UserInfoItem("学号", userInfo.schoolid ?: "未知")
          UserInfoItem("用户名", userInfo.username ?: "未知")
          UserInfoItem("手机号", userInfo.phone ?: "未绑定")
          UserInfoItem("邮箱", userInfo.email ?: "未绑定")
          UserInfoItem("证件类型", userInfo.idCardTypeName ?: "未知")
          UserInfoItem("证件号码", userInfo.idCardNumber ?: "未知")
        } else {
          Text(
              text = "正在加载用户信息...",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(16.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun UserInfoItem(label: String, value: String) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
  }
}

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
    // Text(
    //         text = "关于",
    //         style = MaterialTheme.typography.headlineMedium,
    //         fontWeight = FontWeight.Bold,
    //         modifier = Modifier.padding(bottom = 16.dp)
    // )

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "BUCC 应用",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "版本：${AppInfo.version}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "把上课安排、作业进度和校园服务收在一个熟悉的地方。忙着赶课赶作业时，BUCC 帮你少切几个页面，也给日常留一点从容。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "主要功能：",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text =
                "工作台与课业：课表、待办和提醒集中在工作台，方便查看课程、考试和成绩；课表支持离线查看，课程提醒帮你记住接下来的安排。\n\n" +
                    "作业中心：汇总多个来源的作业与实验任务，可查看截止时间和详情、标记完成或撤销标记，过期任务也会单独归组。\n\n" +
                    "健康与校园生活：TD 健康云可查询次数并进行直连打卡，PatPat 服务现已恢复；还可以使用阳光打卡、课堂纸飞机、iHome、空教室查询、图书馆空间预约和研讨室预约。图书馆空间预约可按日期、分区和时段查看座位，通过地图或列表选择座位，确认后提交预约，也可查看和取消预约。\n\n" +
                    "校园卡与网费：查看校园卡余额并进入官方充值流程；查看网费余额和流量，也可发起充值。网费登录在支持的会话接续场景中可免去登录验证码，官方充值表单仍可能要求图形验证码。提交后，BUCC 可识别官方二维码中的航财通地址并直接打开收银台，付款由你在官方页面确认。\n\n" +
                    "连接模式与个性化：直连模式可使用完整校园服务，包括 TD 打卡和 PatPat；WebVPN 模式下部分服务受限，TD 仅支持次数查询，PatPat 暂不可用。BUCC 支持离线缓存、本地化设置、主题和头像，也可管理 Accoding、PatPat、TD 多账号。\n\n" +
                    "BUCC 提供 macOS、Windows、Android、iOS 和鸿蒙版本；各平台功能与可用性可能随系统和校园服务调整。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "技术栈：Kotlin Multiplatform + Compose Multiplatform",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        BuccSlogan(style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }
}
