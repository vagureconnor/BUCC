plugins { kotlin("multiplatform") }

kotlin {
  compilerOptions.freeCompilerArgs.add("-opt-in=kotlinx.cinterop.ExperimentalForeignApi")
  ohosArm64 { binaries.sharedLib { baseName = "bucc_crypto_probe" } }
  sourceSets {
    commonMain {
      kotlin.srcDir("../../shared/src/commonMain/kotlin")
      kotlin.include(
          "app/bucc/api/plantform/PlatformAes*.kt",
          "app/bucc/api/plantform/PlatformRsaPkcs1Encrypt.kt",
          "app/bucc/api/plantform/PlatformMd5Hex.kt",
          "app/bucc/api/PlatformRsaPublicKeyDer.kt",
      )
    }
    val ohosArm64Main by getting {
      kotlin.srcDir("../../shared/src/ohosMain/kotlin")
      kotlin.include("app/bucc/api/plantform/PlatformCrypto.ohos.kt", "BuccCryptoProbe.kt")
    }
  }
}
