import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from public_certificate import CertificateError, leaf_sha256


ROOT_SHA = "a1" * 32
LEAF_SHA = "7d8fabaaff15ea84e5343d94b352ee97452e4314bfb90bbdcc8569d57659f2f7"
OTHER_SHA = "c3" * 32


def pem(label: str) -> bytes:
    return f"-----BEGIN CERTIFICATE-----\n{label}\n-----END CERTIFICATE-----\n".encode()


class PublicCertificateTests(unittest.TestCase):
    def run_openssl_for_markers(self, ca_by_marker, fingerprint_by_marker):
        def fake_run(command, **kwargs):
            data = Path(command[command.index("-in") + 1]).read_bytes()
            marker = next((key for key in ca_by_marker if key.encode() in data), "DER")
            fingerprint = fingerprint_by_marker[marker]
            constraints = "CA:TRUE" if ca_by_marker.get(marker, False) else "CA:FALSE"
            return SimpleNamespace(
                returncode=0,
                stdout=f"sha256 Fingerprint=" + ":".join(fingerprint[index:index + 2] for index in range(0, 64, 2)) + f"\nX509v3 Basic Constraints:\n    {constraints}\n",
            )

        return fake_run

    def test_root_first_pem_chain_returns_unique_non_ca_leaf_fingerprint(self):
        with tempfile.TemporaryDirectory() as temporary:
            certificate = Path(temporary) / "Release.cer"
            certificate.write_bytes(pem("ROOT") + pem("LEAF"))
            fake_run = self.run_openssl_for_markers(
                {"ROOT": True, "LEAF": False},
                {"ROOT": ROOT_SHA, "LEAF": LEAF_SHA},
            )
            with patch("public_certificate.shutil.which", return_value="openssl"), patch(
                "public_certificate.subprocess.run", side_effect=fake_run
            ):
                self.assertEqual(LEAF_SHA, leaf_sha256(certificate))

    def test_der_single_leaf_is_supported(self):
        with tempfile.TemporaryDirectory() as temporary:
            certificate = Path(temporary) / "single.cer"
            certificate.write_bytes(b"synthetic DER bytes")
            fake_run = self.run_openssl_for_markers({"DER": False}, {"DER": LEAF_SHA})
            with patch("public_certificate.shutil.which", return_value="openssl"), patch(
                "public_certificate.subprocess.run", side_effect=fake_run
            ):
                self.assertEqual(LEAF_SHA, leaf_sha256(certificate))

    def test_rejects_ca_only_and_multiple_non_ca_certificates(self):
        for labels, ca_by_marker, fingerprints, expected in (
            (("ROOT",), {"ROOT": True}, {"ROOT": ROOT_SHA}, "non-CA leaf"),
            (("ROOT", "LEAF", "OTHER"), {"ROOT": True, "LEAF": False, "OTHER": False}, {"ROOT": ROOT_SHA, "LEAF": LEAF_SHA, "OTHER": OTHER_SHA}, "exactly one"),
        ):
            with self.subTest(labels=labels), tempfile.TemporaryDirectory() as temporary:
                certificate = Path(temporary) / "chain.cer"
                certificate.write_bytes(b"".join(pem(label) for label in labels))
                fake_run = self.run_openssl_for_markers(ca_by_marker, fingerprints)
                with patch("public_certificate.shutil.which", return_value="openssl"), patch(
                    "public_certificate.subprocess.run", side_effect=fake_run
                ):
                    with self.assertRaisesRegex(CertificateError, expected):
                        leaf_sha256(certificate)

    def test_rejects_wrong_leaf_fingerprint_at_identity_validation(self):
        from public_certificate import validate_leaf_sha256

        with tempfile.TemporaryDirectory() as temporary:
            certificate = Path(temporary) / "chain.cer"
            certificate.write_bytes(pem("ROOT") + pem("LEAF"))
            fake_run = self.run_openssl_for_markers(
                {"ROOT": True, "LEAF": False},
                {"ROOT": ROOT_SHA, "LEAF": OTHER_SHA},
            )
            with patch("public_certificate.shutil.which", return_value="openssl"), patch(
                "public_certificate.subprocess.run", side_effect=fake_run
            ):
                with self.assertRaisesRegex(CertificateError, "approved"):
                    validate_leaf_sha256(certificate, LEAF_SHA)


if __name__ == "__main__":
    unittest.main()
