package app.bucc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bucc.api.ConnectionMode
import app.bucc.api.ConnectionRuntime
import app.bucc.api.auth.LocalSessionExpiryEvents
import app.bucc.repository.ScheduleStore
import app.bucc.ui.navigation.AppScreen
import app.bucc.ui.navigation.MainAppScreen
import app.bucc.ui.navigation.NavigationController
import app.bucc.ui.screens.auth.AuthViewModel
import app.bucc.ui.screens.auth.ConnectionModeSelectionScreen
import app.bucc.ui.screens.auth.LoginScreen
import app.bucc.ui.screens.schedule.OfflineScheduleScreen
import app.bucc.ui.screens.splash.SplashScreen
import app.bucc.ui.theme.BUCCTheme
import app.bucc.ui.theme.PreloadFonts
import app.bucc.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/**
 * 应用程序顶层入口 Composable。 负责全局状态管理，包括：
 * 1. 字体预加载。
 * 2. 整体主题应用。
 * 3. 认证状态监听与自动登录逻辑。
 * 4. 启动界面 (Splash) 与主界面/登录界面的切换。
 */
@Composable
@Preview
fun App() {
  // 预加载应用所需的中文字体
  PreloadFonts()

  val authViewModel: AuthViewModel = viewModel { AuthViewModel() }
  val uiState by authViewModel.uiState.collectAsState()
  val navigationController = remember { NavigationController() }
  val themeAccount = uiState.userData?.schoolid ?: ScheduleStore.account().orEmpty()
  var themeMode by
      remember(themeAccount) { mutableStateOf(ThemePreferenceStore.read(themeAccount)) }
  BUCCTheme(themeMode) {
    val loginForm by authViewModel.loginForm.collectAsState()
    val availableConnectionModes = remember { ConnectionRuntime.availableModes() }
    var selectedConnectionMode by remember { mutableStateOf<ConnectionMode?>(null) }
    var navigationMode by remember { mutableStateOf<ConnectionMode?>(null) }
    var navigationAccount by remember { mutableStateOf<String?>(null) }
    var modeResolved by remember { mutableStateOf(false) }
    var showOfflineSchedule by remember { mutableStateOf(false) }

    // 启动流程控制状态
    var isSplashFinished by remember { mutableStateOf(false) }

    fun bootstrapForMode(mode: ConnectionMode) {
      selectedConnectionMode = mode
      authViewModel.initializeApp()
    }

    LaunchedEffect(Unit) {
      selectedConnectionMode = ConnectionRuntime.resolveSelectedMode()
      modeResolved = true
      if (ScheduleStore.hasSavedSchedule()) isSplashFinished = true
      selectedConnectionMode?.let(::bootstrapForMode)
    }

    LaunchedEffect(selectedConnectionMode) {
      val mode = selectedConnectionMode ?: return@LaunchedEffect
      if (navigationMode != null && navigationMode != mode) {
        navigationController.setRoot(AppScreen.HOME)
      }
      navigationMode = mode
    }

    LaunchedEffect(uiState.userData?.schoolid) {
      val account = uiState.userData?.schoolid ?: return@LaunchedEffect
      if (navigationAccount != null && navigationAccount != account) {
        navigationController.setRoot(AppScreen.HOME)
      }
      navigationAccount = account
    }

    LaunchedEffect(authViewModel) {
      LocalSessionExpiryEvents.events.collect { authViewModel.validateSession() }
    }

    // 根据认证状态和加载进度决定何时隐藏 Splash 界面
    LaunchedEffect(
        uiState.isLoggedIn,
        uiState.error,
        uiState.isLoading,
        uiState.isPreloading,
        uiState.isRefreshingCaptcha,
    ) {
      val shouldEndSplash =
          (uiState.isLoggedIn && uiState.userData != null) ||
              (uiState.error != null &&
                  !uiState.isLoading &&
                  !uiState.isPreloading &&
                  !uiState.isRefreshingCaptcha) ||
              (!uiState.isLoading &&
                  !uiState.isPreloading &&
                  !uiState.isRefreshingCaptcha &&
                  !uiState.isLoggedIn &&
                  uiState.error == null)

      if (shouldEndSplash) isSplashFinished = true
    }

    // 视图切换状态机
    when {
      !modeResolved -> SplashScreen(modifier = Modifier.fillMaxSize())
      selectedConnectionMode == null ->
          ConnectionModeSelectionScreen(
              availableModes = availableConnectionModes,
              onConfirm = { mode ->
                if (uiState.userData != null) {
                  selectedConnectionMode = mode
                  authViewModel.switchConnectionMode(mode)
                } else {
                  bootstrapForMode(mode.also(ConnectionRuntime::switchMode))
                }
              },
              modifier = Modifier.fillMaxSize(),
          )
      showOfflineSchedule && !uiState.isRecoveringSession ->
          OfflineScheduleScreen(onBack = { showOfflineSchedule = false })
      !isSplashFinished -> SplashScreen(modifier = Modifier.fillMaxSize())
      (uiState.isLoggedIn || uiState.isRecoveringSession) && uiState.userData != null -> {
        val userData = uiState.userData!!
        Box(Modifier.fillMaxSize()) {
          MainAppScreen(
              navController = navigationController,
              userData = userData,
              userInfo = uiState.userInfo,
              connectionMode = selectedConnectionMode ?: ConnectionMode.DIRECT,
              availableConnectionModes = availableConnectionModes,
              sessionAvailable = uiState.isLoggedIn,
              onEnsureUserInfo = { authViewModel.ensureUserInfoLoaded() },
              onConnectionModeSelected = { mode ->
                navigationController.setRoot(AppScreen.HOME)
                selectedConnectionMode = mode
                authViewModel.switchConnectionMode(mode)
              },
              onLogoutClick = {
                navigationController.setRoot(AppScreen.HOME)
                authViewModel.logout()
              },
              themeMode = themeMode,
              onThemeModeSelected = { mode ->
                themeMode = mode
                ThemePreferenceStore.write(themeAccount, mode)
              },
              modifier = Modifier.fillMaxSize(),
          )
          if (uiState.isRecoveringSession) {
            Dialog(
                onDismissRequest = {},
                properties =
                    DialogProperties(
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false,
                    ),
            ) {
              Box(
                  modifier =
                      Modifier.fillMaxSize()
                          .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f)),
                  contentAlignment = Alignment.Center,
              ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                  CircularProgressIndicator()
                  Text("正在恢复登录", color = MaterialTheme.colorScheme.onSurface)
                }
              }
            }
          }
        }
      }
      else -> {
        LoginScreen(
            loginFormState = loginForm,
            currentConnectionMode = selectedConnectionMode ?: ConnectionMode.DIRECT,
            availableConnectionModes = availableConnectionModes,
            onUsernameChange = { authViewModel.updateUsername(it) },
            onPasswordChange = { authViewModel.updatePassword(it) },
            onCaptchaChange = { authViewModel.updateCaptcha(it) },
            onRememberUsernameChange = { authViewModel.updateRememberUsername(it) },
            onConnectionModeSelected = { mode ->
              navigationController.setRoot(AppScreen.HOME)
              selectedConnectionMode = mode
              authViewModel.switchConnectionMode(mode)
            },
            onLoginClick = { authViewModel.login() },
            onRefreshCaptcha = { authViewModel.refreshCaptcha() },
            isLoading = uiState.isLoading,
            isRefreshingCaptcha = uiState.isRefreshingCaptcha,
            captchaRequired = uiState.captchaRequired,
            captchaInfo = uiState.captchaInfo,
            error = uiState.error,
            onOfflineSchedule =
                if (ScheduleStore.hasSavedSchedule()) ({ showOfflineSchedule = true }) else null,
            modifier = Modifier.background(MaterialTheme.colorScheme.background).fillMaxSize(),
        )
      }
    }

    // 登录失败保留提示，便于检查连接或切换模式；已登录页面仍自动淡出。
    LaunchedEffect(uiState.error, uiState.isLoggedIn) {
      if (uiState.error != null && uiState.isLoggedIn) {
        delay(5000)
        authViewModel.clearError()
      }
    }
  }
}
