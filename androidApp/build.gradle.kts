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

  val releaseKeyPath =
      localProperties.getProperty("SIGNING_KEY") ?: System.getenv("SIGNING_KEY")
  val releaseStorePassword =
      localProperties.getProperty("SIGNING_STORE_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
  val releaseKeyAlias =
      localProperties.getProperty("SIGNING_KEY_ALIAS") ?: System.getenv("SIGNING_KEY_ALIAS")
  val releaseKeyPassword =
      localProperties.getProperty("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_KEY_PASSWORD")
  val hasReleaseSigning =
      listOf(releaseKeyPath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
          .all { !it.isNullOrBlank() }
  val hasAnyReleaseSigning =
      listOf(releaseKeyPath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
          .any { !it.isNullOrBlank() }
  val unsignedRelease = providers.gradleProperty("bucc.android.unsignedRelease").orNull == "true"

  val validateReleaseSigning by
      tasks.registering {
        doLast {
          if (unsignedRelease && hasAnyReleaseSigning) {
            throw GradleException("Unsigned Android Release builds must not receive release signing values.")
          }
          if (!unsignedRelease && !hasReleaseSigning) {
            throw GradleException(
                "Release signing is required. Supply SIGNING_KEY, SIGNING_STORE_PASSWORD, " +
                    "SIGNING_KEY_ALIAS and SIGNING_KEY_PASSWORD, or explicitly opt into " +
                    "-Pbucc.android.unsignedRelease=true for CI compilation."
            )
          }
        }
      }

  if (hasReleaseSigning && !unsignedRelease) {
    signingConfigs {
      create("release") {
        storeFile = file(releaseKeyPath!!)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      signingConfig = if (hasReleaseSigning && !unsignedRelease) signingConfigs.getByName("release") else null
    }
  }

  tasks
      .matching { it.name in setOf("packageRelease", "packageReleaseBundle", "bundleRelease", "validateSigningRelease") }
      .configureEach { dependsOn(validateReleaseSigning) }

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
