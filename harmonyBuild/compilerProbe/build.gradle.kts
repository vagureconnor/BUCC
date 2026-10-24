plugins { kotlin("multiplatform") }

kotlin { ohosArm64 { binaries.sharedLib { baseName = "bucc_probe" } } }
