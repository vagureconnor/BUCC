import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class IOSArchiveContractTests(unittest.TestCase):
    def test_unsigned_ci_archive_packages_the_real_bucc_product(self):
        workflow = (ROOT / ".github/workflows/test.yml").read_text(encoding="utf-8")
        self.assertIn('Products/Applications/BUCC.app', workflow)
        self.assertIn('python3 script/package_unsigned_ipa.py "$app"', workflow)
        self.assertNotIn("Products/Applications/iosApp.app", workflow)

    def test_release_archive_packages_the_real_bucc_product(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn('Products/Applications/BUCC.app', workflow)
        self.assertIn('script/package_unsigned_ipa.py "$RUNNER_TEMP/BUCC-unsigned.xcarchive/Products/Applications/BUCC.app"', workflow)
        self.assertNotIn("Products/Applications/iosApp.app", workflow)


if __name__ == "__main__":
    unittest.main()
