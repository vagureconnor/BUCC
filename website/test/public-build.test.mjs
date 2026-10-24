import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const root = new URL('../', import.meta.url);
const read = (name) => readFile(new URL(name, root));
test('public deployment includes only reviewed static assets', async () => {
  assert.deepEqual((await readdir(new URL('dist/', root))).sort(),
    ['_headers','_routes.json','aircraft.png','apple-touch-icon.png','download-manifest.json','downloads.js','favicon.png','index.html','main.js','og-image.png','privacy.html','style.css']);
  for (const file of ['index.html', 'privacy.html', 'main.js', 'downloads.js', 'download-manifest.json', 'style.css', '_routes.json']) {
    const text = (await read(`dist/${file}`)).toString();
    assert.doesNotMatch(text, /\/Users\/|ProvisionedDevices|BEGIN .*PRIVATE KEY|oauth_token|[0-9a-f]{40}-[0-9a-f]{24}/i);
  }
});
test('original airplane asset is unchanged and website submits no credentials', async () => {
  const hash = (b) => createHash('sha256').update(b).digest('hex');
  assert.equal(hash(await read('dist/aircraft.png')), hash(await read('../composeApp/icons/aircraft-cutout.png')));
  const index = (await read('dist/index.html')).toString();
  assert.doesNotMatch(index, /<form\b|<input\b|itms-services:/i);
  assert.doesNotMatch(index, /<nav\b/i);
  const ipaHrefs = [...index.matchAll(/href\s*=\s*["']([^"']*\.ipa(?:\?[^"']*)?)["']/gi)].map((match) => match[1]);
  assert.deepEqual(ipaHrefs, []);
  const headers = (await read('dist/_headers')).toString();
  assert.match(headers, /connect-src 'none'/);
  assert.match(headers, /form-action 'none'/);
});
test('all platform actions resolve to real status panels', async () => {
  const index = (await read('dist/index.html')).toString();
  for (const match of index.matchAll(/href="#([a-z]+-detail)"/g)) {
    assert.ok(index.includes(`<details id="${match[1]}"`), match[1]);
  }
  assert.match(index, /BUCC 2.0.0/);
  assert.match(index, /正式签名构建/);
  assert.match(index, /安装前需要自行签名/);
  assert.match(index, /不含设备描述文件/);
  assert.match(index, /SHA-256/i);
  assert.match(index, /官方充值流程/);
  assert.deepEqual([...index.matchAll(/data-platform="([^"]+)"/g)].map((match) => match[1]),
    ['macos', 'android', 'windows', 'ios', 'harmony']);
  const downloads = (await read('dist/downloads.js')).toString();
  assert.match(downloads, /selectedFiles = new Map\(\)/);
  assert.match(downloads, /card\.append\(button, status\)/);
  assert.doesNotMatch(downloads, /container\.append\(row\)/);
  const routes = JSON.parse((await read('dist/_routes.json')).toString());
  assert.deepEqual(routes, { version: 1, include: ['/downloads/*'], exclude: [] });
});

test('privacy copy remains consistent with a static site and unsigned iOS download', async () => {
  const privacy = (await read('dist/privacy.html')).toString();
  assert.match(privacy, /不设置收集账号、密码、会话 Cookie、二维码或设备标识的功能/);
  assert.match(privacy, /Cloudflare/);
  assert.match(privacy, /不包含设备描述文件/);
  assert.match(privacy, /不收集签名资料或 Apple 账号凭据/);
  assert.doesNotMatch(privacy, /上传设备 UDID|提交校园密码/);
});
