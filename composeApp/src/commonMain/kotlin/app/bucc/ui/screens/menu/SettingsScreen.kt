package app.bucc.ui.screens.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.bucc.api.ConnectionMode
import app.bucc.notifications.NotificationPreferences
import app.bucc.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    currentMode: ConnectionMode,
    availableModes: List<ConnectionMode>,
    onModeSelected: (ConnectionMode) -> Unit,
    onAccodingAccount: () -> Unit,
    onTdAccountSave: (String?, String) -> Unit,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeSelected: (ThemeMode) -> Unit = {},
    accodingAccountName: String? = null,
    tdAccountName: String? = null,
    tdDefaultAccountName: String = "",
    tdAccountOverridden: Boolean = false,
    tdPasswordConfigured: Boolean = false,
    tdAccountError: String? = null,
    tdAccountMessage: String? = null,
    tdTodoVisible: Boolean = true,
    ygdkTodoVisible: Boolean = true,
    paperPlaneTodoVisible: Boolean = true,
    onTdTodoVisibleChange: (Boolean) -> Unit = {},
    onYgdkTodoVisibleChange: (Boolean) -> Unit = {},
    onPaperPlaneTodoVisibleChange: (Boolean) -> Unit = {},
    notificationPreferences: NotificationPreferences = NotificationPreferences(),
    onNotificationPreferencesChange: (NotificationPreferences) -> Unit = {},
    notificationStatus: String? = null,
    onNotificationPermissions: () -> Unit = {},
    patpatAccountName: String? = null,
    onPatpatAccount: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
  var pendingMode by remember { mutableStateOf<ConnectionMode?>(null) }
  var showTdAccountDialog by remember { mutableStateOf(false) }

  if (showTdAccountDialog) {
    TdHealthCloudAccountDialog(
        currentUsername = tdAccountName.orEmpty(),
        defaultUsername = tdDefaultAccountName,
        overridden = tdAccountOverridden,
        passwordConfigured = tdPasswordConfigured,
        onDismiss = { showTdAccountDialog = false },
        onSave = { username, password ->
          showTdAccountDialog = false
          onTdAccountSave(username, password)
        },
    )
  }

  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("账号配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        "管理其他校园服务账号；校园统一认证与主账号保持一致。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AccountEntry("Accoding", "竞赛与作业查询", accodingAccountName, onAccodingAccount)
    AccountEntry(
        "PatPat",
        when (currentMode) {
          ConnectionMode.WEBVPN -> "WebVPN 模式下暂不可用"
          ConnectionMode.DIRECT -> "独立账号登录，不使用校园统一认证"
        },
        patpatAccountName,
        onPatpatAccount,
        enabled = currentMode != ConnectionMode.WEBVPN,
    )
    AccountEntry(
        "TD 健康云",
        when (currentMode) {
          ConnectionMode.WEBVPN -> "仅查询次数；需可访问官方健康云 WebVPN 地址"
          ConnectionMode.DIRECT -> "默认复用统一认证账号；可单独设置健康云账号"
        },
        tdAccountName,
        onClick = { showTdAccountDialog = true },
    )
    tdAccountError?.let {
      Text(
          "TD 健康云设置失败：$it",
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
      )
    }
    if (tdAccountMessage == "健康云账号设置已保存") {
      Text(
          tdAccountMessage,
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.bodySmall,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text("首页待办", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        "选择是否在首页显示这些服务的待办提醒。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    VisibilityToggle("TD 健康云", "本学期有效次数达到 32 次后不再提醒", tdTodoVisible, onTdTodoVisibleChange)
    VisibilityToggle(
        "阳光打卡",
        "第 11–14 周每周目标 4 次，学期最多 16 次",
        ygdkTodoVisible,
        onYgdkTodoVisibleChange,
    )
    VisibilityToggle(
        "课堂纸飞机",
        "每日投掷和浏览均达到 5 次后不再提醒",
        paperPlaneTodoVisible,
        onPaperPlaneTodoVisibleChange,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text("通知", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    VisibilityToggle(
        "课前打卡提醒",
        "每节课开始前十分钟",
        notificationPreferences.course,
        onChange = { onNotificationPreferencesChange(notificationPreferences.copy(course = it)) },
    )
    VisibilityToggle(
        "每日待办提醒",
        "每天北京时间十点提醒查看待办",
        notificationPreferences.dailyTodo,
        onChange = {
          onNotificationPreferencesChange(notificationPreferences.copy(dailyTodo = it))
        },
    )
    VisibilityToggle(
        "新作业提醒",
        "发现新作业时通知",
        notificationPreferences.newHomework,
        onChange = {
          onNotificationPreferencesChange(notificationPreferences.copy(newHomework = it))
        },
    )
    notificationStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = onNotificationPermissions) { Text("系统通知权限") }

    Spacer(modifier = Modifier.height(8.dp))
    Text("外观", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        "默认跟随系统，也可以固定使用浅色或深色模式。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ThemeMode.entries.forEach { mode ->
      Card(
          modifier = Modifier.fillMaxWidth().clickable { onThemeModeSelected(mode) },
          colors =
              CardDefaults.cardColors(
                  containerColor =
                      if (themeMode == mode) MaterialTheme.colorScheme.secondaryContainer
                      else MaterialTheme.colorScheme.surface
              ),
      ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(selected = themeMode == mode, onClick = { onThemeModeSelected(mode) })
          Text(
              mode.label,
              modifier = Modifier.padding(start = 12.dp),
              style = MaterialTheme.typography.titleMedium,
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "连接模式",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "当前模式：${currentMode.displayName}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = ConnectionMode.introduction,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(16.dp))

    availableModes.forEach { mode ->
      Card(
          modifier =
              Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable {
                if (mode != currentMode) {
                  pendingMode = mode
                }
              },
          colors =
              CardDefaults.cardColors(
                  containerColor =
                      if (mode == currentMode) {
                        MaterialTheme.colorScheme.secondaryContainer
                      } else {
                        MaterialTheme.colorScheme.surface
                      }
              ),
      ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = mode == currentMode,
                onClick = null,
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
              Text(text = mode.displayName, style = MaterialTheme.typography.titleMedium)
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                  text = mode.description,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }

  pendingMode?.let { mode ->
    AlertDialog(
        onDismissRequest = { pendingMode = null },
        title = { Text("切换连接模式") },
        text = { Column { Text("切换后会清除当前模式会话并尝试自动重连；如需验证码或重连失败，请按提示手动登录。") } },
        confirmButton = {
          TextButton(
              onClick = {
                pendingMode = null
                onModeSelected(mode)
              }
          ) {
            Text("确认切换")
          }
        },
        dismissButton = { TextButton(onClick = { pendingMode = null }) { Text("取消") } },
    )
  }
}

@Composable
private fun TdHealthCloudAccountDialog(
    currentUsername: String,
    defaultUsername: String,
    overridden: Boolean,
    passwordConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?, String) -> Unit,
) {
  var username by
      remember(currentUsername, defaultUsername, overridden) {
        mutableStateOf(if (overridden) currentUsername else defaultUsername)
      }
  var password by remember { mutableStateOf("") }
  var useDefault by remember(overridden) { mutableStateOf(!overridden) }
  val selectedUsername = if (useDefault) defaultUsername else username.trim()
  val valid = selectedUsername.isNotBlank() && selectedUsername.all(Char::isDigit)
  val selectedPasswordConfigured = passwordConfigured && selectedUsername == currentUsername
  val requiresPassword =
      selectedUsername != defaultUsername &&
          (selectedUsername != currentUsername || !passwordConfigured)

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("TD 健康云账号") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
              "默认使用统一认证账号和当前登录会话密码。修改内容仅在点击保存后生效。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
              value = if (useDefault) defaultUsername else username,
              onValueChange = {
                useDefault = false
                username = it.trim()
              },
              label = { Text("健康云账号") },
              singleLine = true,
              isError = !useDefault && username.isNotEmpty() && !username.all(Char::isDigit),
              modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              label = { Text("健康云密码（留空保留已保存密码）") },
              visualTransformation = PasswordVisualTransformation(),
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )
          Text(
              when {
                requiresPassword -> "切换到新的健康云账号时，需要填写该账号密码。"
                selectedPasswordConfigured -> "当前账号已有可用密码；留空不会更改。"
                useDefault -> "默认账号的密码只从当前统一认证会话读取，不会写入普通偏好存储。"
                else -> "独立健康云账号需要填写对应密码。"
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (defaultUsername.isNotBlank()) {
            TextButton(
                onClick = {
                  useDefault = true
                  username = defaultUsername
                },
            ) {
              Text("恢复统一认证账号")
            }
          }
        }
      },
      confirmButton = {
        TextButton(
            onClick = {
              val override = selectedUsername.takeUnless { it == defaultUsername }
              onSave(override, password)
            },
            enabled = valid && (!requiresPassword || password.isNotBlank()),
        ) {
          Text("保存")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

@Composable
private fun VisibilityToggle(
    title: String,
    description: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    interactive: Boolean = true,
) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(checked = enabled, onCheckedChange = onChange, enabled = interactive)
    }
  }
}

@Composable
private fun AccountEntry(
    title: String,
    description: String,
    accountName: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
  Card(
      modifier =
          Modifier.fillMaxWidth()
              .alpha(if (enabled) 1f else .38f)
              .clickable(enabled = enabled, onClick = onClick),
      colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
          accountName?.takeIf(String::isNotBlank) ?: "管理  ›",
          style = MaterialTheme.typography.labelLarge,
          color =
              if (enabled) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
