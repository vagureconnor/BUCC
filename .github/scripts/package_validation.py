"""Inspect packaged desktop and Apple release artifacts rather than trusting names."""
from __future__ import annotations

import os
import plistlib
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


class PackageError(ValueError):
    pass


CPU_TYPES = {"arm64": 0x0100000C, "x86_64": 0x01000007}


def _release_info(info: object, version: str, version_code: int) -> str:
    if not isinstance(info, dict):
        raise PackageError("应用 Info.plist 格式无效")
    if info.get("CFBundleShortVersionString") != version or str(info.get("CFBundleVersion")) != str(version_code):
        raise PackageError("应用包版本或版本号不匹配")
    executable = info.get("CFBundleExecutable")
    if not isinstance(executable, str) or not executable or PurePosixPath(executable).name != executable:
        raise PackageError("应用主可执行文件路径无效")
    return executable


def _parse_info(data: bytes, version: str, version_code: int) -> str:
    try:
        return _release_info(plistlib.loads(data), version, version_code)
    except (plistlib.InvalidFileException, ValueError, TypeError) as error:
        if isinstance(error, PackageError):
            raise
        raise PackageError("应用 Info.plist 无法读取") from None


def _macho_slices(data: bytes) -> list[tuple[int, bytes]]:
    if len(data) < 8:
        raise PackageError("主程序不是有效 Mach-O")
    magic = data[:4]
    thin = {
        b"\xcf\xfa\xed\xfe": "<",  # MH_MAGIC_64, little endian
        b"\xfe\xed\xfa\xcf": ">",  # MH_CIGAM_64, big endian
    }
    if magic in thin:
        _validate_macho_load_commands(data, thin[magic])
        return [(struct.unpack_from(thin[magic] + "i", data, 4)[0], data)]
    fat = {
        b"\xca\xfe\xba\xbe": (">", 20),
        b"\xbe\xba\xfe\xca": ("<", 20),
        b"\xca\xfe\xba\xbf": (">", 32),
        b"\xbf\xba\xfe\xca": ("<", 32),
    }
    if magic not in fat:
        raise PackageError("主程序不是有效 64 位 Mach-O")
    endian, record_size = fat[magic]
    count = struct.unpack_from(endian + "I", data, 4)[0]
    if count < 1 or count > 32 or len(data) < 8 + count * record_size:
        raise PackageError("Fat Mach-O 架构表无效")
    slices = []
    for index in range(count):
        entry = 8 + index * record_size
        cpu_type = struct.unpack_from(endian + "i", data, entry)[0]
        if record_size == 20:
            offset, size = struct.unpack_from(endian + "II", data, entry + 8)
        else:
            offset, size = struct.unpack_from(endian + "QQ", data, entry + 8)
        if size < 32 or offset + size > len(data):
            raise PackageError("Fat Mach-O slice 范围无效")
        image = data[offset:offset + size]
        if image[:4] not in thin:
            raise PackageError("Fat Mach-O 包含非 64 位 slice")
        _validate_macho_load_commands(image, thin[image[:4]])
        if struct.unpack_from(thin[image[:4]] + "i", image, 4)[0] != cpu_type:
            raise PackageError("Fat Mach-O 架构表与 slice CPU 类型不匹配")
        slices.append((cpu_type, image))
    return slices


def _validate_macho_load_commands(data: bytes, endian: str) -> None:
    if len(data) < 32:
        raise PackageError("Mach-O 64 位头部被截断")
    ncmds, sizeofcmds = struct.unpack_from(endian + "II", data, 16)
    command_end = 32 + sizeofcmds
    if command_end > len(data):
        raise PackageError("Mach-O load commands 越过 slice 边界")
    cursor = 32
    for _ in range(ncmds):
        if cursor + 8 > command_end:
            raise PackageError("Mach-O load command header 被截断")
        command, command_size = struct.unpack_from(endian + "II", data, cursor)
        if command_size < 8 or cursor + command_size > command_end:
            raise PackageError("Mach-O load command 大小越界")
        if (command & 0x7FFFFFFF) == 0x1D and command_size >= 16:
            signature_size = struct.unpack_from(endian + "I", data, cursor + 12)[0]
            if signature_size:
                raise PackageError("iOS IPA Mach-O 含有 embedded code signature")
        cursor += command_size
    if cursor != command_end:
        raise PackageError("Mach-O load command 区域长度不匹配")


def _macho_architectures(data: bytes) -> set[int]:
    return {cpu_type for cpu_type, _ in _macho_slices(data)}


def _check_macho(data: bytes, architecture: str) -> None:
    expected = CPU_TYPES[architecture]
    if expected not in _macho_architectures(data):
        raise PackageError(f"应用主程序不包含要求的 {architecture} Mach-O 架构")


def _check_ios_macho(data: bytes) -> None:
    arm_slices = [image for cpu_type, image in _macho_slices(data) if cpu_type == CPU_TYPES["arm64"]]
    if not arm_slices:
        raise PackageError("iOS Mach-O 不包含 arm64 架构")
    for image in arm_slices:
        magic = image[:4]
        endian = "<" if magic in {b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe"} else ">"
        ncmds, sizeofcmds = struct.unpack_from(endian + "II", image, 16)
        command_end = 32 + sizeofcmds
        cursor = 32
        platform = None
        for _ in range(ncmds):
            command, command_size = struct.unpack_from(endian + "II", image, cursor)
            base_command = command & 0x7FFFFFFF
            if base_command == 0x32 and command_size >= 24:  # LC_BUILD_VERSION
                platform = struct.unpack_from(endian + "I", image, cursor + 8)[0]
            elif base_command == 0x25 and command_size >= 16:  # LC_VERSION_MIN_IPHONEOS
                platform = 2
            cursor += command_size
        if cursor != command_end or platform != 2:
            raise PackageError("IPA arm64 Mach-O 必须是实体 iOS 构建，不接受 simulator 或未标注 platform")


def validate_ios_ipa(path: Path, version: str, version_code: int) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            app_roots = sorted({
                PurePosixPath(name).parts[1]
                for name in names
                if len(PurePosixPath(name).parts) >= 3
                and PurePosixPath(name).parts[0] == "Payload"
                and PurePosixPath(name).parts[1].endswith(".app")
            })
            if len(app_roots) != 1:
                raise PackageError("iOS IPA 必须只包含一个 Payload 应用")
            app_prefix = f"Payload/{app_roots[0]}/"
            app_files = [name for name in names if name.startswith(app_prefix)]
            if any("_CodeSignature" in PurePosixPath(name).parts or PurePosixPath(name).name == "embedded.mobileprovision" for name in app_files):
                raise PackageError("iOS IPA 必须保持未签名，不得包含 provisioning profile")
            info_name = app_prefix + "Info.plist"
            executable = _parse_info(archive.read(info_name), version, version_code)
            binary = archive.read(app_prefix + executable)
            _check_ios_macho(binary)
            for name in app_files:
                if name.endswith("/") or name in {info_name, app_prefix + executable}:
                    continue
                contents = archive.read(name)
                if contents[:4] in {
                    b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf",
                    b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca",
                    b"\xca\xfe\xba\xbf", b"\xbf\xba\xfe\xca",
                }:
                    _check_ios_macho(contents)
    except (OSError, zipfile.BadZipFile, KeyError, RuntimeError) as error:
        if isinstance(error, PackageError):
            raise
        raise PackageError("iOS IPA 内容结构无效") from None


def _run(command: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(command, check=False, capture_output=True, text=True)
    except OSError:
        raise PackageError(f"无法运行本机验证命令：{Path(command[0]).name}") from None
    if result.returncode:
        raise PackageError(f"本机验证命令失败：{Path(command[0]).name}")
    return result


def _validate_macos_native_components(app: Path, architecture: str) -> None:
    resource_root = app / "Contents/app/resources"
    components = (
        ("campus proxy", resource_root / "campus-proxy/bucc-campus-proxy"),
        ("attendance backend", resource_root / "td-backend/AutoTDBackend"),
        ("BUCC native library", resource_root / "native/libbucc-native.dylib"),
        ("bundled JVM", app / "Contents/runtime/Contents/Home/lib/server/libjvm.dylib"),
        ("bundled JVM launcher", app / "Contents/runtime/Contents/Home/lib/libjli.dylib"),
    )
    for label, binary in components:
        try:
            contents = binary.read_bytes()
        except OSError:
            raise PackageError(f"macOS DMG 缺少 {label} 本机组件") from None
        try:
            _check_macho(contents, architecture)
        except PackageError:
            raise PackageError(f"macOS DMG 的 {label} 不包含要求的 {architecture} Mach-O 架构") from None


def validate_macos_dmg(path: Path, version: str, version_code: int, architecture: str) -> None:
    if architecture not in CPU_TYPES:
        raise PackageError("macOS DMG 架构配置无效")
    with tempfile.TemporaryDirectory(prefix="bucc-dmg-verify-") as temporary:
        mountpoint = Path(temporary) / "mount"
        mountpoint.mkdir()
        attach = _run(["hdiutil", "attach", "-readonly", "-nobrowse", "-plist", "-mountpoint", str(mountpoint), str(path)])
        try:
            try:
                attach_data = attach.stdout if isinstance(attach.stdout, bytes) else attach.stdout.encode()
                details = plistlib.loads(attach_data)
            except (plistlib.InvalidFileException, ValueError):
                raise PackageError("hdiutil 未返回可解析的挂载信息") from None
            mounts = [
                Path(item["mount-point"])
                for item in details.get("system-entities", [])
                if isinstance(item, dict) and isinstance(item.get("mount-point"), str)
            ]
            if len(mounts) != 1 or mounts[0].resolve() != mountpoint.resolve():
                raise PackageError("DMG 挂载点与本次只读验证目录不匹配")
            apps = list(mountpoint.rglob("*.app"))
            if len(apps) != 1:
                raise PackageError("macOS DMG 必须只包含一个应用")
            app = apps[0]
            executable = _parse_info((app / "Contents/Info.plist").read_bytes(), version, version_code)
            _check_macho((app / "Contents/MacOS" / executable).read_bytes(), architecture)
            _validate_macos_native_components(app, architecture)
            _run(["codesign", "--verify", "--deep", "--strict", str(app)])
            detail = subprocess.run(
                ["codesign", "-dv", "--verbose=4", str(app)],
                check=False,
                capture_output=True,
                text=True,
            )
            if detail.returncode:
                raise PackageError("codesign 无法读取 macOS 应用签名状态")
            signature_info = detail.stdout + "\n" + detail.stderr
            if "Signature=adhoc" not in signature_info or "Authority=" in signature_info:
                raise PackageError("macOS 应用必须通过 codesign 验证且使用 ad-hoc 签名")
        finally:
            _run(["hdiutil", "detach", "-force", str(mountpoint)])


def validate_windows_exe(path: Path, manifest_signing: str | None = None) -> None:
    try:
        data = path.read_bytes()
    except OSError:
        raise PackageError("Windows EXE 无法读取") from None
    if len(data) < 0x40 or data[:2] != b"MZ":
        raise PackageError("Windows 资产不是有效 PE 文件")
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if pe_offset + 24 > len(data) or data[pe_offset:pe_offset + 4] != b"PE\0\0":
        raise PackageError("Windows 资产不是有效 PE 文件")
    machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
    if machine != 0x8664:
        raise PackageError("Windows PE 文件必须为 x64")
    optional_size = struct.unpack_from("<H", data, pe_offset + 20)[0]
    optional = pe_offset + 24
    if optional_size < 112 or optional + optional_size > len(data) or struct.unpack_from("<H", data, optional)[0] != 0x20B:
        raise PackageError("Windows PE optional header 格式无效")
    directories = struct.unpack_from("<I", data, optional + 108)[0]
    if directories <= 4 or optional_size < 112 + 5 * 8:
        raise PackageError("Windows PE 缺少 Authenticode 状态目录")
    certificate_offset, certificate_size = struct.unpack_from("<II", data, optional + 112 + 4 * 8)
    if certificate_offset or certificate_size:
        if not certificate_offset or not certificate_size or certificate_offset + certificate_size > len(data):
            raise PackageError("Windows PE Authenticode 目录无效")
        raise PackageError("Windows Authenticode 签名身份尚未配置，拒绝签名 EXE")
    if manifest_signing not in (None, "unsigned"):
        raise PackageError("manifest 签名声明与实际未签名 EXE 不一致")

