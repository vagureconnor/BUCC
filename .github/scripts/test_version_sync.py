import json
import tempfile
import unittest
from pathlib import Path

from version_sync import check


class VersionSyncTests(unittest.TestCase):
    def test_detects_platform_version_drift(self):
        source = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("gradle.properties", "harmonyBuild/gradle.properties", "harmonyApp/AppScope/app.json5", "harmonyApp/oh-package.json5", "iosApp/Configuration/Config.xcconfig", "iosApp/iosApp.xcodeproj/project.pbxproj"):
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text((source / name).read_text(encoding="utf-8"), encoding="utf-8")
            app = root / "harmonyApp/AppScope/app.json5"
            content = app.read_text().replace('"versionName": "2.0.1"', '"versionName": "2.0.0"')
            app.write_text(content)
            self.assertTrue(any("versionName" in issue for issue in check(root)))


if __name__ == "__main__":
    unittest.main()
