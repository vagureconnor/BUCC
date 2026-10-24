#!/usr/bin/env python3
"""Validate and upload an unsigned BUCC IPA to Cloudflare KV in chunks."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


CHUNK_SIZE = 8 * 1024 * 1024
MAX_CHUNKS = 20


class UploadError(Exception):
    pass


def validate_ipa(path: Path, chunk_directory: Path) -> tuple[int, str, list[Path]]:
    if not path.is_file() or path.suffix.lower() != ".ipa":
        raise UploadError("Input must be an existing .ipa file")
    has_app = False
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.namelist()
            if not members or any(not name.startswith("Payload/") for name in members):
                raise UploadError("IPA contains entries outside Payload/")
            has_app = any(name.startswith("Payload/") and name.endswith(".app/") for name in members)
            if not has_app:
                raise UploadError("IPA does not contain Payload/<app>.app")
            if any(name.endswith("/embedded.mobileprovision") for name in members):
                raise UploadError("IPA contains an embedded provisioning profile")
            if any("/_CodeSignature/" in name for name in members):
                raise UploadError("IPA contains _CodeSignature metadata")
    except (OSError, zipfile.BadZipFile) as exc:
        raise UploadError("Could not read IPA archive") from exc

    size = path.stat().st_size
    count = (size + CHUNK_SIZE - 1) // CHUNK_SIZE
    if size <= 0 or count > MAX_CHUNKS:
        raise UploadError("IPA size exceeds the configured KV chunk capacity")
    digest = hashlib.sha256()
    chunks: list[Path] = []
    with path.open("rb") as source:
        index = 0
        while data := source.read(CHUNK_SIZE):
            digest.update(data)
            chunk_path = chunk_directory / f"chunk-{index:03d}"
            chunk_path.write_bytes(data)
            chunks.append(chunk_path)
            index += 1
    return size, digest.hexdigest(), chunks


def upload(path: Path, version: str, revision: str, namespace_id: str, wrangler: Path, dry_run: bool) -> str:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise UploadError("Version must use numeric major.minor.patch format")
    if not re.fullmatch(r"[a-fA-F0-9]{7,40}", revision):
        raise UploadError("Revision must be 7 to 40 hexadecimal characters")
    if not namespace_id.strip():
        raise UploadError("Cloudflare KV namespace ID is required")
    if not wrangler.is_file():
        raise UploadError("Wrangler JavaScript CLI path does not exist")

    with tempfile.TemporaryDirectory(prefix="bucc-ipa-kv-") as temporary:
        temporary_path = Path(temporary)
        size, sha256, chunks = validate_ipa(path, temporary_path)
        filename = f"BUCC-{version}-iOS-unsigned-{revision.lower()[:40]}.ipa"
        keys = [f"ipa:{sha256}:{index:03d}" for index in range(len(chunks))]
        manifest = {
            "filename": filename,
            "size": size,
            "sha256": sha256,
            "chunks": [{"key": key, "size": chunk.stat().st_size} for key, chunk in zip(keys, chunks)],
        }
        if dry_run:
            print(f"Validated {filename}: {size} bytes, {len(chunks)} chunks, SHA-256 {sha256}")
            return filename

        for index, (key, chunk_path) in enumerate(zip(keys, chunks)):
            command = ["node", str(wrangler), "kv", "key", "put", key,
                       "--namespace-id", namespace_id, "--path", str(chunk_path), "--remote"]
            result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if result.returncode:
                raise UploadError(f"Cloudflare KV chunk upload failed at chunk {index}")

        manifest_path = temporary_path / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, separators=(",", ":")), encoding="utf-8")
        command = ["node", str(wrangler), "kv", "key", "put", f"manifest:{filename}",
                   "--namespace-id", namespace_id, "--path", str(manifest_path), "--remote"]
        result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        if result.returncode:
            raise UploadError("Cloudflare KV manifest publish failed")
    print(f"Uploaded {filename}: {size} bytes, {len(chunks)} chunks, SHA-256 {sha256}")
    return filename


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ipa", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--revision", required=True, help="short hexadecimal source revision")
    parser.add_argument("--namespace-id", required=True)
    parser.add_argument("--wrangler-js", type=Path, required=True, help="path to wrangler JavaScript CLI")
    parser.add_argument("--dry-run", action="store_true", help="validate archive and report chunk plan without upload")
    args = parser.parse_args(argv)
    try:
        upload(args.ipa, args.version, args.revision, args.namespace_id, args.wrangler_js, args.dry_run)
    except UploadError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
