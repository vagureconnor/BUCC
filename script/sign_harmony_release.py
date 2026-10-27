#!/usr/bin/env python3
"""Sign one verified unsigned BUCC Harmony HAP locally with the DevEco certificate."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pty
import re
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import termios
import time
import zipfile
from pathlib import Path
from typing import Sequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / ".github" / "scripts"))
from public_certificate import (  # noqa: E402
    EXPECTED_HARMONY_LEAF_SHA256,
    CertificateError,
    leaf_sha256,
    validate_leaf_sha256,
)

KEYCHAIN_SERVICE = "BUCC Harmony DevEco P12"
EXPECTED_KEYSTORE = Path(
    "/Users/connor/Library/Application Support/BUCC/Signing/Harmony/BUCC-Harmony-DevEco.p12"
)
APP_CERTIFICATE = Path(
    "/Users/connor/Library/Application Support/BUCC/Signing/Harmony/BUCC-Harmony-Release.cer"
)
APP_PROFILE = Path(
    "/Users/connor/Library/Application Support/BUCC/Signing/Harmony/BUCC-Harmony-Release.p7b"
)
DEFAULT_HAP_SIGN_TOOL = Path(
    os.environ.get(
        "DEVECO_SDK_HOME", "/Applications/DevEco-Studio.app/Contents/sdk"
    )
) / "default/openharmony/toolchains/lib/hap-sign-tool.jar"
EXPECTED_SIGNING_FINGERPRINT = EXPECTED_HARMONY_LEAF_SHA256
BUNDLE_CONFIG = Path(__file__).resolve().parents[1] / "harmonyApp/AppScope/app.json5"
SIGN_ALIAS = "bucc_release"
SIGN_ALGORITHM = "SHA384withECDSA"
COMPATIBLE_VERSION = "20"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SOURCE_SHA_RE = re.compile(r"^[0-9a-f]{40}$")
SOURCE_TREE_RE = re.compile(r"^[0-9a-f]{40}$")
VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")

# HAP signing adds certificate/profile/signature records under META-INF. These
# are the only entries ignored by the unsigned/signed payload comparison.
SIGNATURE_ENTRY_RE = re.compile(
    r"^META-INF/(?:MANIFEST\.MF|HAP\.SIG|CERT[^/]*\.(?:SF|RSA|DSA|EC|CER)|"
    r"PROFILE[^/]*\.(?:P7B|CER))$",
    re.IGNORECASE,
)


class SigningError(Exception):
    """Safe, user-facing error with no tool output or credential material."""


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            key, value = stripped.split("=", 1)
            properties[key.strip()] = value.strip()
    return properties


def validate_input(
    hap: Path,
    metadata_path: Path,
    expected_source_sha: str,
    output: Path,
    keystore: Path,
    expected_keystore: Path = EXPECTED_KEYSTORE,
) -> dict:
    if not SOURCE_SHA_RE.fullmatch(expected_source_sha):
        raise SigningError("expected source SHA must be a 40-character lowercase Git SHA")
    try:
        resolved_hap = hap.resolve(strict=True)
        resolved_metadata = metadata_path.resolve(strict=True)
        resolved_output = output.absolute()
        resolved_expected_keystore = expected_keystore.resolve(strict=True)
    except OSError:
        raise SigningError("input or configured signing material is missing") from None
    if keystore.expanduser().absolute() != expected_keystore.expanduser().absolute():
        raise SigningError("keystore path does not match the approved DevEco keystore")
    try:
        resolved_keystore = keystore.resolve(strict=True)
    except OSError:
        raise SigningError("approved DevEco keystore is missing") from None
    if not resolved_hap.is_file() or resolved_hap.suffix.lower() != ".hap":
        raise SigningError("input must be an existing .hap file")
    if resolved_output.suffix.lower() != ".hap":
        raise SigningError("output filename must end in .hap")
    if not resolved_metadata.is_file():
        raise SigningError("input metadata file is missing")
    if resolved_keystore != resolved_expected_keystore:
        raise SigningError("keystore path does not match the approved DevEco keystore")
    if resolved_output.exists():
        raise SigningError("output already exists; refusing to overwrite it")
    manifest_output = resolved_output.with_suffix(resolved_output.suffix + ".manifest.json")
    if manifest_output.exists():
        raise SigningError("output manifest already exists; refusing to overwrite it")
    if resolved_output == resolved_hap or resolved_output == resolved_metadata:
        raise SigningError("output path must be new and separate from the inputs")
    if not resolved_output.parent.is_dir():
        raise SigningError("output parent directory must already exist")

    try:
        metadata = json.loads(resolved_metadata.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raise SigningError("input metadata is unreadable") from None
    if metadata.get("platform") != "harmony" or metadata.get("signing") != "unsigned":
        raise SigningError("input metadata must identify an unsigned Harmony artifact")
    source_sha = metadata.get("source_sha")
    if source_sha != expected_source_sha:
        raise SigningError("input metadata source SHA does not match the expected source")
    source_tree = metadata.get("source_tree")
    if not isinstance(source_tree, str) or not SOURCE_TREE_RE.fullmatch(source_tree):
        raise SigningError("input metadata source tree is invalid")
    if metadata.get("file") != resolved_hap.name:
        raise SigningError("input metadata filename does not match the HAP")
    version = metadata.get("version")
    if not isinstance(version, str) or not VERSION_RE.fullmatch(version):
        raise SigningError("input metadata version is invalid")
    expected_hash = metadata.get("sha256")
    if not isinstance(expected_hash, str) or not SHA256_RE.fullmatch(expected_hash):
        raise SigningError("input metadata SHA-256 is invalid")
    if hash_file(resolved_hap) != expected_hash:
        raise SigningError("unsigned HAP hash does not match its metadata")
    try:
        with zipfile.ZipFile(resolved_hap) as archive:
            validate_zip_entries(archive)
    except (OSError, zipfile.BadZipFile):
        raise SigningError("unsigned HAP is not a valid ZIP package") from None
    return {
        "hap": resolved_hap,
        "metadata_path": resolved_metadata,
        "metadata": metadata,
        "output": resolved_output,
        "manifest_output": manifest_output,
        "keystore": resolved_keystore,
    }


def validate_zip_entries(archive: zipfile.ZipFile) -> None:
    names = archive.namelist()
    if not names or len(names) != len(set(names)):
        raise SigningError("HAP has no entries or contains duplicate ZIP entry names")
    for name in names:
        path = Path(name)
        if path.is_absolute() or ".." in path.parts or "\\" in name:
            raise SigningError("HAP contains an unsafe ZIP entry path")


def payload_hashes(hap: Path) -> dict[str, str]:
    try:
        with zipfile.ZipFile(hap) as archive:
            validate_zip_entries(archive)
            return {
                info.filename: hashlib.sha256(archive.read(info)).hexdigest()
                for info in archive.infolist()
                if not info.is_dir() and not SIGNATURE_ENTRY_RE.fullmatch(info.filename)
            }
    except (OSError, zipfile.BadZipFile, RuntimeError):
        raise SigningError("could not read HAP payload entries") from None


def verify_payload_unchanged(unsigned_hap: Path, signed_hap: Path) -> None:
    unsigned_payload = payload_hashes(unsigned_hap)
    signed_payload = payload_hashes(signed_hap)
    if unsigned_payload != signed_payload:
        raise SigningError("signed HAP payload differs from the unsigned source")


def read_keychain_password(service: str = KEYCHAIN_SERVICE) -> str:
    try:
        result = subprocess.run(
            ["security", "find-generic-password", "-s", service, "-w"],
            check=False,
            capture_output=True,
            text=False,
        )
    except OSError:
        raise SigningError("macOS Keychain lookup is unavailable") from None
    if result.returncode != 0:
        raise SigningError("macOS Keychain item lookup failed")
    try:
        password = result.stdout.decode("utf-8").rstrip("\r\n")
    except UnicodeDecodeError:
        raise SigningError("macOS Keychain item is not valid UTF-8") from None
    if not password:
        raise SigningError("macOS Keychain item is empty")
    return password


def _prompt_kind(buffer: bytes) -> str | None:
    text = buffer.decode("utf-8", errors="ignore").lower()
    recent = text[-2048:]
    if re.search(r"please\s+input\s+keystorepwd\b", recent):
        return "keystore"
    if re.search(r"please\s+input\s+keypwd\b", recent):
        return "key"
    if "issuer" in recent and re.search(r"(?:password|pwd)", recent):
        return "unknown"
    if "keystore" in recent and re.search(r"\bpassword\b", recent):
        return "keystore"
    if re.search(r"\bkey\b.{0,64}\bpassword\b", recent):
        return "key"
    if re.search(r"\bpassword\b|pwd", recent):
        return "unknown"
    return None


def _ensure_echo_disabled(master_fd: int) -> None:
    try:
        attributes = termios.tcgetattr(master_fd)
        if attributes[3] & termios.ECHO:
            attributes[3] &= ~termios.ECHO
            termios.tcsetattr(master_fd, termios.TCSANOW, attributes)
            attributes = termios.tcgetattr(master_fd)
        if attributes[3] & termios.ECHO:
            raise SigningError("interactive terminal echo could not be disabled")
    except (OSError, termios.error):
        raise SigningError("interactive terminal echo state could not be verified") from None


def run_interactive_signer(
    argv: Sequence[str],
    password: str,
    timeout_seconds: int = 180,
) -> int:
    """Run without exposing passwords in argv/env/stdout; return only exit status."""
    if not password:
        raise SigningError("signing password is empty")
    try:
        child_pid, master_fd = pty.fork()
    except OSError:
        raise SigningError("could not create a private interactive terminal") from None
    if child_pid == 0:
        try:
            attributes = termios.tcgetattr(0)
            attributes[3] &= ~termios.ECHO
            termios.tcsetattr(0, termios.TCSANOW, attributes)
            os.execvpe(argv[0], list(argv), os.environ.copy())
        except BaseException:
            os._exit(127)

    sent: set[str] = set()
    recent = bytearray()
    started = time.monotonic()
    exit_status: int | None = None
    reaped = False
    try:
        while True:
            waited_pid, status = os.waitpid(child_pid, os.WNOHANG)
            if waited_pid == child_pid:
                exit_status = os.waitstatus_to_exitcode(status)
                reaped = True
                break
            if time.monotonic() - started > timeout_seconds:
                raise SigningError("signing tool timed out")
            readable, _, _ = select.select([master_fd], [], [], 0.2)
            if not readable:
                continue
            try:
                chunk = os.read(master_fd, 4096)
            except OSError:
                break
            if not chunk:
                break
            recent.extend(chunk)
            if len(recent) > 4096:
                del recent[:-2048]
            prompt = _prompt_kind(bytes(recent))
            if prompt is None:
                continue
            if prompt == "unknown":
                raise SigningError("signing tool requested an unexpected password")
            if prompt in sent:
                raise SigningError("signing tool requested a repeated password prompt")
            _ensure_echo_disabled(master_fd)
            os.write(master_fd, password.encode("utf-8") + b"\n")
            sent.add(prompt)
            recent.clear()
        if exit_status is None:
            _, status = os.waitpid(child_pid, 0)
            exit_status = os.waitstatus_to_exitcode(status)
            reaped = True
        if exit_status != 0:
            raise SigningError(f"signing tool exited with status {exit_status}")
        if sent != {"keystore", "key"}:
            raise SigningError("signing tool did not request both protected passwords")
        return exit_status
    except SigningError:
        if not reaped:
            try:
                os.kill(child_pid, signal.SIGKILL)
            except OSError:
                pass
            try:
                os.waitpid(child_pid, 0)
            except OSError:
                pass
        raise
    except (OSError, ValueError, termios.error):
        if not reaped:
            try:
                os.kill(child_pid, signal.SIGKILL)
            except OSError:
                pass
            try:
                os.waitpid(child_pid, 0)
            except OSError:
                pass
        raise SigningError("interactive signing operation failed") from None
    finally:
        try:
            os.close(master_fd)
        except OSError:
            pass


def build_sign_command(
    java: str,
    hap_sign_tool: Path,
    keystore: Path,
    certificate: Path,
    profile: Path,
    unsigned_hap: Path,
    signed_hap: Path,
) -> list[str]:
    return [
        java,
        "-jar",
        str(hap_sign_tool),
        "sign-app",
        "-mode",
        "localSign",
        "-keyAlias",
        SIGN_ALIAS,
        "-appCertFile",
        str(certificate),
        "-profileFile",
        str(profile),
        "-inFile",
        str(unsigned_hap),
        "-signAlg",
        SIGN_ALGORITHM,
        "-keystoreFile",
        str(keystore),
        "-outFile",
        str(signed_hap),
        "-compatibleVersion",
        COMPATIBLE_VERSION,
        "-pwdInputMode",
        "1",
    ]


def certificate_fingerprint(certificate: Path) -> str:
    try:
        return leaf_sha256(certificate)
    except CertificateError as error:
        raise SigningError(f"verified HAP certificate chain could not be inspected: {error}") from None


def validate_release_certificate_fingerprint(certificate: Path) -> str:
    try:
        return validate_leaf_sha256(certificate, EXPECTED_SIGNING_FINGERPRINT)
    except CertificateError as error:
        raise SigningError(str(error)) from None


def read_project_bundle_name(config_path: Path = BUNDLE_CONFIG) -> str:
    try:
        contents = config_path.read_text(encoding="utf-8")
    except OSError:
        raise SigningError("Harmony application configuration is missing") from None
    names = re.findall(r'"bundleName"\s*:\s*"([^"]+)"', contents)
    if len(names) != 1:
        raise SigningError("Harmony application configuration must declare one bundleName")
    return names[0]


def _profile_bundle_names(value: object) -> set[str]:
    names: set[str] = set()
    if isinstance(value, dict):
        for key, item in value.items():
            if key in {"bundle-name", "bundleName", "bundle_name"} and isinstance(item, str):
                names.add(item)
            names.update(_profile_bundle_names(item))
    elif isinstance(value, list):
        for item in value:
            names.update(_profile_bundle_names(item))
    return names


def validate_verified_profile(profile: Path, expected_bundle_name: str) -> None:
    openssl = shutil.which("openssl")
    if not openssl or not profile.is_file():
        raise SigningError("verified HAP profile could not be inspected")
    with tempfile.TemporaryDirectory(prefix="bucc-harmony-profile-") as temporary:
        content = Path(temporary) / "verified-profile.json"
        verified = False
        for encoding in ("DER", "PEM"):
            try:
                result = subprocess.run(
                    [
                        openssl,
                        "cms",
                        "-verify",
                        "-binary",
                        "-inform",
                        encoding,
                        "-in",
                        str(profile),
                        "-noverify",
                        "-out",
                        str(content),
                    ],
                    check=False,
                    capture_output=True,
                    text=True,
                )
            except OSError:
                raise SigningError("OpenSSL could not inspect the verified HAP profile") from None
            if result.returncode == 0 and content.is_file():
                verified = True
                break
            content.unlink(missing_ok=True)
        if not verified:
            raise SigningError("officially verified HAP profile content is unreadable")
        try:
            profile_data = json.loads(content.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            raise SigningError("verified HAP profile is not valid JSON") from None
    if _profile_bundle_names(profile_data) != {expected_bundle_name}:
        raise SigningError("verified HAP profile bundle name does not match the current app configuration")


def verify_signed_hap(java: str, hap_sign_tool: Path, signed_hap: Path, temp_dir: Path) -> tuple[Path, Path]:
    certificate = temp_dir / "verified-cert-chain.cer"
    profile = temp_dir / "verified-profile.p7b"
    argv = [
        java,
        "-jar",
        str(hap_sign_tool),
        "verify-app",
        "-inFile",
        str(signed_hap),
        "-outCertChain",
        str(certificate),
        "-outProfile",
        str(profile),
    ]
    try:
        result = subprocess.run(argv, check=False, capture_output=True, text=True)
    except OSError:
        raise SigningError("HAP signature verifier could not be started") from None
    if result.returncode != 0 or not certificate.is_file() or not profile.is_file():
        raise SigningError("official HAP signature verification failed")
    if not profile.stat().st_size:
        raise SigningError("verified HAP profile output is empty")
    return certificate, profile


def copy_exclusive(source: Path, destination: Path) -> None:
    fd: int | None = None
    try:
        fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as target, source.open("rb") as incoming:
            fd = None
            shutil.copyfileobj(incoming, target)
    except FileExistsError:
        raise SigningError("output already exists; refusing to overwrite it") from None
    except OSError:
        if fd is not None:
            os.close(fd)
        destination.unlink(missing_ok=True)
        raise SigningError("could not create the signed output file") from None


def sign(
    hap: Path,
    metadata_path: Path,
    expected_source_sha: str,
    output: Path,
    keystore: Path = EXPECTED_KEYSTORE,
    hap_sign_tool: Path = DEFAULT_HAP_SIGN_TOOL,
    java: str | None = None,
) -> dict:
    validated = validate_input(hap, metadata_path, expected_source_sha, output, keystore)
    if not hap_sign_tool.is_file():
        raise SigningError("configured official hap-sign-tool.jar is missing")
    if not APP_CERTIFICATE.is_file() or not APP_PROFILE.is_file():
        raise SigningError("configured Harmony release certificate/profile is missing")
    java_command = java or shutil.which("java")
    if not java_command:
        raise SigningError("Java is unavailable")
    validate_release_certificate_fingerprint(APP_CERTIFICATE)
    password = read_keychain_password()
    input_hap: Path = validated["hap"]
    metadata = validated["metadata"]
    output_path: Path = validated["output"]
    manifest_path: Path = validated["manifest_output"]

    created_output = False
    try:
        with tempfile.TemporaryDirectory(prefix="bucc-harmony-sign-") as temporary:
            private_temp = Path(temporary)
            signed_temp = private_temp / output_path.name
            argv = build_sign_command(
                java_command,
                hap_sign_tool,
                validated["keystore"],
                APP_CERTIFICATE,
                APP_PROFILE,
                input_hap,
                signed_temp,
            )
            run_interactive_signer(argv, password)
            password = ""
            if not signed_temp.is_file():
                raise SigningError("signing tool produced no HAP file")
            verify_signed_hap(java_command, hap_sign_tool, signed_temp, private_temp)
            verify_payload_unchanged(input_hap, signed_temp)
            verified_cert = private_temp / "verified-cert-chain.cer"
            verified_profile = private_temp / "verified-profile.p7b"
            fingerprint = validate_release_certificate_fingerprint(verified_cert)
            validate_verified_profile(verified_profile, read_project_bundle_name())
            signed_sha = hash_file(signed_temp)
            result = {
                "platform": "harmony",
                "version": metadata["version"],
                "version_code": metadata.get("version_code"),
                "source_sha": expected_source_sha,
                "source_tree": metadata["source_tree"],
                "arch": "arm64-v8a",
                "input_file": input_hap.name,
                "input_sha256": metadata["sha256"],
                "file": output_path.name,
                "sha256": signed_sha,
                "signing_fingerprint": fingerprint,
                "sign_algorithm": SIGN_ALGORITHM,
                "compatible_version": COMPATIBLE_VERSION,
                "toolchain": metadata.get("toolchain", {}),
            }
            copy_exclusive(signed_temp, output_path)
            created_output = True
            write_manifest_exclusive(manifest_path, result)
            created_output = False
            return result
    except SigningError:
        if created_output:
            output_path.unlink(missing_ok=True)
        raise
    except Exception:
        if created_output:
            output_path.unlink(missing_ok=True)
        raise SigningError("Harmony signing operation failed safely") from None
    finally:
        password = ""


def write_manifest_exclusive(path: Path, manifest: dict) -> None:
    payload = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
    fd: int | None = None
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            fd = None
            output.write(payload)
    except FileExistsError:
        raise SigningError("output manifest already exists; refusing to overwrite it") from None
    except OSError:
        if fd is not None:
            os.close(fd)
        path.unlink(missing_ok=True)
        raise SigningError("could not create the signed output manifest") from None


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hap", type=Path, required=True, help="unsigned Harmony HAP from the CI artifact")
    parser.add_argument("--metadata", type=Path, required=True, help="unsigned HAP metadata JSON")
    parser.add_argument("--expected-source-sha", required=True, help="the exact 40-character CI source SHA")
    parser.add_argument("--output", type=Path, required=True, help="new signed HAP path; existing files are never overwritten")
    parser.add_argument("--keystore", type=Path, default=EXPECTED_KEYSTORE, help=argparse.SUPPRESS)
    parser.add_argument("--hap-sign-tool", type=Path, default=DEFAULT_HAP_SIGN_TOOL, help="official hap-sign-tool.jar from the configured DevEco SDK")
    args = parser.parse_args(argv)
    try:
        result = sign(args.hap, args.metadata, args.expected_source_sha, args.output, args.keystore, args.hap_sign_tool)
    except SigningError as error:
        print(f"Harmony signing failed safely: {error}", file=sys.stderr)
        return 1
    except Exception:
        print("Harmony signing failed safely: unexpected internal error", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
