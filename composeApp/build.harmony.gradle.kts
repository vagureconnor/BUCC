import java.util.zip.ZipFile

plugins {
  kotlin("multiplatform")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("composeApp"))

// The published 1.7.3 icon KLIBs have no OHOS target. Compile only the used,
// Apache-2.0 common source files against the same Compose UI as this target.
val harmonyIconSources by
    configurations.creating {
      isCanBeConsumed = false
      isCanBeResolved = true
    }

dependencies {
  add(
      harmonyIconSources.name,
      "org.jetbrains.compose.material:material-icons-core:1.7.3:sources@jar",
  )
  add(
      harmonyIconSources.name,
      "org.jetbrains.compose.material:material-icons-extended:1.7.3:sources@jar",
  )
}

val generateHarmonyIcons by
    tasks.registering {
      val sources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }
      val output = layout.buildDirectory.dir("generated/materialIcons")
      inputs.files(sources, harmonyIconSources)
      outputs.dir(output)
      doLast {
        val prefix = "commonMain/androidx/compose/material/icons/"
        val selected = mutableSetOf(prefix + "Icons.kt")
        val imports =
            Regex("import androidx\\.compose\\.material\\.icons\\.([A-Za-z][A-Za-z0-9_.]*)")
        val usage =
            Regex(
                "Icons\\.(AutoMirrored\\.)?(Default|Filled|Outlined|Rounded|TwoTone|Sharp)\\.([A-Za-z][A-Za-z0-9_]*)"
            )
        sources.forEach { file ->
          val text = file.readText()
          imports.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (name != "Icons" && !name.endsWith('.'))
                selected += prefix + name.replace('.', '/') + ".kt"
          }
          usage.findAll(text).forEach { match ->
            val auto = if (match.groupValues[1].isEmpty()) "" else "automirrored/"
            val style =
                match.groupValues[2].let { if (it == "Default") "filled" else it.lowercase() }
            selected += "$prefix$auto$style/${match.groupValues[3]}.kt"
          }
        }
        val destination = output.get().asFile
        val found = mutableSetOf<String>()
        harmonyIconSources.files.forEach { archive ->
          ZipFile(archive).use { zip ->
            selected.forEach { name ->
              zip.getEntry(name)?.let { entry ->
                val target = destination.resolve(name.removePrefix("commonMain/"))
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                  target.outputStream().use { input.copyTo(it) }
                }
                found += name
              }
            }
          }
        }
        check(found == selected) { "Missing Material icon common sources: ${selected - found}" }
      }
    }

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
    freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    freeCompilerArgs.add("-opt-in=kotlinx.cinterop.ExperimentalForeignApi")
  }
  ohosArm64 {
    binaries.sharedLib {
      baseName = "bucc_compose"
      export("org.jetbrains.compose.export:export:1.9.2-1.0.0")
    }
  }
  sourceSets {
    commonMain {
      kotlin.srcDir(generateHarmonyIcons)
      kotlin.srcDir(rootProject.file("compat/kotlin"))
    }
    commonMain.dependencies {
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material)
      implementation("org.jetbrains.compose.material3:material3:1.9.2-1.0.0")
      implementation(compose.ui)
      implementation(compose.components.resources)
      implementation(compose.components.uiToolingPreview)
      implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4-1.0.0")
      implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.4-1.0.0")
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2-1.0.0")
      implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-1.0.0")
      implementation("io.coil-kt.coil3:coil-compose:3.3.0-1.0.0")
      implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0-1.0.0")
      implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3-1.0.0")
      implementation("com.russhwolf:multiplatform-settings:1.3.0-1.0.0")
      implementation(project(":shared"))
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2-1.0.0")
      implementation("com.russhwolf:multiplatform-settings-test:1.3.0-1.0.0")
    }
    val ohosArm64Main by getting {
      dependencies { api("org.jetbrains.compose.export:export:1.9.2-1.0.0") }
    }
  }
}

compose.resources { packageOfResClass = "bucc.composeapp.generated.resources" }

val stageHarmonyLibrary by
    tasks.registering(Copy::class) {
      dependsOn("linkReleaseSharedOhosArm64")
      from(layout.buildDirectory.file("bin/ohosArm64/releaseShared/libbucc_compose.so"))
      into(rootProject.file("../harmonyApp/entry/libs/arm64-v8a"))
    }
val stageHarmonyResources by
    tasks.registering(Sync::class) {
      dependsOn(
          "prepareComposeResourcesTaskForCommonMain",
          "convertXmlValueResourcesForCommonMain",
          "copyNonXmlValueResourcesForCommonMain",
      )
      from(
          layout.buildDirectory.dir(
              "generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"
          )
      )
      into(
          rootProject.file(
              "../harmonyApp/entry/src/main/resources/rawfile/composeResources/bucc.composeapp.generated.resources"
          )
      )
    }

tasks.register("prepareHarmonyHost") { dependsOn(stageHarmonyLibrary, stageHarmonyResources) }
