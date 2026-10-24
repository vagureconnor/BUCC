import { cp, mkdir, readdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.dirname(fileURLToPath(import.meta.url));
const dist = path.join(root, 'dist');
// dist contains generated website assets only, never local release packages.
await mkdir(dist, { recursive: true });
for (const folder of ['public', 'src']) {
  for (const name of await readdir(path.join(root, folder))) {
    if (folder === 'src' && name === 'downloads.js') continue;
    await cp(path.join(root, folder, name), path.join(dist, name), { recursive: true });
  }
}
await cp(path.join(root, 'src', 'downloads.js'), path.join(dist, 'downloads.js'));
await writeFile(path.join(dist, 'download-manifest.js'),
  'window.BUCC_RELEASE_MANIFEST = {"version":1,"files":[]};\n',
);
console.log('Built public website assets in website/dist');
