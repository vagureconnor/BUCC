import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class IOSArchiveContractTests(unittest.TestCase):
    def test_ci_and_release_workflows_do_not_build_apple_artifacts(self):
        for workflow_path in (".github/workflows/test.yml", ".github/workflows/release.yml"):
            workflow = (ROOT / workflow_path).read_text(encoding="utf-8")
            self.assertNotIn("xcodebuild", workflow)
            self.assertNotIn("packageReleaseDmg", workflow)
            self.assertNotIn("package_unsigned_ipa.py", workflow)

    def test_local_ios_build_archives_and_packages_the_real_device_app_unsigned(self):
        script = (ROOT / "script/build_apple_local.sh").read_text(encoding="utf-8")
        compact = " ".join(script.split())
        self.assertIn("xcodebuild archive", compact)
        self.assertIn("-destination 'generic/platform=iOS'", compact)
        self.assertIn("CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=", compact)
        self.assertIn('package_unsigned_ipa.py "$archive/Products/Applications/BUCC.app"', compact)
        self.assertNotIn("Products/Applications/iosApp.app", script)

    def test_local_macos_build_verifies_the_current_arch_dmg(self):
        script = (ROOT / "script/build_apple_local.sh").read_text(encoding="utf-8")
        self.assertIn('arch="$(uname -m)"', script)
        self.assertIn("packageReleaseDmg", script)
        self.assertIn("codesign --verify --deep --strict", script)
        self.assertIn("hdiutil verify", script)


if __name__ == "__main__":
    unittest.main()
