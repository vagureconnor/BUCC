"""Extract the unique non-CA leaf identity from a public X.509 certificate file."""
from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
from pathlib import Path


class CertificateError(ValueError):
    pass


EXPECTED_HARMONY_LEAF_SHA256 = "7d8fabaaff15ea84e5343d94b352ee97452e4314bfb90bbdcc8569d57659f2f7"
PEM_CERTIFICATE = re.compile(rb"-----BEGIN CERTIFICATE-----\s+.*?-----END CERTIFICATE-----", re.DOTALL)
FINGERPRINT = re.compile(r"(?:sha256\s+)?Fingerprint=([0-9a-fA-F:]+)", re.IGNORECASE)
CA_TRUE = re.compile(r"\bCA\s*:\s*TRUE\b", re.IGNORECASE)


def _certificate_blobs(path: Path) -> tuple[list[bytes], bool]:
    try:
        contents = path.read_bytes()
    except OSError:
        raise CertificateError("certificate file is unavailable") from None
    blocks = list(PEM_CERTIFICATE.finditer(contents))
    if blocks:
        remainder = PEM_CERTIFICATE.sub(b"", contents)
        if b"-----BEGIN CERTIFICATE-----" in remainder or b"-----END CERTIFICATE-----" in remainder or remainder.strip():
            raise CertificateError("certificate PEM chain has malformed or extra data")
        return [match.group(0) + b"\n" for match in blocks], True
    if b"-----BEGIN CERTIFICATE-----" in contents or b"-----END CERTIFICATE-----" in contents:
        raise CertificateError("certificate PEM chain is malformed")
    if not contents:
        raise CertificateError("certificate file is empty")
    return [contents], False


def _inspect_certificate(openssl: str, certificate: Path, is_pem: bool) -> tuple[str, bool]:
    command = [openssl, "x509"]
    if not is_pem:
        command.extend(["-inform", "DER"])
    command.extend(["-in", str(certificate), "-noout", "-fingerprint", "-sha256", "-text"])
    try:
        result = subprocess.run(command, check=False, capture_output=True, text=True)
    except OSError:
        raise CertificateError("OpenSSL certificate inspection failed") from None
    if result.returncode:
        raise CertificateError("certificate could not be parsed")
    match = FINGERPRINT.search(result.stdout)
    if not match:
        raise CertificateError("certificate SHA-256 fingerprint was not reported")
    fingerprint = re.sub(r"[^0-9a-f]", "", match.group(1).lower())
    if len(fingerprint) != 64:
        raise CertificateError("certificate SHA-256 fingerprint is invalid")
    return fingerprint, bool(CA_TRUE.search(result.stdout))


def leaf_sha256(certificate: Path) -> str:
    openssl = shutil.which("openssl")
    if not openssl:
        raise CertificateError("OpenSSL is unavailable for certificate inspection")
    blobs, is_pem = _certificate_blobs(certificate)
    leaves = []
    with tempfile.TemporaryDirectory(prefix="bucc-public-certificate-") as temporary:
        directory = Path(temporary)
        for index, blob in enumerate(blobs):
            single = directory / f"certificate-{index}.cer"
            single.write_bytes(blob)
            fingerprint, is_ca = _inspect_certificate(openssl, single, is_pem)
            if not is_ca:
                leaves.append(fingerprint)
    if not leaves:
        raise CertificateError("certificate chain contains no non-CA leaf")
    if len(leaves) != 1:
        raise CertificateError("certificate chain must contain exactly one non-CA leaf")
    return leaves[0]


def validate_leaf_sha256(certificate: Path, expected: str = EXPECTED_HARMONY_LEAF_SHA256) -> str:
    actual = leaf_sha256(certificate)
    normalized_expected = re.sub(r"[^0-9a-f]", "", expected.lower())
    if not re.fullmatch(r"[0-9a-f]{64}", normalized_expected) or actual != normalized_expected:
        raise CertificateError("certificate chain leaf does not match the approved release certificate")
    return actual

