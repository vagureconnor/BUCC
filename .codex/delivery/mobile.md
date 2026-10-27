# BUCC 2.0.1 mobile work

## Changes

- The home preview sorts todos first, displays at most three cards, and uses a non-scrollable preview container inside the existing page scroll. The complete todo list remains unlimited. Its link text is now exactly “查看所有待办”. Mobile platforms use this preview even at wide widths; desktop keeps its existing wide two-column full-list behavior.
- Removed the library camera QR route, entry points, platform scanner implementations, DTO/parser and scanner-only tests. Removed Android camera permission/features and CameraX dependencies, plus the Harmony host-probe scanner control. Kept ZXing for payment QR recognition and kept photo capture/picking paths.

## Verification

- Updated `HomeTodoViewportTest` first. The focused test failed before the implementation because swiping the todo viewport exposed card four.
- Focused `HomeTodoViewportTest.home todo preview shows at most three cards without an inner scroll`: passed.
- Full `HomeTodoViewportTest` class: passed.
- Both JVM test runs used `--max-workers=2` and `-x :composeApp:copyBuccNative`.
- `:composeApp:compileAndroidMain` could not run because this worktree has no Android SDK location (`ANDROID_HOME` or `local.properties`). An initial attempt with the non-existent task name `compileDebugKotlinAndroid` also failed during task lookup.
- Did not run the full `composeApp:jvmTest` suite or native packaging, as requested.

## Remaining coverage boundary

The JVM UI test exercises the narrow home viewport. The wide mobile branch is selected from `getPlatform().isMobile` in shared UI code, but no iOS/Android/Harmony device or wide-mobile UI test ran in this worktree.
