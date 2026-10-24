package app.bucc.ui.screens.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.bucc.api.ConnectionMode
import app.bucc.model.dto.CaptchaInfo
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 登录界面 Composable 函数。 提供用户名、密码、验证码（可选）输入以及记住账号选项。
 *
 * @param loginFormState 包含当前输入的表单数据。
 * @param currentConnectionMode 当前连接模式。
 * @param availableConnectionModes 可选择的连接模式。
 * @param onUsernameChange 用户名改变回调。
 * @param onPasswordChange 密码改变回调。
 * @param onCaptchaChange 验证码改变回调。
 * @param onRememberUsernameChange “记住账号”开关回调。
 * @param onConnectionModeSelected 连接模式切换回调。
 * @param onLoginClick 点击登录按钮回调。
 * @param onRefreshCaptcha 点击验证码图片刷新回调。
 * @param isLoading 是否处于正在登录的加载状态。
 * @param isRefreshingCaptcha 是否正在刷新验证码图片。
 * @param captchaRequired 是否需要展示验证码输入框。
 * @param captchaInfo 验证码详细信息（含 Base64 图片）。
 * @param error 错误信息提示（若有）。
 */
@Composable
fun LoginScreen(
    loginFormState: LoginFormState,
    currentConnectionMode: ConnectionMode,
    availableConnectionModes: List<ConnectionMode>,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCaptchaChange: (String) -> Unit,
    onRememberUsernameChange: (Boolean) -> Unit,
    onConnectionModeSelected: (ConnectionMode) -> Unit,
    onLoginClick: () -> Unit,
    onRefreshCaptcha: () -> Unit,
    isLoading: Boolean,
    isRefreshingCaptcha: Boolean,
    captchaRequired: Boolean,
    captchaInfo: CaptchaInfo?,
    error: String?,
    modifier: Modifier = Modifier,
    onOfflineSchedule: (() -> Unit)? = null,
) {
  var modeMenuExpanded by remember { mutableStateOf(false) }
  var showModeHelp by remember { mutableStateOf(false) }
  var passwordVisible by remember { mutableStateOf(false) }

  Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
      Text(
          text = "BUCC 登录",
          style = MaterialTheme.typography.headlineMedium,
          modifier = Modifier.padding(bottom = 32.dp),
      )
      if (onOfflineSchedule != null) {
        FilledTonalButton(
            onClick = onOfflineSchedule,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
          Text("查看已保存的离线课表")
        }
      }

      OutlinedTextField(
          value = loginFormState.username,
          onValueChange = onUsernameChange,
          label = { Text("学号") },
          singleLine = true,
          enabled = !isLoading,
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
      )

      OutlinedTextField(
          value = loginFormState.password,
          onValueChange = onPasswordChange,
          label = { Text("密码") },
          singleLine = true,
          visualTransformation =
              if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !isLoading) {
              Icon(
                  imageVector =
                      if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                  contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
              )
            }
          },
          enabled = !isLoading,
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
      )

      if (captchaRequired && captchaInfo != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
              value = loginFormState.captcha,
              onValueChange = onCaptchaChange,
              label = { Text("验证码") },
              singleLine = true,
              enabled = !isLoading,
              modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          CaptchaImage(
              captchaInfo = captchaInfo,
              onClick = onRefreshCaptcha,
              isRefreshing = isRefreshingCaptcha,
              modifier = Modifier.height(56.dp).width(120.dp),
          )
        }
        Text(
            text = "点击图片刷新验证码",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
      }

      Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.clickable { onRememberUsernameChange(!loginFormState.rememberUsername) },
        ) {
          Checkbox(
              checked = loginFormState.rememberUsername,
              onCheckedChange = onRememberUsernameChange,
              enabled = !isLoading,
          )
          Text("记住账号", style = MaterialTheme.typography.bodyMedium)
        }
      }

      Button(
          onClick = onLoginClick,
          enabled =
              loginFormState.username.isNotBlank() &&
                  loginFormState.password.isNotBlank() &&
                  (!captchaRequired || loginFormState.captcha.isNotBlank()),
          modifier = Modifier.fillMaxWidth().height(48.dp),
      ) {
        if (isLoading)
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        else Text("登录")
      }

      error?.let { msg ->
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
          Column(Modifier.padding(16.dp)) {
            Text(text = msg, color = MaterialTheme.colorScheme.onErrorContainer)
            Text("连接不成功时，可检查 VPN 或切换连接模式后重试。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { modeMenuExpanded = true }, enabled = !isLoading) {
              Text("切换连接模式")
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(32.dp))
      app.bucc.ui.common.components.BuccSlogan(style = MaterialTheme.typography.bodyLarge)
    }

    ConnectionModeMenuButton(
        currentMode = currentConnectionMode,
        availableModes = availableConnectionModes,
        onModeSelected = { mode ->
          if (mode != currentConnectionMode) onConnectionModeSelected(mode)
        },
        enabled = !isLoading && !isRefreshingCaptcha,
        expanded = modeMenuExpanded,
        onExpandedChange = { modeMenuExpanded = it },
        onShowHelp = { showModeHelp = true },
        modifier =
            Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
                )
                .padding(16.dp),
    )
    if (showModeHelp) {
      AlertDialog(
          onDismissRequest = { showModeHelp = false },
          title = { Text("连接模式说明") },
          text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              availableConnectionModes.forEach { mode ->
                Text(mode.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Text(
                  ConnectionMode.introduction,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          confirmButton = { TextButton(onClick = { showModeHelp = false }) { Text("关闭") } },
      )
    }
  }
}

@Composable
private fun ConnectionModeMenuButton(
    currentMode: ConnectionMode,
    availableModes: List<ConnectionMode>,
    onModeSelected: (ConnectionMode) -> Unit,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onShowHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Box(modifier = modifier) {
    FilledTonalButton(onClick = { onExpandedChange(true) }, enabled = enabled) {
      Icon(
          imageVector = Icons.Filled.Tune,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text("模式")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
      availableModes.forEach { mode ->
        DropdownMenuItem(
            text = {
              Column {
                Text(
                    text = if (mode == currentMode) "${mode.displayName}（当前）" else mode.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            enabled = true,
            onClick = {
              onExpandedChange(false)
              onModeSelected(mode)
            },
        )
      }
      DropdownMenuItem(
          text = { Text("连接模式说明") },
          onClick = {
            onExpandedChange(false)
            onShowHelp()
          },
      )
    }
  }
}

/** 内部验证码展示组件。 */
@Composable
private fun CaptchaImage(
    captchaInfo: CaptchaInfo,
    onClick: () -> Unit,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
  @OptIn(ExperimentalEncodingApi::class)
  val imageBytes =
      remember(captchaInfo.base64Image) {
        captchaInfo.base64Image
            ?.substringAfter("base64,", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { Base64.decode(it) }
      }

  Card(
      modifier = modifier.clickable(enabled = !isRefreshing, onClick = onClick),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      if (isRefreshing)
          CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
      else if (imageBytes != null)
          AsyncImage(
              model = ImageRequest.Builder(LocalPlatformContext.current).data(imageBytes).build(),
              contentDescription = null,
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Fit,
          )
      else
          Text(
              text = "加载失败",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
          )
    }
  }
}
