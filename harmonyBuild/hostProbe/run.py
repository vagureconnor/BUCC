#!/usr/bin/env python3
"""Build isolated ArkWeb or native-host regression HAPs from production sources."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workspace", type=Path, help="New directory; existing files are never overwritten")
    parser.add_argument("--deveco-home", default=os.environ.get("DEVECO_HOME"))
    parser.add_argument("--target", help="Explicit disposable emulator HDC target; omit to build only")
    parser.add_argument("--suite", choices=["web", "host"], default="web")
    args = parser.parse_args()
    if not args.deveco_home:
        parser.error("Set DEVECO_HOME or pass --deveco-home (DevEco Contents directory)")
    workspace = args.workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=False)
    deveco = Path(args.deveco_home).resolve()
    root = Path(__file__).resolve().parents[2]
    app = root / "harmonyApp"
    project = workspace / "project"
    project.mkdir()
    for name in ["AppScope", "build-profile.json5", "hvigorfile.ts", "hvigor",
                 "entry/hvigorfile.ts", "entry/src/main/resources/base"]:
        source, destination = app / name, project / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        if source.is_dir():
            shutil.copytree(source, destination)
        else:
            shutil.copy2(source, destination)
    scope = project / "AppScope/app.json5"
    data = json.loads(scope.read_text())
    bundle = "app.bucc.webfixture" if args.suite == "web" else "app.bucc.hostfixtureallow"
    data["app"]["bundleName"] = bundle
    data["app"]["versionCode"] = 1_000_000
    data["app"]["versionName"] = "1.0.0"
    scope.write_text(json.dumps(data, indent=2))
    for name in ["AppScope/resources/base/element/string.json",
                 "entry/src/main/resources/base/element/string.json"]:
        path = project / name
        values = json.loads(path.read_text())
        for item in values["string"]:
            if item["name"] in ["app_name", "EntryAbility_label"]:
                item["value"] = "BUCC Web Fixture" if args.suite == "web" else "BUCC Host Fixture"
        path.write_text(json.dumps(values, ensure_ascii=False, indent=2))
    (project / "oh-package.json5").write_text(json.dumps({"modelVersion": "5.0.0", "dependencies": {}}))
    (project / "entry/oh-package.json5").write_text(json.dumps({"name": "entry", "version": "1.0.0", "dependencies": {}}))
    (project / "entry/build-profile.json5").write_text(json.dumps({
        "apiType": "stageMode", "buildOption": {}, "targets": [{"name": "default"}]}))
    module = json.loads((app / "entry/src/main/module.json5").read_text())
    if args.suite == "web":
        module["module"]["requestPermissions"] = [{"name": "ohos.permission.INTERNET"}]
    (project / "entry/src/main/module.json5").write_text(json.dumps(module, indent=2))
    profile = project / "entry/src/main/resources/base/profile"
    profile.mkdir(exist_ok=True)
    (profile / "main_pages.json").write_text('{"src":["pages/Index"]}')
    ets = project / "entry/src/main/ets"
    for folder in ["entryability", "platform", "components", "pages"]:
        (ets / folder).mkdir(parents=True)
    (ets / "entryability/EntryAbility.ets").write_text("""import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
export default class EntryAbility extends UIAbility {
  onWindowStageCreate(stage: window.WindowStage): void { stage.loadContent('pages/Index'); }
}
""")
    hashes = {}
    replacements = {
        "platform/PaymentHost.ets": [("import * as nativeApi from 'libentry.so';",
                                      "import * as nativeApi from './FixturePolicy';")],
        "components/PaymentWeb.ets": [
            ("import type webview", "import { fixtureResponse } from '../platform/FixturePolicy';\nimport type webview"),
            ("paymentHost.intercept(this.sessionId, frame.root, event.request))",
             "paymentHost.intercept(this.sessionId, frame.root, event.request) || fixtureResponse(event.request))")]
    }
    if args.suite == "host":
        replacements = {
            "platform/MediaHost.ets": [],
            "platform/CalendarHost.ets": [
                ("'BUCC courses'", "'BUCC synthetic host fixture'"),
                ("'BUCC课程'", "'BUCC合成测试日历'")],
            "platform/NotificationHost.ets": [("'app.bucc.harmony'", repr(bundle))]
        }
    for name, changes in replacements.items():
        original = (app / "entry/src/main/ets" / name).read_bytes()
        hashes[name] = hashlib.sha256(original).hexdigest()
        text = original.decode()
        for before, after in changes:
            if text.count(before) != 1:
                raise RuntimeError(f"Production source changed; review fixture adapter: {name}")
            text = text.replace(before, after)
        (ets / name).write_text(text)
    fixtures = Path(__file__).resolve().parent / "fixtures"
    if args.suite == "web":
        shutil.copy2(fixtures / "FixturePolicy.ets", ets / "platform/FixturePolicy.ets")
    shutil.copy2(fixtures / ("Index.ets" if args.suite == "web" else "NativeHostIndex.ets"), ets / "pages/Index.ets")
    (workspace / "production-source-sha256.json").write_text(json.dumps(hashes, indent=2))
    env = os.environ.copy()
    env.update(DEVECO_HOME=str(deveco), DEVECO_SDK_HOME=str(deveco / "sdk"),
               OHOS_SDK_HOME=str(deveco / "sdk/default/openharmony"), NODE_HOME=str(deveco / "tools/node"))
    env["PATH"] = str(deveco / "tools/node/bin") + os.pathsep + env["PATH"]
    with (workspace / "build.log").open("w") as log:
        subprocess.run([str(deveco / "tools/hvigor/bin/hvigorw"), "assembleHap", "--mode", "module",
                        "-p", "product=default", "-p", "module=entry@default", "-p", "buildMode=debug", "--no-daemon"],
                       cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    hap = project / "entry/build/default/outputs/default/entry-default-unsigned.hap"
    print(hap, flush=True)
    if not args.target:
        return
    hdc = deveco / "sdk/default/openharmony/toolchains/hdc"

    def command(*parts):
        return subprocess.check_output([str(hdc), "-t", args.target, *parts], text=True)

    installed = command("install", "-r", str(hap))
    (workspace / "install.log").write_text(installed)
    if "successfully" not in installed:
        raise RuntimeError("Fixture HAP installation failed")
    command("shell", "aa", "force-stop", bundle)
    (workspace / "start.log").write_text(command("shell", "aa", "start", "-a", "EntryAbility", "-b", bundle))
    if args.suite == "host":
        print("Native host fixture launched. Exercise the labelled controls and system permission dialogs; no test pass is implied.")
        return

    def texts(node):
        yield node.get("attributes", {}).get("text", "")
        for child in node.get("children", []):
            yield from texts(child)

    for _ in range(45):
        layout = command("shell", "uitest", "dumpLayout")
        remote = re.search(r"DumpLayout saved to:(\S+)", layout).group(1)
        destination = workspace / "layout.json"
        command("file", "recv", remote, str(destination))
        results = [text for text in texts(json.loads(destination.read_text()))
                   if text.startswith("PASS ") or text.startswith("FAIL ")]
        if results:
            (workspace / "result.txt").write_text(results[0] + "\n")
            command("shell", "snapshot_display", "-f", "/data/local/tmp/bucc-webfixture.jpeg")
            command("file", "recv", "/data/local/tmp/bucc-webfixture.jpeg", str(workspace / "screen.jpeg"))
            print(results[0])
            if results[0].startswith("FAIL "):
                raise RuntimeError(results[0])
            return
    raise RuntimeError("Fixture did not finish; inspect the dedicated emulator")


if __name__ == "__main__":
    run()
