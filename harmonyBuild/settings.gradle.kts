pluginManagement {
  repositories {
    maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") {
      content { includeVersionByRegex(".*", ".*", ".*-\\d+\\.\\d+\\.\\d+") }
    }
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") {
      content { includeVersionByRegex(".*", ".*", ".*-\\d+\\.\\d+\\.\\d+") }
    }
    mavenCentral()
    google()
  }
}

rootProject.name = "BUCCHarmony"

include(":compilerProbe")

include(":cryptoProbe")

include(":shared")

project(":shared").projectDir = file("../shared")

project(":shared").buildFileName = "build.harmony.gradle.kts"

if (providers.gradleProperty("bucc.harmony.ui").orNull == "true") {
  include(":composeApp")
  project(":composeApp").projectDir = file("../composeApp")
  project(":composeApp").buildFileName = "build.harmony.gradle.kts"
}
