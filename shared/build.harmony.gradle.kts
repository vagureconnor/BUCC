import java.util.Base64

plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared"))

val nativeProbe = providers.gradleProperty("bucc.harmony.nativeProbe").orNull == "true"

check(!nativeProbe || providers.gradleProperty("bucc.harmony.ui").orNull != "true") {
  "Native component probes and the shipping UI use separate build profiles"
}

val stageAvatarProbeSource by
    tasks.registering(Copy::class) {
      from(
          rootProject.file(
              "../composeApp/src/ohosMain/kotlin/app/bucc/ui/common/util/AvatarCompression.ohos.kt"
          )
      )
      into(layout.buildDirectory.dir("generated/avatarProbe"))
    }

val generateHarmonyVersion by
    tasks.registering {
      val output = layout.buildDirectory.dir("generated/version")
      val appVersion = providers.gradleProperty("project.version")
      inputs.property("version", appVersion)
      outputs.dir(output)
      doLast {
        val version = appVersion.get()
        require(version.matches(Regex("[0-9A-Za-z.+_-]+")))
        output.get().file("app/bucc/BuildKonfig.kt").asFile.apply {
          parentFile.mkdirs()
          writeText(
              "package app.bucc\n\ninternal object BuildKonfig { const val VERSION = \"$version\" }\n"
          )
        }
      }
    }

val generateHarmonyTdImages by
    tasks.registering {
      val source =
          rootProject.layout.projectDirectory.dir(
              "../composeApp/src/jvmMain/resources/td-backend/default_images"
          )
      val output = layout.buildDirectory.dir("generated/tdImages")
      inputs.dir(source)
      outputs.dir(output)
      doLast {
        val images =
            (1..3).joinToString(",\n") { index ->
              val name = "image$index.jpg"
              val bytes = source.file(name).asFile.readBytes()
              check(bytes.isNotEmpty()) { "Bundled TD image is empty" }
              val encoded = Base64.getEncoder().encodeToString(bytes)
              "    \"$name\" to Base64.decode(\"$encoded\")"
            }
        output.get().file("app/bucc/td/HarmonyDefaultTdImages.kt").asFile.apply {
          parentFile.mkdirs()
          writeText(
              "package app.bucc.td\n\nimport kotlin.io.encoding.Base64\n\ninternal object HarmonyDefaultTdImages {\n  val images: Map<String, ByteArray> by lazy { mapOf(\n$images\n  ) }\n}\n"
          )
        }
      }
    }

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
    freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    freeCompilerArgs.add("-opt-in=kotlinx.cinterop.ExperimentalForeignApi")
  }
  ohosArm64 {
    if (providers.gradleProperty("bucc.harmony.nativeProbe").orNull == "true") {
      binaries.sharedLib { baseName = "bucc_shared_probe" }
    }
  }
  sourceSets {
    commonMain {
      if (nativeProbe) kotlin.srcDir(rootProject.file("deviceProbe/src/commonMain/kotlin"))
      kotlin.srcDir(generateHarmonyVersion)
      kotlin.srcDir(generateHarmonyTdImages)
      dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.1-1.0.0")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-1.0.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2-1.0.0")
        listOf("core", "content-negotiation", "logging", "auth").forEach {
          implementation("io.ktor:ktor-client-$it:3.3.3-1.0.0")
        }
        implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3-1.0.0")
        implementation("com.russhwolf:multiplatform-settings:1.3.0-1.0.0")
        implementation("com.russhwolf:multiplatform-settings-no-arg:1.3.0-1.0.0")
      }
    }
    val ohosArm64Main by getting {
      if (providers.gradleProperty("bucc.harmony.nativeProbe").orNull == "true") {
        kotlin.srcDir(rootProject.file("deviceProbe/src/ohosArm64Main/kotlin"))
        kotlin.srcDir(rootProject.file("cryptoProbe/src/ohosArm64Main/kotlin"))
        kotlin.srcDir(stageAvatarProbeSource)
      }
      dependencies { implementation("io.ktor:ktor-client-curl:3.3.3-1.0.0") }
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation("io.ktor:ktor-client-mock:3.3.3-1.0.0")
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2-1.0.0")
      implementation("com.russhwolf:multiplatform-settings-test:1.3.0-1.0.0")
    }
  }
}
