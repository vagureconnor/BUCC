#!/usr/bin/env python3
"""Static contract checks for the Windows build driver, which runs only on the VM."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class WindowsBuildContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = (ROOT / 'script/build_windows_local.ps1').read_text(encoding='utf-8')

    def test_requires_commit_tree_archive_manifest_and_new_output(self):
        for required in ('SourceSha', 'SourceTree', 'SourceArchive', 'ProvenanceFile', 'OutputDirectory',
                         'Refusing to overwrite an existing output directory', 'source_sha', 'source_tree',
                         'archive_sha256'):
            self.assertIn(required, self.script)
        verifier = (ROOT / '.github/scripts/verify_git_archive.py').read_text(encoding='utf-8')
        self.assertIn('blob_sha1', verifier)

    def test_configures_x64_release_toolchain_only_in_process(self):
        for setting in ("$env:JAVA_HOME = $JdkHome", "$env:BUCC_BASH = $BashExe", "$env:BUCC_GO = $GoExe",
                        "$env:BUCC_TD_PACKAGING_PYTHON = $PythonExe", "$env:GOOS = 'windows'",
                        "$env:GOARCH = 'amd64'", "$env:WIX = $WixHome", "SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')"):
            self.assertIn(setting, self.script)
        self.assertIn('working x64 JDK', self.script)
        self.assertIn("'3.14|win-amd64|64'", self.script)

    def test_build_command_and_artifact_inspection_are_pinned(self):
        for required in (':composeApp:packageReleaseExe', '--no-configuration-cache', '--console=plain',
                         '--max-workers=2', '0x8664', 'Get-AuthenticodeSignature', 'SignatureStatus]::NotSigned',
                         'source_archive_sha256', 'source_manifest_sha256', "architecture = 'x86_64'"):
            self.assertIn(required, self.script)
        self.assertIn('Tracked source files changed during the Windows build', self.script)


if __name__ == '__main__':
    unittest.main()
