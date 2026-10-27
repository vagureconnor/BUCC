import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  // Changed from androidApplication to androidKotlinMultiplatformLibrary
  alias(libs.plugins.androidKotlinMultiplatformLibrary)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
  // alias(libs.plugins.composeHotReload)
}

val composeVersion = libs.versions.composeMultiplatform.get()
val composeMaterial3Version = "1.9.0"
val composeMaterialIconsExtendedVersion = "1.7.3"

kotlin {
  jvmToolchain(21)

  android {
    namespace = "app.bucc.compose"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
    androidResources.enable = true
  }

  listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
    iosTarget.binaries.framework {
      baseName = "ComposeApp"
      isStatic = true
      binaryOption("bundleShortVersionString", project.property("project.version").toString())
      binaryOption("bundleVersion", project.property("project.version.code").toString())
    }
  }

  jvm()
  applyDefaultHierarchyTemplate()

  sourceSets {
    val webViewMain by creating {
      dependsOn(commonMain.get())
      dependencies { implementation("io.github.kevinnzou:compose-webview-multiplatform:2.0.3") }
    }
    androidMain.get().dependsOn(webViewMain)
    iosMain.get().dependsOn(webViewMain)
    jvmMain.get().dependsOn(webViewMain)
    jvmTest { kotlin.srcDir(rootProject.file("test-support/kotlin")) }
    androidMain.dependencies {
      implementation("org.jetbrains.compose.ui:ui-tooling-preview:$composeVersion")
      implementation(libs.androidx.activity.compose)
      implementation("com.google.zxing:core:3.5.3")
    }
    commonMain.dependencies {
      implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
      implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
      implementation("org.jetbrains.compose.material:material:$composeVersion")
      implementation("org.jetbrains.compose.material3:material3:$composeMaterial3Version")
      implementation("org.jetbrains.compose.ui:ui:$composeVersion")
      implementation(
          "org.jetbrains.compose.material:material-icons-extended:$composeMaterialIconsExtendedVersion"
      )
      implementation("org.jetbrains.compose.components:components-resources:$composeVersion")
      implementation("org.jetbrains.compose.ui:ui-tooling-preview:$composeVersion")
      implementation(kotlin("reflect"))
      implementation(libs.androidx.lifecycle.viewmodelCompose)
      implementation(libs.androidx.lifecycle.runtimeCompose)
      implementation(libs.kotlinx.coroutinesCore)
      implementation(libs.kotlinx.datetime)
      implementation(libs.coil3.compose)
      implementation(libs.coil3.network.ktor)
      implementation(libs.ktor.serialization.kotlinx.json)
      implementation(projects.shared)
      implementation(libs.multiplatform.settings)
      implementation("org.jetbrains.kotlin:kotlin-metadata-jvm")
    }

    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.multiplatform.settings.test)
    }

    jvmMain.dependencies {
      implementation("com.google.zxing:core:3.5.3")
      implementation("net.java.dev.jna:jna:5.19.1")
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutinesSwing)
    }

    jvmTest.dependencies {
      implementation("org.jetbrains.compose.ui:ui-test-junit4:$composeVersion")
    }
  }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
  systemProperty(
      "java.util.prefs.PreferencesFactory",
      "app.bucc.testsupport.InMemoryPreferencesFactory",
  )
}

val tdPackagingArch =
    providers
        .gradleProperty("bucc.td.arch")
        .orElse(providers.environmentVariable("BUCC_TD_ARCH"))
        .orElse(
            providers.systemProperty("os.arch").map { arch ->
              when (arch.lowercase()) {
                "aarch64" -> "arm64"
                "amd64" -> "x86_64"
                else -> arch
              }
            }
        )
val tdPackagingResources = layout.buildDirectory.dir("td-packaging/resources")

compose.desktop {
  application {
    mainClass = "app.bucc.MainKt"
    jvmArgs(
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
    )
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
      jvmArgs(
          "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED",
          "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
          "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
      )
    }
    nativeDistributions {
      appResourcesRootDir.set(tdPackagingResources)
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Exe)
      outputBaseDir.set(
          layout.buildDirectory.dir(
              providers.gradleProperty("bucc.packageOutput").getOrElse("compose/binaries")
          )
      )
      packageName = "BUCC"
      packageVersion = project.property("project.version").toString()
      macOS {
        bundleID = "app.bucc"
        iconFile = project.file("icons/app.icns")
        minimumSystemVersion = "11.0"
        infoPlist {
          extraKeysRawXml =
              """
              <key>NSCalendarsUsageDescription</key>
              <string>将课表导入系统日历</string>
              <key>NSCalendarsFullAccessUsageDescription</key>
              <string>将课表导入系统日历并更新已导入的课程</string>
              """
                  .trimIndent()
        }
      }
      linux { iconFile = project.file("icons/app.png") }

      buildTypes.release.proguard {
        isEnabled.set(false)
        version.set("7.8.2")
        configurationFiles.from("compose-desktop.pro")
      }

      windows {
        iconFile = project.file("icons/app.ico")
        menu = true
        shortcut = true
        perUserInstall = false
      }
    }
  }
}

val packageOutputPath = providers.gradleProperty("bucc.packageOutput").getOrElse("compose/binaries")
val trimDesktopIcons by
    tasks.registering(Exec::class) {
      dependsOn("createReleaseDistributable")
      val releaseAppDirectory = layout.buildDirectory.dir("$packageOutputPath/main-release/app")
      commandLine(
          if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python"
          else "python3",
          rootProject.file("script/trim_desktop_icons.py").absolutePath,
          releaseAppDirectory.get().asFile.absolutePath,
      )
    }

tasks
    .matching { it.name in setOf("packageReleaseDmg", "packageReleaseExe") }
    .configureEach { dependsOn(trimDesktopIcons) }

val macReleaseAppBundle =
    layout.buildDirectory.dir("$packageOutputPath/main-release/app/BUCC.app").get().asFile
val signTrimmedMacReleaseBundle by
    tasks.registering(Exec::class) {
      dependsOn(trimDesktopIcons)
      commandLine(
          "/usr/bin/codesign",
          "--force",
          "--deep",
          "--sign",
          "-",
          macReleaseAppBundle.absolutePath,
      )
    }

tasks
    .matching { it.name == "packageReleaseDmg" }
    .configureEach { dependsOn(signTrimmedMacReleaseBundle) }

val macAppBundle = layout.buildDirectory.dir("$packageOutputPath/main/app/BUCC.app").get().asFile
val macInfoPlist = macAppBundle.resolve("Contents/Info.plist")
val macBundleVersion = project.property("project.version.code").toString()
val setMacBundleVersion by
    tasks.registering(Exec::class) {
      dependsOn("createDistributable")
      commandLine(
          "/usr/libexec/PlistBuddy",
          "-c",
          "Set :CFBundleVersion $macBundleVersion",
          macInfoPlist.absolutePath,
      )
    }
val setMacReleaseBundleVersion by
    tasks.registering(Exec::class) {
      dependsOn("createReleaseDistributable")
      commandLine(
          "/usr/libexec/PlistBuddy",
          "-c",
          "Set :CFBundleVersion $macBundleVersion",
          macReleaseAppBundle.resolve("Contents/Info.plist").absolutePath,
      )
    }

if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
  signTrimmedMacReleaseBundle.configure { dependsOn(setMacReleaseBundleVersion) }
}

val signMacBundleAfterVersionUpdate by
    tasks.registering(Exec::class) {
      dependsOn(setMacBundleVersion)
      commandLine(
          "/usr/bin/codesign",
          "--force",
          "--deep",
          "--sign",
          "-",
          macAppBundle.absolutePath,
      )
    }

tasks
    .matching { it.name in setOf("packageDmg", "packageReleaseDmg") }
    .configureEach { dependsOn(signMacBundleAfterVersionUpdate) }

val bundleTdBackend by
    tasks.registering(Exec::class) {
      val tdPackagingOutput = layout.buildDirectory.dir("td-packaging")
      inputs.property("tdArchitecture", tdPackagingArch)
      inputs.dir("src/jvmMain/resources/td-backend")
      inputs.file(rootProject.file("script/package_td_backend.sh"))
      inputs.file(rootProject.file("script/package_td_backend_windows.py"))
      outputs.dir(layout.buildDirectory.dir("td-packaging/resources"))
      environment("BUCC_TD_ARCH", tdPackagingArch.get())
      environment("BUCC_TD_PACKAGING_OUTPUT", tdPackagingOutput.get().asFile.absolutePath)
      if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        commandLine(
            providers.environmentVariable("BUCC_TD_PACKAGING_PYTHON").getOrElse("python"),
            rootProject.file("script/package_td_backend_windows.py").absolutePath,
        )
      } else {
        commandLine("bash", rootProject.file("script/package_td_backend.sh").absolutePath)
      }
    }

if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
  tasks
      .matching {
        it.name in
            setOf(
                "createDistributable",
                "createReleaseDistributable",
                "prepareAppResources",
                "packageMsi",
                "packageExe",
                "packageReleaseMsi",
                "packageReleaseExe",
            )
      }
      .configureEach { dependsOn(bundleTdBackend) }
}

if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
  val nativeLibrary = layout.buildDirectory.file("native/libbucc-native.dylib")
  val paymentWebviewSources = file("src/jvmMain/resources/native/payment-webview")
  val paymentWebviewObject = layout.buildDirectory.file("native/payment-webview.o")
  val compilePaymentWebview by
      tasks.registering(Exec::class) {
        inputs.dir(paymentWebviewSources)
        inputs.property("architecture", tdPackagingArch)
        outputs.file(paymentWebviewObject)
        doFirst { paymentWebviewObject.get().asFile.parentFile.mkdirs() }
        val arch = if (tdPackagingArch.get() == "x86_64") "x86_64" else "arm64"
        val wrapper = paymentWebviewSources.resolve("PaymentWebview.cpp")
        commandLine(
            listOf(
                "xcrun",
                "clang++",
                "-std=c++11",
                "-x",
                "objective-c++",
                "-target",
                "$arch-apple-macos11",
                "-I",
                paymentWebviewSources.absolutePath,
                "-c",
                wrapper.absolutePath,
                "-o",
                paymentWebviewObject.get().asFile.absolutePath,
            )
        )
      }
  val compileBuccNative by
      tasks.registering(Exec::class) {
        dependsOn(bundleTdBackend, compilePaymentWebview)
        val sources = fileTree("src/jvmMain/resources/native") { include("*.swift") }
        inputs.files(sources)
        inputs.dir(paymentWebviewSources)
        inputs.file(paymentWebviewObject)
        inputs.property("architecture", tdPackagingArch)
        outputs.file(nativeLibrary)
        doFirst { nativeLibrary.get().asFile.parentFile.mkdirs() }
        val arch = if (tdPackagingArch.get() == "x86_64") "x86_64" else "arm64"
        commandLine(
            listOf("xcrun", "swiftc", "-emit-library", "-target", "$arch-apple-macos11") +
                sources.files.sortedBy { it.name }.map { it.absolutePath } +
                listOf(
                    paymentWebviewObject.get().asFile.absolutePath,
                    "-lc++",
                    "-lobjc",
                    "-framework",
                    "WebKit",
                    "-framework",
                    "Cocoa",
                    "-framework",
                    "EventKit",
                    "-o",
                    nativeLibrary.get().asFile.absolutePath,
                )
        )
      }
  val copyBuccNative by
      tasks.registering(Copy::class) {
        dependsOn(compileBuccNative)
        from(nativeLibrary)
        into(tdPackagingResources.map { it.dir("common/native") })
      }
  tasks
      .matching {
        it.name in
            setOf(
                "createDistributable",
                "createReleaseDistributable",
                "prepareAppResources",
                "packageDmg",
                "packageReleaseDmg",
            )
      }
      .configureEach { dependsOn(copyBuccNative) }
  // Runtime tests must exercise the native bridge built from this checkout, not an older dylib.
  tasks.withType<Test>().configureEach { dependsOn(copyBuccNative) }
  tasks
      .matching { it.name in setOf("createDistributable", "createReleaseDistributable") }
      .configureEach {
        doLast {
          outputs.files.asFileTree
              .matching { include("**/td-backend/AutoTDBackend") }
              .files
              .forEach { check(it.setExecutable(true, false)) { "无法设置 AutoTD 后端的执行权限：$it" } }
        }
      }
}

// Native UI tests need the same JDK window access as the packaged macOS launcher.
tasks.withType<Test>().configureEach {
  if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
    jvmArgs(
        "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
  }
}

// The app owns this credential-free campus helper; it never launches or configures a VPN client.
val campusProxyGoos =
    when {
      System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "darwin"
      System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
      System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
      else ->
          throw GradleException("Unsupported campus proxy host: ${System.getProperty("os.name")}")
    }
val campusProxyGoarch =
    when (tdPackagingArch.get()) {
      "x86_64" -> "amd64"
      "arm64" -> "arm64"
      else ->
          throw GradleException("Unsupported campus proxy architecture: ${tdPackagingArch.get()}")
    }
val campusProxyName =
    if (campusProxyGoos == "windows") "bucc-campus-proxy.exe" else "bucc-campus-proxy"
val campusProxyOutput =
    rootProject.layout.buildDirectory.file("campus-proxy/desktop/$campusProxyName")
val buildPhysicalCampusProxy by
    tasks.registering(Exec::class) {
      inputs.dir(rootProject.file("third_party/bucc-physical-proxy"))
      inputs.file(rootProject.file("script/build_campus_proxy.sh"))
      inputs.property("goos", campusProxyGoos)
      inputs.property("goarch", campusProxyGoarch)
      outputs.file(campusProxyOutput)
      doFirst {
        val outputDirectory = campusProxyOutput.get().asFile.parentFile
        outputDirectory.mkdirs()
        listOf("bucc-campus-proxy", "bucc-campus-proxy.exe").forEach { name ->
          outputDirectory.resolve(name).delete()
        }
      }
      environment("GOOS", campusProxyGoos)
      environment("GOARCH", campusProxyGoarch)
      workingDir(rootProject.projectDir)
      commandLine(
          providers.environmentVariable("BUCC_BASH").getOrElse("bash"),
          rootProject.file("script/build_campus_proxy.sh").absolutePath,
          "desktop",
      )
    }
val clearObsoleteCampusResources by
    tasks.registering(Delete::class) {
      delete(
          listOf("campus-core", "campus-system-proxy").map { name ->
            tdPackagingResources.get().dir("common/$name").asFile
          }
      )
    }
val bundlePhysicalCampusProxy by
    tasks.registering(Copy::class) {
      dependsOn(buildPhysicalCampusProxy, clearObsoleteCampusResources)
      from(rootProject.layout.buildDirectory.dir("campus-proxy/desktop"))
      from(rootProject.file("third_party/bucc-physical-proxy/LICENSE")) {
        into("notices/bucc-physical-proxy")
      }
      from(rootProject.file("third_party/bucc-physical-proxy/BUCC-INTEGRATION.md")) {
        into("notices/bucc-physical-proxy")
      }
      from(rootProject.file("third_party/bucc-physical-proxy")) {
        include("**/*.go", "go.mod", "go.sum")
        into("notices/bucc-physical-proxy/source")
      }
      from(rootProject.file("third_party/NOTICE.md")) { into("notices") }
      into(tdPackagingResources.map { it.dir("common/campus-proxy") })
    }

tasks
    .matching {
      it.name in
          setOf(
              "createDistributable",
              "createReleaseDistributable",
              "prepareAppResources",
              "packageDmg",
              "packageReleaseDmg",
          )
    }
    .configureEach { dependsOn(bundlePhysicalCampusProxy) }

if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
  // jpackage normalizes files in app/resources to 0644, including executable helpers.
  val packagedCampusDirectory =
      layout.buildDirectory.dir(
          providers.gradleProperty("bucc.packageOutput").getOrElse("compose/binaries") +
              "/main/app/BUCC.app/Contents/app/resources/campus-proxy"
      )
  val prepareCampusAppExecutables by
      tasks.registering(Exec::class) {
        dependsOn("createDistributable")
        commandLine(
            "chmod",
            "0755",
            packagedCampusDirectory.get().file("bucc-campus-proxy").asFile.absolutePath,
        )
      }
  tasks
      .matching { it.name == "packageDmg" }
      .configureEach { dependsOn(prepareCampusAppExecutables) }
}
