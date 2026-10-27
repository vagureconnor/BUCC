#!/usr/bin/env python3
"""Verify a git-archive payload against its commit tree manifest and extract it safely."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import tarfile


class ArchiveError(ValueError):
    pass


def git_blob_sha1(data: bytes) -> str:
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()


def git_tree_sha1(entries: list[dict]) -> str:
    nodes: dict[str, dict[str, tuple[bool, bytes, str]]] = {'': {}}
    for entry in entries:
        path = entry['path']
        parts = path.split('/')
        parent = ''
        for part in parts[:-1]:
            prefix = f'{parent}/{part}' if parent else part
            nodes.setdefault(prefix, {})
            nodes.setdefault(parent, {})[part] = (True, b'', prefix)
            parent = prefix
        nodes.setdefault(parent, {})[parts[-1]] = (False, bytes.fromhex(entry['blob_sha1']), entry['mode'])

    def digest(directory: str) -> bytes:
        children = nodes[directory]
        records = []
        for name, (is_tree, oid_or_empty, mode_or_path) in children.items():
            if is_tree:
                mode, oid = b'40000', digest(mode_or_path)
            else:
                mode, oid = mode_or_path.encode('ascii'), oid_or_empty
            sort_name = name.encode('utf-8') + (b'/' if is_tree else b'')
            records.append((sort_name, mode + b' ' + name.encode('utf-8') + b'\0' + oid))
        raw = b''.join(record for _, record in sorted(records, key=lambda item: item[0]))
        return hashlib.sha1(b'tree ' + str(len(raw)).encode() + b'\0' + raw).digest()

    return digest('').hex()


def safe_relative(name: str) -> PurePosixPath:
    if not name or '\\' in name:
        raise ArchiveError('Unsafe archive path')
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in ('', '.', '..') for part in path.parts):
        raise ArchiveError('Unsafe archive path')
    if len(path.parts[0]) >= 2 and path.parts[0][1] == ':':
        raise ArchiveError('Unsafe archive path')
    return path


def verify_and_extract(archive: Path, provenance_path: Path, destination: Path,
                       source_sha: str, source_tree: str) -> None:
    if not (len(source_sha) == 40 and all(c in '0123456789abcdef' for c in source_sha)):
        raise ArchiveError('Source commit SHA must be 40 lowercase hex characters')
    if not (len(source_tree) == 40 and all(c in '0123456789abcdef' for c in source_tree)):
        raise ArchiveError('Source tree SHA must be 40 lowercase hex characters')
    if destination.exists():
        raise ArchiveError('Source extraction destination already exists')
    provenance = json.loads(provenance_path.read_text(encoding='utf-8'))
    if provenance.get('source_sha') != source_sha or provenance.get('source_tree') != source_tree:
        raise ArchiveError('Source provenance commit or tree does not match requested source')
    actual_archive_sha = hashlib.sha256(archive.read_bytes()).hexdigest()
    if provenance.get('archive_sha256') != actual_archive_sha:
        raise ArchiveError('Source archive hash does not match provenance')
    entries = provenance.get('files')
    if not isinstance(entries, list) or not entries:
        raise ArchiveError('Source provenance file list is missing')
    paths: dict[str, dict] = {}
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get('path'), str):
            raise ArchiveError('Invalid source file entry')
        path = safe_relative(entry['path']).as_posix()
        if path in paths or entry.get('mode') not in {'100644', '100755', '120000'}:
            raise ArchiveError('Duplicate source path or unsupported Git mode')
        blob = entry.get('blob_sha1', '')
        if len(blob) != 40 or any(c not in '0123456789abcdef' for c in blob):
            raise ArchiveError('Invalid Git blob SHA')
        paths[path] = entry
    if git_tree_sha1(entries) != source_tree:
        raise ArchiveError('Provenance files do not reconstruct the expected Git tree')

    destination.mkdir(parents=True)
    observed: set[str] = set()
    try:
        with tarfile.open(archive, 'r:*') as source:
            for member in source.getmembers():
                rel = safe_relative(member.name.rstrip('/'))
                target = destination.joinpath(*rel.parts)
                if member.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                if not member.isfile():
                    raise ArchiveError('Git archive contains an unsupported link or special file')
                name = rel.as_posix()
                if name in observed or name not in paths:
                    raise ArchiveError('Archive has duplicate or unlisted source files')
                stream = source.extractfile(member)
                if stream is None:
                    raise ArchiveError('Could not read source archive member')
                data = stream.read()
                if git_blob_sha1(data) != paths[name]['blob_sha1']:
                    raise ArchiveError('Source file contents differ from the Git tree manifest')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                observed.add(name)
    except (tarfile.TarError, OSError) as error:
        raise ArchiveError('Could not read source archive') from error
    if observed != paths.keys():
        raise ArchiveError('Source archive is missing files from the Git tree')



def verify_extracted_source(source_dir: Path, provenance_path: Path, source_sha: str, source_tree: str) -> None:
    provenance = json.loads(provenance_path.read_text(encoding='utf-8'))
    if provenance.get('source_sha') != source_sha or provenance.get('source_tree') != source_tree:
        raise ArchiveError('Source provenance commit or tree does not match requested source')
    entries = provenance.get('files')
    if not isinstance(entries, list) or not entries or git_tree_sha1(entries) != source_tree:
        raise ArchiveError('Provenance files do not reconstruct the expected Git tree')
    for entry in entries:
        relative = safe_relative(entry['path'])
        target = source_dir.joinpath(*relative.parts)
        if not target.is_file() or target.is_symlink():
            raise ArchiveError('Tracked source file is missing or changed type')
        data = target.read_bytes()
        if git_blob_sha1(data) != entry['blob_sha1']:
            raise ArchiveError('Tracked source file changed during the build')

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--provenance', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--source-tree', required=True)
    parser.add_argument('--verify-existing', action='store_true')
    args = parser.parse_args()
    try:
        if args.verify_existing:
            verify_extracted_source(args.destination, args.provenance, args.source_sha, args.source_tree)
        else:
            verify_and_extract(args.archive, args.provenance, args.destination, args.source_sha, args.source_tree)
    except (ArchiveError, OSError, json.JSONDecodeError) as error:
        parser.error(str(error))
    print('Verified and extracted source archive for ' + args.source_sha)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
