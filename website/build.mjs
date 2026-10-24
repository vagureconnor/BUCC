import { access, cp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.dirname(fileURLToPath(import.meta.url));
const dist = path.join(root, 'dist');
let releaseManifest = { version: 1, files: [] };
try {
  const previousManifest = JSON.parse(await readFile(path.join(dist, 'download-manifest.json'), 'utf8'));
  if (previousManifest.version === 1 && previousManifest.files.length > 0) {
    await Promise.all(previousManifest.files.flatMap((file) => file.parts.map((part) =>
      access(path.join(dist, part.url.slice(1))))));
    releaseManifest = previousManifest;
  }
} catch {}
// Retain packaged release chunks already staged in dist.
await mkdir(dist, { recursive: true });
for (const folder of ['public', 'src']) {
  for (const name of await readdir(path.join(root, folder))) {
    if (folder === 'src' && name === 'downloads.js') continue;
    await cp(path.join(root, folder, name), path.join(dist, name), { recursive: true });
  }
}
await cp(path.join(root, 'src', 'downloads.js'), path.join(dist, 'downloads.js'));
await writeFile(path.join(dist, 'download-manifest.json'), `${JSON.stringify(releaseManifest, null, 2)}\n`);
await writeFile(path.join(dist, 'download-manifest.js'),
  `window.BUCC_RELEASE_MANIFEST = ${JSON.stringify(releaseManifest)};\n`,
);
console.log('Built public website assets in website/dist');
