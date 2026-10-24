import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { packageReleases } from '../package-releases.mjs';

const execFileAsync = promisify(execFile);
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

test('release package parts stay below the Pages asset cap and reconstruct exactly', async () => {
  const temporaryDir = await mkdtemp(path.join(os.tmpdir(), 'bucc-release-package-test-'));
  try {
    const source = path.join(temporaryDir, 'BUCC-2.0.0-test.ipa');
    const bytes = Buffer.from(Array.from({ length: 157 }, (_, index) => index % 251));
    await writeFile(source, bytes);
    const dist = path.join(temporaryDir, 'dist');
    const manifest = await packageReleases([source], { chunkSize: 24, distDir: dist });
    const entry = manifest.files[0];
    assert.equal(entry.sha256, sha256(bytes));
    assert.equal(entry.size, bytes.length);
    assert.ok(entry.parts.length > 1);
    const joined = [];
    for (const part of entry.parts) {
      const partBytes = await readFile(path.join(dist, part.url.replace('/downloads/', 'downloads/')));
      assert.ok(partBytes.length <= 24);
      assert.equal(partBytes.length, part.size);
      assert.equal(sha256(partBytes), part.sha256);
      joined.push(partBytes);
    }
    assert.deepEqual(Buffer.concat(joined), bytes);
    assert.deepEqual(JSON.parse(await readFile(path.join(dist, 'download-manifest.json'), 'utf8')), manifest);
  } finally {
    await execFileAsync('/usr/bin/trash', [temporaryDir]);
  }
});

test('release packager rejects non-release names and oversized chunks', async () => {
  const temporaryDir = await mkdtemp(path.join(os.tmpdir(), 'bucc-release-package-test-'));
  try {
    await assert.rejects(packageReleases([], { chunkSize: 25 * 1024 * 1024 + 1, distDir: temporaryDir }), /Chunk size/);
    await assert.rejects(packageReleases(['/tmp/not-a-release.apk'], { distDir: temporaryDir }), /Unsupported release filename/);
  } finally {
    await execFileAsync('/usr/bin/trash', [temporaryDir]);
  }
});
