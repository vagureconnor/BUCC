#!/usr/bin/env python3
"""Contracts tying local Mac builders and DMG verification to selected CPU architecture."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MacNativeArchitectureContractTests(unittest.TestCase):
    def test_physical_go_proxy_uses_host_and_target_as_gradle_inputs_and_environment(self):
        build = (ROOT / 'composeApp/build.gradle.kts').read_text(encoding='utf-8')
        for line in (
            'inputs.property("goos", campusProxyGoos)',
            'inputs.property("goarch", campusProxyGoarch)',
            'environment("GOOS", campusProxyGoos)',
            'environment("GOARCH", campusProxyGoarch)',
            '"x86_64" -> "amd64"',
            '"arm64" -> "arm64"',
        ):
            self.assertIn(line, build)
        self.assertIn('bucc-campus-proxy.exe', build)

    def test_apple_builder_runs_native_component_package_validation(self):
        script = (ROOT / 'script/build_apple_local.sh').read_text(encoding='utf-8')
        self.assertIn('validate_macos_dmg', script)
        self.assertIn('"$target_arch"', script)

    def test_validator_checks_only_bucc_native_and_selected_jvm_payloads(self):
        validator = (ROOT / '.github/scripts/package_validation.py').read_text(encoding='utf-8')
        for path in (
            'campus-proxy/bucc-campus-proxy',
            'td-backend/AutoTDBackend',
            'native/libbucc-native.dylib',
            'Contents/runtime/Contents/Home/lib/server/libjvm.dylib',
            'Contents/runtime/Contents/Home/lib/libjli.dylib',
        ):
            self.assertIn(path, validator)
        self.assertIn('_check_macho(contents, architecture)', validator)


if __name__ == '__main__':
    unittest.main()
