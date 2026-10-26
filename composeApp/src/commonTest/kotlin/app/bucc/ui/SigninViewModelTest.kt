package app.bucc.ui

import app.bucc.api.feature.SigninApi
import app.bucc.api.feature.SigninApiBackend
import app.bucc.model.dto.SigninActionResponse
import app.bucc.model.dto.SigninClassDto
import app.bucc.model.dto.SigninStatusResponse
import app.bucc.ui.screens.signin.SigninViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SigninViewModelTest {
  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `failed load stays retryable and successful retry marks loaded`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var calls = 0
    val api =
        SigninApi {
          object : SigninApiBackend {
              override suspend fun getTodayClasses(): Result<SigninStatusResponse> {
                calls++
                return if (calls == 1) Result.failure(IllegalStateException("offline"))
                else Result.success(SigninStatusResponse(code = 200, message = "ok", data = emptyList()))
              }

              override suspend fun performSignin(courseId: String) =
                  Result.success(SigninActionResponse(200, true, "ok"))
          }
        }
    val viewModel = SigninViewModel(api)

    viewModel.ensureTodayLoaded()
    advanceUntilIdle()
    assertFalse(viewModel.hasTodayLoaded())
    assertEquals("offline", viewModel.uiState.value.error)

    viewModel.ensureTodayLoaded()
    advanceUntilIdle()
    assertTrue(viewModel.hasTodayLoaded())
    assertEquals(2, calls)
    assertEquals(null, viewModel.uiState.value.error)
  }

  @Test
  fun `overlapping loads issue only one query`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var calls = 0
    val api =
        SigninApi {
          object : SigninApiBackend {
              override suspend fun getTodayClasses(): Result<SigninStatusResponse> {
                calls++
                return Result.success(SigninStatusResponse(code = 200, message = "ok", data = emptyList()))
              }

              override suspend fun performSignin(courseId: String) =
                  Result.success(SigninActionResponse(200, true, "ok"))
          }
        }
    val viewModel = SigninViewModel(api)

    viewModel.loadTodayClasses()
    viewModel.loadTodayClasses()
    advanceUntilIdle()

    assertEquals(1, calls)
  }
}
