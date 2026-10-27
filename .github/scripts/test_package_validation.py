import plistlib
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from package_validation import PackageError, validate_ios_ipa, validate_macos_dmg, validate_windows_exe


def macho(cpu_type: int, platform: int = 2, code_signature_size: int = 0) -> bytes:
    if code_signature_size:
        header = struct.pack("<IiiIIIII", 0xFEEDFACF, cpu_type, 0, 2, 2, 40, 0, 0)
        build = struct.pack("<IIIIII", 0x32, 24, platform, 0xD0000, 0xD0000, 0)
        signature = struct.pack("<IIII", 0x1D, 16, 0, code_signature_size)
        return header + build + signature
    header = struct.pack("<IiiIIIII", 0xFEEDFACF, cpu_type, 0, 2, 1, 24, 0, 0)
    return header + struct.pack("<IIIIII", 0x32, 24, platform, 0xD0000, 0xD0000, 0)


def app_contents(root: Path, cpu_type: int, native_arches: dict[str, int] | None = None) -> Path:
    app = root / "BUCC.app"
    (app / "Contents" / "MacOS").mkdir(parents=True)
    info = {
        "CFBundleExecutable": "BUCC",
        "CFBundleShortVersionString": "2.0.1",
        "CFBundleVersion": "201",
    }
    (app / "Contents" / "Info.plist").write_bytes(plistlib.dumps(info))
    (app / "Contents" / "MacOS" / "BUCC").write_bytes(macho(cpu_type, platform=1, code_signature_size=24))
    native_arches = native_arches or {}
    resource_root = app / "Contents/app/resources"
    components = {
        "campus proxy": resource_root / "campus-proxy/bucc-campus-proxy",
        "attendance backend": resource_root / "td-backend/AutoTDBackend",
        "BUCC native library": resource_root / "native/libbucc-native.dylib",
        "bundled JVM": app / "Contents/runtime/Contents/Home/lib/server/libjvm.dylib",
        "bundled JVM launcher": app / "Contents/runtime/Contents/Home/lib/libjli.dylib",
    }
    for name, binary_path in components.items():
        binary_path.parent.mkdir(parents=True, exist_ok=True)
        binary_path.write_bytes(macho(native_arches.get(name, cpu_type), platform=1, code_signature_size=24))
    return app


def pe_file(path: Path, machine: int = 0x8664, certificate_size: int = 0) -> None:
    data = bytearray(0x300)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3C, 0x80)
    data[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<H", data, 0x84, machine)
    struct.pack_into("<H", data, 0x94, 0xF0)
    struct.pack_into("<H", data, 0x98, 0x20B)
    struct.pack_into("<I", data, 0x98 + 108, 16)
    struct.pack_into("<I", data, 0x98 + 112 + 4 * 8, 0x240 if certificate_size else 0)
    struct.pack_into("<I", data, 0x98 + 112 + 4 * 8 + 4, certificate_size)
    path.write_bytes(data)


class PackageValidationTests(unittest.TestCase):
    def test_ios_requires_one_arm64_unsigned_app_with_release_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "app.ipa"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("Payload/BUCC.app/Info.plist", plistlib.dumps({
                    "CFBundleExecutable": "BUCC",
                    "CFBundleShortVersionString": "2.0.1",
                    "CFBundleVersion": "201",
                }))
                archive.writestr("Payload/BUCC.app/BUCC", macho(0x0100000C))
            validate_ios_ipa(path, "2.0.1", 201)

            wrong_arch = Path(temporary) / "x86.ipa"
            with zipfile.ZipFile(wrong_arch, "w") as archive:
                archive.writestr("Payload/BUCC.app/Info.plist", plistlib.dumps({
                    "CFBundleExecutable": "BUCC",
                    "CFBundleShortVersionString": "2.0.1",
                    "CFBundleVersion": "201",
                }))
                archive.writestr("Payload/BUCC.app/BUCC", macho(0x01000007))
            with self.assertRaisesRegex(PackageError, "arm64"):
                validate_ios_ipa(wrong_arch, "2.0.1", 201)

            for name, binary, message in (
                ("simulator.ipa", macho(0x0100000C, platform=7), "实体 iOS"),
                ("signed.ipa", macho(0x0100000C, code_signature_size=24), "embedded code signature"),
            ):
                package = Path(temporary) / name
                with zipfile.ZipFile(package, "w") as archive:
                    archive.writestr("Payload/BUCC.app/Info.plist", plistlib.dumps({
                        "CFBundleExecutable": "BUCC",
                        "CFBundleShortVersionString": "2.0.1",
                        "CFBundleVersion": "201",
                    }))
                    archive.writestr("Payload/BUCC.app/BUCC", binary)
                with self.assertRaisesRegex(PackageError, message):
                    validate_ios_ipa(package, "2.0.1", 201)
            malformed = bytearray(macho(0x0100000C))
            struct.pack_into("<I", malformed, 20, 0xFFFFFFFF)
            malformed_ipa = Path(temporary) / "malformed.ipa"
            with zipfile.ZipFile(malformed_ipa, "w") as archive:
                archive.writestr("Payload/BUCC.app/Info.plist", plistlib.dumps({
                    "CFBundleExecutable": "BUCC",
                    "CFBundleShortVersionString": "2.0.1",
                    "CFBundleVersion": "201",
                }))
                archive.writestr("Payload/BUCC.app/BUCC", malformed)
            with self.assertRaisesRegex(PackageError, "load commands"):
                validate_ios_ipa(malformed_ipa, "2.0.1", 201)

    def test_ios_rejects_code_signature_and_embedded_profile(self):
        for forbidden in ("_CodeSignature/CodeResources", "embedded.mobileprovision"):
            with self.subTest(forbidden=forbidden), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "app.ipa"
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("Payload/BUCC.app/Info.plist", plistlib.dumps({
                        "CFBundleExecutable": "BUCC",
                        "CFBundleShortVersionString": "2.0.1",
                        "CFBundleVersion": "201",
                    }))
                    archive.writestr("Payload/BUCC.app/BUCC", macho(0x0100000C))
                    archive.writestr(f"Payload/BUCC.app/{forbidden}", b"signature")
                with self.assertRaisesRegex(PackageError, "签名"):
                    validate_ios_ipa(path, "2.0.1", 201)

    def test_ios_rejects_fat_device_plus_simulator_slices(self):
        arm = macho(0x0100000C, platform=2)
        simulator = macho(0x01000007, platform=7, code_signature_size=24)
        table = struct.pack(">II", 0xCAFEBABE, 2)
        table += struct.pack(">iiIII", 0x0100000C, 0, 48, len(arm), 0)
        table += struct.pack(">iiIII", 0x01000007, 0, 48 + len(arm), len(simulator), 0)
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "mixed.ipa"
            with zipfile.ZipFile(package, "w") as archive:
                archive.writestr("Payload/BUCC.app/Info.plist", plistlib.dumps({
                    "CFBundleExecutable": "BUCC", "CFBundleShortVersionString": "2.0.1", "CFBundleVersion": "201",
                }))
                archive.writestr("Payload/BUCC.app/BUCC", table + arm + simulator)
            with self.assertRaises(PackageError):
                validate_ios_ipa(package, "2.0.1", 201)

    def test_ios_rejects_embedded_signature_in_nested_framework(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "nested.ipa"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("Payload/BUCC.app/Info.plist", plistlib.dumps({
                    "CFBundleExecutable": "BUCC",
                    "CFBundleShortVersionString": "2.0.1",
                    "CFBundleVersion": "201",
                }))
                archive.writestr("Payload/BUCC.app/BUCC", macho(0x0100000C))
                archive.writestr("Payload/BUCC.app/Frameworks/Payment.framework/Payment", macho(0x0100000C, code_signature_size=24))
            with self.assertRaisesRegex(PackageError, "embedded code signature"):
                validate_ios_ipa(path, "2.0.1", 201)

    def test_macos_mounts_readonly_checks_actual_arch_and_adhoc_signature(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dmg = root / "app.dmg"
            dmg.write_bytes(b"not interpreted without hdiutil")

            def fake_run(argv, **kwargs):
                if argv[0] == "hdiutil" and argv[1] == "attach":
                    mount = Path(argv[argv.index("-mountpoint") + 1])
                    app_contents(mount, 0x01000007)
                    return SimpleNamespace(returncode=0, stdout=plistlib.dumps({"system-entities": [{"mount-point": str(mount)}]}), stderr="")
                if argv[0] == "codesign" and argv[1] == "--verify":
                    return SimpleNamespace(returncode=0, stdout="", stderr="")
                if argv[0] == "codesign" and argv[1] == "-dv":
                    return SimpleNamespace(returncode=0, stdout="", stderr="Signature=adhoc\n")
                return SimpleNamespace(returncode=0, stdout="", stderr="")

            with patch("package_validation.subprocess.run", side_effect=fake_run) as run:
                validate_macos_dmg(dmg, "2.0.1", 201, "x86_64")
            attach = run.call_args_list[0].args[0]
            self.assertIn("-readonly", attach)
            with patch("package_validation.subprocess.run", side_effect=fake_run):
                with self.assertRaisesRegex(PackageError, "arm64"):
                    validate_macos_dmg(dmg, "2.0.1", 201, "arm64")

    def test_macos_rejects_arm64_bucc_components_in_x86_64_dmg(self):
        for component in ("campus proxy", "attendance backend", "BUCC native library", "bundled JVM", "bundled JVM launcher"):
            with self.subTest(component=component), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                dmg = root / "app.dmg"
                dmg.write_bytes(b"fixture")

                def fake_run(argv, **kwargs):
                    if argv[0] == "hdiutil" and argv[1] == "attach":
                        mount = Path(argv[argv.index("-mountpoint") + 1])
                        app_contents(mount, 0x01000007, {component: 0x0100000C})
                        return SimpleNamespace(returncode=0, stdout=plistlib.dumps({"system-entities": [{"mount-point": str(mount)}]}), stderr="")
                    if argv[0] == "codesign" and argv[1] == "-dv":
                        return SimpleNamespace(returncode=0, stdout="", stderr="Signature=adhoc\n")
                    return SimpleNamespace(returncode=0, stdout="", stderr="")

                with patch("package_validation.subprocess.run", side_effect=fake_run):
                    with self.assertRaisesRegex(PackageError, component):
                        validate_macos_dmg(dmg, "2.0.1", 201, "x86_64")

    def test_macos_rejects_each_missing_bucc_native_component(self):
        relative_paths = {
            "campus proxy": "Contents/app/resources/campus-proxy/bucc-campus-proxy",
            "attendance backend": "Contents/app/resources/td-backend/AutoTDBackend",
            "BUCC native library": "Contents/app/resources/native/libbucc-native.dylib",
            "bundled JVM": "Contents/runtime/Contents/Home/lib/server/libjvm.dylib",
            "bundled JVM launcher": "Contents/runtime/Contents/Home/lib/libjli.dylib",
        }
        for component, relative_path in relative_paths.items():
            with self.subTest(component=component), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                dmg = root / "app.dmg"
                dmg.write_bytes(b"fixture")

                def fake_run(argv, **kwargs):
                    if argv[0] == "hdiutil" and argv[1] == "attach":
                        mount = Path(argv[argv.index("-mountpoint") + 1])
                        app = app_contents(mount, 0x01000007)
                        (app / relative_path).unlink()
                        return SimpleNamespace(returncode=0, stdout=plistlib.dumps({"system-entities": [{"mount-point": str(mount)}]}), stderr="")
                    return SimpleNamespace(returncode=0, stdout="", stderr="Signature=adhoc\n")

                with patch("package_validation.subprocess.run", side_effect=fake_run):
                    with self.assertRaisesRegex(PackageError, component):
                        validate_macos_dmg(dmg, "2.0.1", 201, "x86_64")

    def test_macos_rejects_non_adhoc_signature_and_wrong_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dmg = root / "app.dmg"
            dmg.write_bytes(b"fixture")
            wrong_version = False

            def fake_run(argv, **kwargs):
                if argv[0] == "hdiutil" and argv[1] == "attach":
                    mount = Path(argv[argv.index("-mountpoint") + 1])
                    app = app_contents(mount, 0x0100000C)
                    if wrong_version:
                        info = plistlib.loads((app / "Contents" / "Info.plist").read_bytes())
                        info["CFBundleVersion"] = "200"
                        (app / "Contents" / "Info.plist").write_bytes(plistlib.dumps(info))
                    return SimpleNamespace(returncode=0, stdout=plistlib.dumps({"system-entities": [{"mount-point": str(mount)}]}), stderr="")
                if argv[0] == "codesign" and argv[1] == "-dv":
                    return SimpleNamespace(returncode=0, stdout="", stderr="Authority=Developer ID Application\n")
                return SimpleNamespace(returncode=0, stdout="", stderr="")

            with patch("package_validation.subprocess.run", side_effect=fake_run):
                with self.assertRaisesRegex(PackageError, "ad-hoc"):
                    validate_macos_dmg(dmg, "2.0.1", 201, "arm64")
            wrong_version = True
            with patch("package_validation.subprocess.run", side_effect=fake_run):
                with self.assertRaisesRegex(PackageError, "版本"):
                    validate_macos_dmg(dmg, "2.0.1", 201, "arm64")

    def test_windows_requires_x64_pe_and_rejects_authenticode_signature_or_false_claim(self):
        with tempfile.TemporaryDirectory() as temporary:
            exe = Path(temporary) / "app.exe"
            pe_file(exe)
            validate_windows_exe(exe)
            with self.assertRaisesRegex(PackageError, "声明"):
                validate_windows_exe(exe, "signed")
            pe_file(exe, machine=0x014C)
            with self.assertRaisesRegex(PackageError, "x64"):
                validate_windows_exe(exe)
            pe_file(exe, certificate_size=16)
            with self.assertRaisesRegex(PackageError, "Authenticode"):
                validate_windows_exe(exe)


if __name__ == "__main__":
    unittest.main()
