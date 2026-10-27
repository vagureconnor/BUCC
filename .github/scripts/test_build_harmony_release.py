import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class HarmonyBuildScriptContractTests(unittest.TestCase):
    def test_uses_only_installed_deveco_sdk_and_bundled_toolchain(self):
        script = (ROOT / "script/build_harmony_release.sh").read_text(encoding="utf-8")
        self.assertIn('DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-/Applications/DevEco-Studio.app/Contents/sdk}"', script)
        self.assertIn('OHOS_SDK_HOME="${OHOS_SDK_HOME:-$DEVECO_SDK_HOME/default/openharmony}"', script)
        self.assertIn('node_dir="$deveco_contents/tools/node/bin"', script)
        self.assertIn('hvigor_command="$deveco_contents/tools/hvigor/bin/hvigorw"', script)
        self.assertIn('ohpm_command="$deveco_contents/tools/ohpm/bin/ohpm"', script)
        self.assertIn('export PATH="$node_dir:$deveco_contents/tools/ohpm/bin:$deveco_contents/tools/hvigor/bin:$PATH"', script)
        self.assertIn('"$hvigor_command" --no-daemon assembleHap', script)
        self.assertNotIn("sdkmanager", script)
        self.assertNotIn("curl ", script)
        self.assertNotIn("DEVECO_HOME", script)

    def test_build_is_bound_to_clean_sha_tree_and_fresh_release_native_library(self):
        script = (ROOT / "script/build_harmony_release.sh").read_text(encoding="utf-8")
        self.assertRegex(script, r"git -C \"\$repo_root\" status --porcelain")
        self.assertIn('git -C "$repo_root" rev-parse HEAD', script)
        self.assertIn("'HEAD^{tree}'", script)
        self.assertIn('shared_lib="$harmony_root/entry/libs/arm64-v8a/libbucc_compose.so"', script)
        self.assertIn('rm -f "$shared_lib"', script)
        self.assertIn('if [[ ! -s "$shared_lib" ]]; then', script)
        self.assertIn('shared_lib_sha_before=', script)
        self.assertIn('shared_lib_sha_after=', script)
        self.assertIn("'HEAD^{tree}'", script)
        self.assertIn('source_tree', script)
        self.assertIn('source_tree', script[script.index("python3 - "):])

    def test_cleans_only_entry_build_and_selects_exact_unsigned_release_hap(self):
        script = (ROOT / "script/build_harmony_release.sh").read_text(encoding="utf-8")
        self.assertIn('rm -rf "$harmony_root/entry/build"', script)
        self.assertIn('hap="$harmony_root/entry/build/default/outputs/default/entry-default-unsigned.hap"', script)
        self.assertNotRegex(script, r"find .*\.hap")
        self.assertIn('if [[ -e "$output_dir" ]]; then', script)
        self.assertIn("sha256", script)
        self.assertIn('source_sha', script)
        self.assertIn('source_tree', script)


if __name__ == "__main__":
    unittest.main()
