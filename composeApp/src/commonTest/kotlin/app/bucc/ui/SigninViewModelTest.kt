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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

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
    val api = SigninApi {
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
    val api = SigninApi {
      object : SigninApiBackend {
        override suspend fun getTodayClasses(): Result<SigninStatusResponse> {
          calls++
          return Result.success(
              SigninStatusResponse(code = 200, message = "ok", data = emptyList())
          )
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

  @Test
  fun `rapid same and different course submissions issue only one post`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val release = CompletableDeferred<Unit>()
    var calls = 0
    val api = SigninApi {
      object : SigninApiBackend {
        override suspend fun getTodayClasses() =
            Result.success(SigninStatusResponse(200, "ok", emptyList()))

        override suspend fun performSignin(courseId: String): Result<SigninActionResponse> {
          calls++
          release.await()
          return Result.success(SigninActionResponse(200, true, "ok"))
        }
      }
    }
    val viewModel = SigninViewModel(api)

    viewModel.performSignin("course-a")
    viewModel.performSignin("course-a")
    viewModel.performSignin("course-b")
    assertEquals("course-a", viewModel.uiState.value.signingInCourseId)
    runCurrent()
    assertEquals(1, calls)

    release.complete(Unit)
    advanceUntilIdle()
    assertEquals("签到成功", viewModel.uiState.value.signinResult)
  }

  @Test
  fun `reset prevents an old signin completion from filling the new session state`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val release = CompletableDeferred<Unit>()
    val api = SigninApi {
      object : SigninApiBackend {
        override suspend fun getTodayClasses() =
            Result.success(SigninStatusResponse(200, "ok", emptyList()))

        override suspend fun performSignin(courseId: String): Result<SigninActionResponse> {
          withContext(NonCancellable) { release.await() }
          return Result.success(SigninActionResponse(200, true, "old session success"))
        }
      }
    }
    val viewModel = SigninViewModel(api)

    viewModel.performSignin("course-old")
    runCurrent()
    viewModel.resetLoadedState()
    release.complete(Unit)
    advanceUntilIdle()

    assertEquals(null, viewModel.uiState.value.signingInCourseId)
    assertEquals(null, viewModel.uiState.value.signinResult)
    assertTrue(viewModel.uiState.value.classes.isEmpty())
  }

  @Test
  fun `reset prevents an old load completion from replacing new session courses`() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val oldRelease = CompletableDeferred<Unit>()
    val newRelease = CompletableDeferred<Unit>()
    var loads = 0
    val oldClass = SigninClassDto("old", "旧会话课程", "08:00", "09:00", 0)
    val newClass = SigninClassDto("new", "新会话课程", "10:00", "11:00", 0)
    val api = SigninApi {
      object : SigninApiBackend {
        override suspend fun getTodayClasses(): Result<SigninStatusResponse> {
          loads++
          if (loads == 1) {
            withContext(NonCancellable) { oldRelease.await() }
            return Result.success(SigninStatusResponse(200, "old", listOf(oldClass)))
          }
          newRelease.await()
          return Result.success(SigninStatusResponse(200, "new", listOf(newClass)))
        }

        override suspend fun performSignin(courseId: String) =
            Result.success(SigninActionResponse(200, true, "ok"))
      }
    }
    val viewModel = SigninViewModel(api)

    viewModel.loadTodayClasses()
    runCurrent()
    viewModel.resetLoadedState()
    viewModel.loadTodayClasses()
    runCurrent()
    oldRelease.complete(Unit)
    runCurrent()
    assertTrue(viewModel.uiState.value.classes.isEmpty())
    assertTrue(viewModel.uiState.value.isLoading)

    newRelease.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf(newClass), viewModel.uiState.value.classes)
    assertTrue(viewModel.hasTodayLoaded())
  }
}
