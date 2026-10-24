import plistlib
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from script.package_unsigned_ipa import PackageError, package


MACHO = b"\xcf\xfa\xed\xfe" + b"synthetic-arm64-binary-CODESIGNATURE"


class UnsignedIpaPackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.app = self.root / "BUCC.app"
        self.app.mkdir()
        with (self.app / "Info.plist").open("wb") as stream:
            plistlib.dump({"CFBundleExecutable": "BUCC"}, stream)
        (self.app / "BUCC").write_bytes(MACHO)
        (self.app / "compressible-fixture.txt").write_bytes(b"BUCC attendance fixture\n" * 16_384)
        (self.app / "embedded.mobileprovision").write_bytes(b"sensitive-profile-fixture")
        (self.app / "_CodeSignature").mkdir()
        (self.app / "_CodeSignature" / "CodeResources").write_text("signature-metadata")
        frameworks = self.app / "Frameworks" / "Example.framework"
        frameworks.mkdir(parents=True)
        (frameworks / "Example").write_bytes(MACHO)
        (frameworks / "embedded.mobileprovision").write_bytes(b"nested-profile-fixture")
        self.output = self.root / "BUCC.ipa"

    def tearDown(self):
        self.temporary.cleanup()

    def _tool(self, platform=2):
        def run(command, text, capture_output, check):
            if command[0] == "lipo":
                return subprocess.CompletedProcess(command, 0, "arm64", "")
            if command[0] == "otool":
                return subprocess.CompletedProcess(
                    command, 0,
                    f"cmd LC_BUILD_VERSION\n\tplatform {platform}\n\tminos 18.0\n",
                    "",
                )
            if command[:3] == ["codesign", "-d", "--verbose=2"]:
                return subprocess.CompletedProcess(command, 0, "", "")
            if command[:2] == ["codesign", "--remove-signature"]:
                binary = Path(command[-1])
                binary.write_bytes(binary.read_bytes().replace(b"CODESIGNATURE", b""))
                return subprocess.CompletedProcess(command, 0, "", "")
            raise AssertionError("unexpected external command")

        return run

    def test_stages_unsigned_ipa_without_profiles_or_signature_metadata(self):
        original_main = (self.app / "BUCC").read_bytes()
        original_framework = (self.app / "Frameworks/Example.framework/Example").read_bytes()
        with patch("script.package_unsigned_ipa.subprocess.run", side_effect=self._tool()):
            package(self.app, self.output)

        self.assertEqual((self.app / "BUCC").read_bytes(), original_main)
        self.assertEqual((self.app / "Frameworks/Example.framework/Example").read_bytes(), original_framework)
        with zipfile.ZipFile(self.output) as archive:
            names = archive.namelist()
            self.assertTrue(names)
            self.assertTrue(all(name.startswith("Payload/") for name in names))
            self.assertNotIn("package.ipa", names)
            self.assertIn("Payload/BUCC.app/BUCC", names)
            self.assertIn("Payload/BUCC.app/Frameworks/Example.framework/Example", names)
            self.assertFalse(any(name.endswith("embedded.mobileprovision") for name in names))
            self.assertFalse(any("/_CodeSignature/" in name for name in names))
            self.assertNotIn(b"CODESIGNATURE", archive.read("Payload/BUCC.app/BUCC"))
            self.assertNotIn(
                b"CODESIGNATURE",
                archive.read("Payload/BUCC.app/Frameworks/Example.framework/Example"),
            )
            compressed = archive.getinfo("Payload/BUCC.app/compressible-fixture.txt")
            self.assertEqual(compressed.compress_type, zipfile.ZIP_DEFLATED)
            self.assertEqual(compressed.file_size, 24 * 16_384)
            self.assertLess(compressed.compress_size, compressed.file_size // 100)

    def test_refuses_existing_output_without_overwriting(self):
        self.output.write_bytes(b"keep-me")
        with patch("script.package_unsigned_ipa.subprocess.run", side_effect=self._tool()):
            with self.assertRaisesRegex(PackageError, "already exists"):
                package(self.app, self.output)
        self.assertEqual(self.output.read_bytes(), b"keep-me")

    def test_rejects_simulator_build_and_creates_no_output(self):
        with patch("script.package_unsigned_ipa.subprocess.run", side_effect=self._tool(platform=7)):
            with self.assertRaisesRegex(PackageError, "Simulator"):
                package(self.app, self.output)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
