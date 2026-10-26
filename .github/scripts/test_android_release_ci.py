from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class AndroidReleaseSigningContractTests(unittest.TestCase):
    def test_unsigned_release_requires_explicit_ci_opt_in_and_no_signing_values(self):
        build = (ROOT / "androidApp/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('gradleProperty("bucc.android.unsignedRelease").orNull == "true"', build)
        self.assertIn("val validateReleaseSigning by", build)
        self.assertIn("doLast {", build)
        self.assertIn("if (unsignedRelease && hasAnyReleaseSigning)", build)
        self.assertIn("if (!unsignedRelease && !hasReleaseSigning)", build)
        hooked_tasks = set(re.findall(r'"(packageRelease|packageReleaseBundle|bundleRelease|validateSigningRelease)"', build))
        self.assertEqual({"packageRelease", "packageReleaseBundle", "bundleRelease", "validateSigningRelease"}, hooked_tasks)
        self.assertIn("signingConfig = if (hasReleaseSigning && !unsignedRelease) signingConfigs.getByName(\"release\") else null", build)
        for key in ("SIGNING_KEY", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD"):
            self.assertIn(key, build)

    def test_ci_is_explicitly_unsigned_and_formal_release_stays_signed(self):
        ci = (ROOT / ".github/workflows/test.yml").read_text(encoding="utf-8")
        release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn(":androidApp:assembleRelease -Pbucc.android.unsignedRelease=true", ci)
        self.assertIn(":shared:jvmTest :composeApp:jvmTest", ci)
        self.assertIn("SIGNING_KEY: ${{ secrets.SIGNING_KEY }}", release)
        self.assertIn("SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}", release)
        self.assertIn("SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}", release)
        self.assertIn("SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}", release)
        self.assertIn("apksigner\" verify --verbose --print-certs", release)
        self.assertNotIn("bucc.android.unsignedRelease", release)

    def test_candidate_workflows_are_manual_signed_or_x64_artifact_only(self):
        android = (ROOT / ".github/workflows/android-release-build.yml").read_text(encoding="utf-8")
        windows = (ROOT / ".github/workflows/windows-build.yml").read_text(encoding="utf-8")
        for workflow in (android, windows):
            self.assertIn("workflow_dispatch:", workflow)
            self.assertNotIn("  push:", workflow)
            self.assertIn("ref: ${{ github.sha }}", workflow)
            self.assertIn("source_sha", workflow)
            self.assertIn("actions/upload-artifact@v4", workflow)
            self.assertNotIn("gh release", workflow)
            self.assertNotIn("softprops/action-gh-release", workflow)
            self.assertNotIn("App Store", workflow)
        for secret in ("SIGNING_KEY", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD"):
            self.assertIn(f"secrets.{secret}", android)
        self.assertIn("apksigner", android)
        self.assertIn("$env:GOOS = 'windows'", windows)
        self.assertIn("$env:GOARCH = 'amd64'", windows)
        self.assertIn("$env:BUCC_BASH", windows)
        self.assertIn("$env:BUCC_GO", windows)


if __name__ == "__main__":
    unittest.main()
