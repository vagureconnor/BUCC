import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.composeMultiplatform)
  alias(libs.plugins.composeCompiler)
}

android {
  namespace = "app.bucc.android"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "app.bucc"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = project.property("project.version.code").toString().toInt()
    versionName = project.property("project.version").toString()
  }

  val localProperties =
      Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
          localPropertiesFile.inputStream().use { load(it) }
        }
      }

  signingConfigs {
    create("release") {
      val keyPath = localProperties.getProperty("SIGNING_KEY") ?: System.getenv("SIGNING_KEY")
      storeFile = keyPath?.let { file(it) }
      storePassword =
          localProperties.getProperty("SIGNING_STORE_PASSWORD")
              ?: System.getenv("SIGNING_STORE_PASSWORD")
      keyAlias =
          localProperties.getProperty("SIGNING_KEY_ALIAS") ?: System.getenv("SIGNING_KEY_ALIAS")
      keyPassword =
          localProperties.getProperty("SIGNING_KEY_PASSWORD")
              ?: System.getenv("SIGNING_KEY_PASSWORD")
    }
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

dependencies {
  implementation(projects.composeApp)
  implementation(projects.shared)
  implementation(libs.androidx.activity.compose)
  implementation(compose.preview)
  debugImplementation(compose.uiTooling)
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.10.3")
  androidTestImplementation("org.jetbrains.compose.material3:material3:1.9.0")
  androidTestImplementation(libs.multiplatform.settings)
  androidTestImplementation(libs.multiplatform.settings.no.arg)
}
