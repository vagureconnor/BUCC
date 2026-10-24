import com.codingfeline.buildkonfig.compiler.FieldSpec

plugins {
  // 引入 KMP、Android 库、序列化及 BuildKonfig 插件
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKotlinMultiplatformLibrary)
  alias(libs.plugins.kotlinSerialization)
  alias(libs.plugins.buildkonfig)
}

// BuildKonfig 配置：生成客户端版本常量
buildkonfig {
  packageName = "app.bucc"
  objectName = "BuildKonfig"

  defaultConfigs {
    buildConfigField(
        FieldSpec.Type.STRING,
        "VERSION",
        project.property("project.version").toString(),
    )
  }
}

tasks.withType<Test>().configureEach {
  systemProperty(
      "java.util.prefs.PreferencesFactory",
      "app.bucc.testsupport.InMemoryPreferencesFactory",
  )
  val sample =
      providers
          .gradleProperty("graduateScheduleSample")
          .orElse(providers.environmentVariable("BUCC_GRADUATE_SCHEDULE_SAMPLE"))
  if (sample.isPresent) {
    inputs.file(sample).withPropertyName("graduateScheduleSample")
    environment("BUCC_GRADUATE_SCHEDULE_SAMPLE", sample.get())
  }
  val gsmisSample =
      providers
          .gradleProperty("gsmisScheduleSampleDir")
          .orElse(providers.environmentVariable("BUCC_GSMIS_SCHEDULE_SAMPLE_DIR"))
  if (gsmisSample.isPresent) {
    inputs.dir(gsmisSample).withPropertyName("gsmisScheduleSampleDir")
    systemProperty("gsmisSampleDir", gsmisSample.get())
  }
}

kotlin {
  // 配置 JDK 21 工具链
  jvmToolchain(21)
  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
    freeCompilerArgs.add("-opt-in=kotlinx.cinterop.ExperimentalForeignApi")
  }

  android {
    namespace = "app.bucc.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()
    androidResources.enable = true
  }

  // 配置 iOS 目标 (Arm64 及 模拟器)
  iosArm64()
  iosSimulatorArm64()

  // 配置 JVM (Desktop) 目标
  jvm()

  sourceSets {
    jvmTest { kotlin.srcDir(rootProject.file("test-support/kotlin")) }
    // 公共源码集依赖
    commonMain.dependencies {
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.coroutinesCore)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)
      implementation(libs.ktor.client.logging)
      implementation(libs.ktor.client.auth)
      implementation(libs.multiplatform.settings)
      implementation(libs.multiplatform.settings.no.arg)
    }

    // 平台特定引擎实现
    androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
    iosMain.dependencies { implementation(libs.ktor.client.darwin) }
    jvmMain.dependencies {
      implementation(libs.ktor.client.cio)
      implementation(libs.ktor.client.okhttp)
      implementation("net.java.dev.jna:jna:5.19.1")
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.ktor.client.mock)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.multiplatform.settings.test)
    }
  }
}
