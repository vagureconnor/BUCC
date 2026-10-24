import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const root = new URL('../', import.meta.url);
const read = (name) => readFile(new URL(name, root));
test('public deployment includes only reviewed static assets', async () => {
  assert.deepEqual((await readdir(new URL('dist/', root))).filter((name) => name !== 'downloads').sort(),
    ['_headers','_routes.json','aircraft.png','apple-touch-icon.png','campus-spaces.webp','download-manifest.js','download-manifest.json','downloads.js','favicon.png','homework-study.webp','index.html','main.js','og-image.png','privacy.html','style.css']);
  for (const file of ['index.html', 'privacy.html', 'main.js', 'downloads.js', 'download-manifest.js', 'download-manifest.json', 'style.css', '_routes.json']) {
    const text = (await read(`dist/${file}`)).toString();
    assert.doesNotMatch(text, /\/Users\/|ProvisionedDevices|BEGIN .*PRIVATE KEY|oauth_token|[0-9a-f]{40}-[0-9a-f]{24}/i);
  }
});
test('original airplane asset is unchanged and website submits no credentials', async () => {
  const hash = (b) => createHash('sha256').update(b).digest('hex');
  assert.equal(hash(await read('dist/aircraft.png')), hash(await read('../composeApp/icons/aircraft-cutout.png')));
  const index = (await read('dist/index.html')).toString();
  assert.doesNotMatch(index, /<form\b|<input\b|itms-services:/i);
  assert.doesNotMatch(index, /<nav\b|校园日常<\/a>|功能一览|href="#downloads"/i);
  const ipaHrefs = [...index.matchAll(/href\s*=\s*["']([^"']*\.ipa(?:\?[^"']*)?)["']/gi)].map((match) => match[1]);
  assert.deepEqual(ipaHrefs, []);
  const headers = (await read('dist/_headers')).toString();
  assert.match(headers, /connect-src 'self'/);
  assert.match(headers, /form-action 'none'/);
});
test('the site presents one adaptive download control and removes release notes', async () => {
  const index = (await read('dist/index.html')).toString();
  assert.match(index, /BUCC 2.0.0/);
  assert.doesNotMatch(index, /选择你的平台|了解功能|下载说明|更新与使用说明|正在准备验证码/);
  assert.equal([...index.matchAll(/data-main-download/g)].length, 1);
  assert.doesNotMatch(index, /schedule-card|<nav\b/i);
  assert.match(index, /需要签名后才能使用/);
  assert.match(index, /data-platform-menu/);
  assert.match(index, /校园空间|校园事务/);
  assert.match(index, /campus-spaces\.webp/);
  assert.match(index, /homework-study\.webp/);
  assert.match(index, /iHome 接诉即办与校园公告/);
  assert.doesNotMatch(index, /iHome.{0,20}学习安排|也可进入 iHome 等校园服务/);
  assert.match(index, /PatPat 在直连模式下可用，WebVPN 模式下暂不可用/);
  assert.doesNotMatch(index, /PatPat 服务目前暂停/);
  assert.match(index, /prefers-color-scheme: dark/);
  const downloads = (await read('dist/downloads.js')).toString();
  assert.match(downloads, /function detectPlatform\(\)/);
  assert.match(downloads, /userAgentData/);
  assert.match(downloads, /fetchWithRetry/);
  assert.match(downloads, /crypto\.subtle\.digest/);
  assert.doesNotMatch(downloads, /load failed/i);
  assert.match(downloads, /window\.BUCC_RELEASE_MANIFEST/);
  assert.match(downloads, /document\.querySelector\('\[data-download-feedback\]'\)/);
  assert.doesNotMatch(downloads, /fetchWithRetry\('\/download-manifest\.json'\)/);
  assert.match(index, /download-manifest\.js/);
  const main = (await read('dist/main.js')).toString();
  assert.match(main, /IntersectionObserver/);
  const styles = (await read('dist/style.css')).toString();
  assert.match(styles, /prefers-reduced-motion/);
  assert.match(styles, /@keyframes planeFloat/);
  assert.match(styles, /prefers-color-scheme:dark/);
  assert.match(styles, /grid-template-columns:repeat\(2,minmax\(0,1fr\)\)/);
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
