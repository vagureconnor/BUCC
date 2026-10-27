#!/usr/bin/env python3
"""Safety contracts for local Apple and Android release packagers."""
from __future__ import annotations
import importlib.util
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('build_android_local', ROOT / 'script/build_android_local.py')
android = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(android)


class AndroidBuilderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.output = self.root / 'packages'
        self.home = self.root / 'home'
        (self.home / 'Library/Application Support/BUCC/Signing').mkdir(parents=True)
        (self.home / 'Library/Application Support/BUCC/Signing/BUCC-android-release.jks').write_bytes(b'fixture')
        jdk = self.root / 'jdk/bin'
        jdk.mkdir(parents=True)
        (jdk / 'keytool').touch()
        (self.root / 'gradle.properties').write_text('project.version=2.0.1\nproject.version.code=201\n')
        (self.root / '.github/scripts').mkdir(parents=True)
        (self.root / '.github/scripts/version_sync.py').touch()
        apk = self.root / 'androidApp/build/outputs/apk/release/androidApp-release.apk'
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b'fixture apk')
        self.old_root = android.ROOT
        android.ROOT = self.root

    def tearDown(self):
        android.ROOT = self.old_root
        self.temp.cleanup()

    def run_main(self, *, store='private-store-secret', key='private-key-secret', certificate=android.EXPECTED_CERT,
                 dirty=False, build_result=0, missing_store=False):
        def command(args, *, env=None):
            if args[0] == 'git' and args[1] == 'status':
                return ' M source' if dirty else ''
            if args[0] == 'git' and args[1] == 'rev-parse':
                return 'sha\n' if args[-1] == 'HEAD' else 'tree\n'
            if '.github/scripts/version_sync.py' in args:
                return ''
            if args[0].endswith('/keytool'):
                return f' SHA256: {certificate[:2]}:' + ':'.join(certificate[i:i+2] for i in range(2,len(certificate),2))
            if 'apksigner' in args[0]:
                return f'Signer #1 certificate SHA-256 digest: {android.EXPECTED_CERT}'
            raise AssertionError(args)
        def fake_run(args, **kwargs):
            if args[0] == 'security':
                if missing_store:
                    return subprocess.CompletedProcess(args, 1, '', '')
                return subprocess.CompletedProcess(args, 0, store + '\n', '')
            if any('assembleRelease' in arg for arg in args):
                return subprocess.CompletedProcess(args, build_result, 'failure detail ' + store + '\n' if build_result else 'BUILD SUCCESSFUL\n')
            raise AssertionError(args)
        env = {'JAVA_HOME': str(self.root / 'jdk'), 'ANDROID_HOME': str(self.root / 'android-sdk')}
        def fake_keychain(service, required=True):
            if missing_store and service == 'app.bucc.android.release':
                raise RuntimeError('Missing or inaccessible local Keychain item: ' + service)
            return key if service.endswith('Key') else store
        with patch.object(android, 'ROOT', self.root), patch.object(android.Path, 'home', return_value=self.home), \
             patch.object(android, 'command', side_effect=command), patch.object(android, 'keychain', side_effect=fake_keychain), \
             patch.object(android.subprocess, 'run', side_effect=fake_run), patch.dict(os.environ, env, clear=True), patch('sys.argv', ['build_android_local.py', str(self.output)]):
            android.main()

    def test_missing_password_blocks_without_writing_output(self):
        with self.assertRaisesRegex(RuntimeError, 'Keychain'):
            self.run_main(missing_store=True)
        self.assertFalse(self.output.exists())

    def test_wrong_approved_certificate_blocks_before_build(self):
        with self.assertRaisesRegex(RuntimeError, 'differs from the user-approved BUCC identity'):
            self.run_main(certificate='0' * 64)
        self.assertFalse(self.output.exists())

    def test_dirty_source_blocks(self):
        with self.assertRaisesRegex(RuntimeError, 'Commit source changes'):
            self.run_main(dirty=True)
        self.assertFalse(self.output.exists())

    def test_build_failure_sanitizes_secrets_from_log_and_exception(self):
        with self.assertRaisesRegex(RuntimeError, 'sanitized build.log'):
            self.run_main(build_result=1)
        log = (self.output / 'build.log').read_text()
        self.assertNotIn('private-store-secret', log)
        self.assertNotIn('private-key-secret', log)


class AppleBuilderTests(unittest.TestCase):
    def test_script_requires_clean_source_before_any_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Path(temporary)
            (fixture / 'script').mkdir()
            shutil.copy2(ROOT / 'script/build_apple_local.sh', fixture / 'script/build_apple_local.sh')
            subprocess.run(['git', 'init', '-q', str(fixture)], check=True, capture_output=True)
            # Use an isolated dirty repo and a fake host identity on every OS.
            # Never invoke an Apple build from a unit test against the real checkout.
            binaries = fixture / 'bin'
            binaries.mkdir()
            (binaries / 'uname').write_text('#!/bin/sh\necho Darwin\n')
            (binaries / 'uname').chmod(0o755)
            marker = fixture / 'unexpected-build'
            (binaries / 'xcodebuild').write_text('#!/bin/sh\ntouch "' + str(marker) + '"\nexit 1\n')
            (binaries / 'xcodebuild').chmod(0o755)
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ.get('PATH', ''))
            result = subprocess.run(['bash', str(fixture / 'script/build_apple_local.sh'), 'ios', str(fixture / 'output')],
                                    cwd=fixture, env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Commit source changes', result.stderr)
            self.assertFalse(marker.exists())
            self.assertFalse((fixture / 'output').exists())

    def test_apple_script_supports_rosetta_architecture_and_checks_target_runtimes(self):
        script = (ROOT / 'script/build_apple_local.sh').read_text()
        self.assertIn('macos-x86_64', script)
        self.assertIn('-derivedDataPath "$output_dir/DerivedData"', script)
        self.assertNotIn('build/ios-local-derived', script)
        self.assertIn('arch -x86_64', script)
        self.assertIn('Target JDK architecture mismatch', script)
        self.assertIn('Target Python architecture mismatch', script)
        self.assertIn('hdiutil verify', script)
        self.assertIn('validate_macos_dmg', script)
        self.assertIn('codesign --verify --deep --strict "$packaged_app"', script)


if __name__ == '__main__':
    unittest.main()
