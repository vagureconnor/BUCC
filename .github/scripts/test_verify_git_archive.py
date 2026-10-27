#!/usr/bin/env python3
"""Source archive provenance and extraction checks."""
from __future__ import annotations
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('verify_git_archive', ROOT / '.github/scripts/verify_git_archive.py')
verify = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(verify)


class GitArchiveVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.run_git('init', '-q')
        self.run_git('config', 'user.name', 'Fixture')
        self.run_git('config', 'user.email', 'fixture@example.invalid')
        (self.repo / 'main.kt').write_bytes(b'class Main {}\n')
        (self.repo / 'nested').mkdir()
        (self.repo / 'nested/tool.sh').write_bytes(b'#!/bin/sh\nexit 0\n')
        self.run_git('add', '.')
        self.run_git('update-index', '--chmod=+x', 'nested/tool.sh')
        self.run_git('commit', '-qm', 'fixture')
        self.sha = self.run_git('rev-parse', 'HEAD').strip()
        self.tree = self.run_git('rev-parse', 'HEAD^{tree}').strip()
        output = subprocess.run(['git', 'ls-tree', '-r', '-z', self.sha], cwd=self.repo,
                                capture_output=True, check=True).stdout
        files = []
        for record in output.split(b'\0'):
            if not record:
                continue
            metadata, path = record.split(b'\t', 1)
            mode, _kind, blob = metadata.decode().split()
            files.append({'path': path.decode(), 'mode': mode, 'blob_sha1': blob})
        self.archive = self.root / 'source.tar'
        self.archive.write_bytes(subprocess.run(['git', 'archive', '--format=tar', self.sha], cwd=self.repo,
                                                 capture_output=True, check=True).stdout)
        self.files = files
        self.provenance = self.root / 'source-provenance.json'
        self.write_provenance()
        self.dest = self.root / 'extracted'

    def tearDown(self):
        self.temp.cleanup()

    def run_git(self, *args):
        return subprocess.run(['git', *args], cwd=self.repo, capture_output=True, text=True, check=True).stdout

    def write_provenance(self):
        self.provenance.write_text(json.dumps({'source_sha': self.sha, 'source_tree': self.tree,
            'archive_sha256': hashlib.sha256(self.archive.read_bytes()).hexdigest(), 'files': self.files}))

    def test_valid_git_archive_reconstructs_expected_tree(self):
        verify.verify_and_extract(self.archive, self.provenance, self.dest, self.sha, self.tree)
        self.assertEqual(b'class Main {}\n', (self.dest / 'main.kt').read_bytes())
        self.assertEqual(b'#!/bin/sh\nexit 0\n', (self.dest / 'nested/tool.sh').read_bytes())

    def test_tampered_archive_content_is_rejected_even_with_updated_archive_digest(self):
        entries = []
        with tarfile.open(self.archive, 'r:') as source:
            entries = [(m.name, source.extractfile(m).read() if m.isfile() else None, m.mode, m.isdir()) for m in source.getmembers()]
        rebuilt = self.root / 'tampered.tar'
        with tarfile.open(rebuilt, 'w') as out:
            for name, data, mode, is_dir in entries:
                import io
                item = tarfile.TarInfo(name); item.mode = mode
                if is_dir:
                    item.type = tarfile.DIRTYPE; item.size = 0; out.addfile(item)
                else:
                    item.size = len(data.replace(b'Main', b'Evil'))
                    out.addfile(item, io.BytesIO(data.replace(b'Main', b'Evil')))
        self.archive.rename(self.root / 'original.tar')
        self.archive = rebuilt
        self.write_provenance()
        with self.assertRaisesRegex(verify.ArchiveError, 'contents differ'):
            verify.verify_and_extract(self.archive, self.provenance, self.dest, self.sha, self.tree)

    def test_extra_untracked_archive_file_is_rejected(self):
        with tarfile.open(self.archive, 'a') as out:
            import io
            item = tarfile.TarInfo('untracked.txt'); item.size = 1
            out.addfile(item, io.BytesIO(b'x'))
        self.write_provenance()
        with self.assertRaisesRegex(verify.ArchiveError, 'unlisted'):
            verify.verify_and_extract(self.archive, self.provenance, self.dest, self.sha, self.tree)

    def test_mismatched_commit_or_tree_is_rejected(self):
        with self.assertRaisesRegex(verify.ArchiveError, 'commit or tree'):
            verify.verify_and_extract(self.archive, self.provenance, self.dest, '0' * 40, self.tree)

    def test_provenance_file_list_must_rebuild_commit_tree(self):
        document = json.loads(self.provenance.read_text())
        document['files'][0]['blob_sha1'] = '0' * 40
        self.provenance.write_text(json.dumps(document))
        with self.assertRaisesRegex(verify.ArchiveError, 'reconstruct'):
            verify.verify_and_extract(self.archive, self.provenance, self.dest, self.sha, self.tree)

    def test_tracked_source_tampering_after_extraction_is_rejected(self):
        verify.verify_and_extract(self.archive, self.provenance, self.dest, self.sha, self.tree)
        (self.dest / 'main.kt').write_bytes(b'tampered')
        with self.assertRaisesRegex(verify.ArchiveError, 'changed during the build'):
            verify.verify_extracted_source(self.dest, self.provenance, self.sha, self.tree)

    def test_unsafe_tar_path_is_rejected(self):
        self.archive = self.root / 'unsafe.tar'
        with tarfile.open(self.archive, 'w') as out:
            import io
            item = tarfile.TarInfo('../escape'); item.size = 1
            out.addfile(item, io.BytesIO(b'x'))
        self.write_provenance()
        with self.assertRaisesRegex(verify.ArchiveError, 'Unsafe archive path'):
            verify.verify_and_extract(self.archive, self.provenance, self.dest, self.sha, self.tree)
        self.assertFalse((self.root.parent / 'escape').exists())

    def test_symlink_tar_member_is_rejected(self):
        self.archive = self.root / 'link.tar'
        with tarfile.open(self.archive, 'w') as out:
            item = tarfile.TarInfo('link'); item.type = tarfile.SYMTYPE; item.linkname = '../outside'
            out.addfile(item)
        self.write_provenance()
        with self.assertRaisesRegex(verify.ArchiveError, 'unsupported link'):
            verify.verify_and_extract(self.archive, self.provenance, self.dest, self.sha, self.tree)

    def test_preexisting_destination_is_never_overwritten(self):
        self.dest.mkdir()
        marker = self.dest / 'keep.txt'; marker.write_text('keep')
        with self.assertRaisesRegex(verify.ArchiveError, 'already exists'):
            verify.verify_and_extract(self.archive, self.provenance, self.dest, self.sha, self.tree)
        self.assertEqual('keep', marker.read_text())


if __name__ == '__main__':
    unittest.main()
