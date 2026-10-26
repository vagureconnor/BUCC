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
        self.assertIn(''.join('signingConfig = if (hasReleaseSigning && !unsignedRelease) signingConfigs.getByName("release") else null'.split()), ''.join(build.split()))
        for key in ("SIGNING_KEY", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD"):
            self.assertIn(key, build)

    def test_ci_runs_only_source_contract_without_platform_toolchains(self):
        ci = (ROOT / ".github/workflows/test.yml").read_text(encoding="utf-8")
        self.assertIn("name: Source contract", ci)
        self.assertIn("python3 .github/scripts/version_sync.py --check", ci)
        self.assertIn("python3 -m unittest discover -s .github/scripts -p 'test_*.py'", ci)
        self.assertNotIn("setup-java", ci)
        self.assertNotIn("setup-android", ci)
        self.assertNotIn("gradlew", ci)
        self.assertEqual(1, len(re.findall(r"^  source-contract:$", ci, re.MULTILINE)))

    def test_release_workflow_only_verifies_local_gate_status(self):
        release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", release)
        self.assertIn("Local five platform gate", release)
        self.assertIn("gh api", release)
        self.assertIn("${{ github.sha }}", release)
        self.assertNotIn("ios_only", release)
        self.assertNotIn("gradlew", release)
        self.assertNotIn("setup-java", release)
        self.assertNotIn("SIGNING_KEY", release)
        self.assertNotIn("gh release", release)
        self.assertNotIn("upload-artifact", release)
        self.assertIn("does not build, sign, or publish", release)

    def test_cloud_platform_candidate_workflows_are_removed(self):
        self.assertFalse((ROOT / ".github/workflows/android-release-build.yml").exists())
        self.assertFalse((ROOT / ".github/workflows/windows-build.yml").exists())


if __name__ == "__main__":
    unittest.main()
