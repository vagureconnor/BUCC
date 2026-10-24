import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, open, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const outputDir = path.join(root, 'dist', 'downloads', 'parts');
export const DEFAULT_CHUNK_SIZE = 24 * 1024 * 1024;
const RELEASE_FILE = /^BUCC-2\.0\.0-[A-Za-z0-9][A-Za-z0-9._-]*\.(?:dmg|msi|exe|apk|aab|ipa|hap|app)$/i;

export async function packageReleases(files, { chunkSize = DEFAULT_CHUNK_SIZE, distDir = path.join(root, 'dist') } = {}) {
  if (!Number.isSafeInteger(chunkSize) || chunkSize <= 0 || chunkSize > DEFAULT_CHUNK_SIZE) {
    throw new Error(`Chunk size must be between 1 byte and ${DEFAULT_CHUNK_SIZE} bytes`);
  }
  const partsDir = path.join(distDir, 'downloads', 'parts');
  await mkdir(partsDir, { recursive: true });
  const manifestFiles = [];

  for (const inputPath of files) {
    const name = path.basename(inputPath);
    if (!RELEASE_FILE.test(name)) throw new Error(`Unsupported release filename: ${name}`);
    const fileSize = (await stat(inputPath)).size;
    if (fileSize <= 0) throw new Error(`Release file is empty: ${name}`);
    const wholeHash = createHash('sha256');
    const parts = [];
    let partIndex = 0;

    for (let offset = 0; offset < fileSize; offset += chunkSize) {
      const length = Math.min(chunkSize, fileSize - offset);
      const partName = `${name}.part-${String(partIndex).padStart(4, '0')}`;
      const partPath = path.join(partsDir, partName);
      const partHash = createHash('sha256');
      const destination = await open(partPath, 'w');
      let written = 0;
      try {
        for await (const buffer of createReadStream(inputPath, { start: offset, end: offset + length - 1 })) {
          wholeHash.update(buffer);
          partHash.update(buffer);
          await destination.write(buffer);
          written += buffer.length;
        }
      } finally {
        await destination.close();
      }
      if (written !== length) throw new Error(`Short read while packaging ${name}`);
      parts.push({
        url: `/downloads/parts/${partName}`,
        size: written,
        sha256: partHash.digest('hex'),
      });
      partIndex += 1;
    }
    manifestFiles.push({ name, size: fileSize, sha256: wholeHash.digest('hex'), parts });
  }

  const manifest = { version: 1, files: manifestFiles };
  await mkdir(distDir, { recursive: true });
  const manifestHandle = await open(path.join(distDir, 'download-manifest.json'), 'w');
  try { await manifestHandle.writeFile(`${JSON.stringify(manifest, null, 2)}\n`); }
  finally { await manifestHandle.close(); }
  await writeFile(path.join(distDir, 'download-manifest.js'),
    `window.BUCC_RELEASE_MANIFEST = ${JSON.stringify(manifest)};\n`);
  return manifest;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const files = process.argv.slice(2);
  if (!files.length) {
    console.error('Usage: npm run package:releases -- <BUCC-2.0.0-package> [...]');
    process.exitCode = 2;
  } else {
    const manifest = await packageReleases(files);
    for (const file of manifest.files) {
      console.log(`${file.name}: ${file.parts.length} part(s), ${file.size} bytes, SHA-256 ${file.sha256}`);
    }
  }
}
