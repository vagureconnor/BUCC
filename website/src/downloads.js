const PLATFORM_NAMES = {
  macos: 'macOS',
  windows: 'Windows',
  android: 'Android',
  ios: 'iOS',
  harmony: 'HarmonyOS',
};

const container = document.querySelector('[data-download-cta]');
const downloadButton = container?.querySelector('[data-main-download]');
const menuToggle = container?.querySelector('[data-menu-toggle]');
const menu = container?.querySelector('[data-platform-menu]');
const platformOptions = container?.querySelector('[data-platform-options]');
const iosNote = container?.querySelector('[data-ios-note]');
const feedback = document.querySelector('[data-download-feedback]');

function detectPlatform() {
  const ua = navigator.userAgent || '';
  const platform = navigator.userAgentData?.platform || navigator.platform || '';
  const signature = `${platform} ${ua}`.toLowerCase();
  if (/harmony/.test(signature)) return 'harmony';
  if (/android/.test(signature)) return 'android';
  if (/iphone|ipad|ipod/.test(signature) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)) return 'ios';
  if (/windows/.test(signature)) return 'windows';
  if (/macintosh|mac os|macintel/.test(signature)) return 'macos';
  return null;
}

function platformFor(filename) {
  const name = filename.toLowerCase();
  if (/macos|macos-arm/.test(name)) return 'macos';
  if (/android|\.apk$/.test(name)) return 'android';
  if (/windows|\.exe$/.test(name)) return 'windows';
  if (/harmony|\.hap$/.test(name)) return 'harmony';
  if (/ios|\.ipa$/.test(name)) return 'ios';
  return null;
}

function downloadPriority(file, platform) {
  const name = file.name.toLowerCase();
  if (platform === 'macos') return name.includes('arm64') ? 3 : name.includes('universal') ? 2 : 1;
  if (platform === 'android') return name.endsWith('.apk') ? 2 : 1;
  if (platform === 'windows') return name.endsWith('.exe') ? 2 : 1;
  if (platform === 'harmony') return name.endsWith('.hap') ? 2 : 1;
  return name.includes('unsigned') ? 2 : 1;
}

async function sha256(bytes) {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function fetchWithRetry(url) {
  let response;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      response = await fetch(url, { cache: 'no-store', credentials: 'same-origin' });
      if (response.ok || response.status < 500) return response;
    } catch (error) {
      if (attempt === 2) throw error;
    }
    await delay(400 * (attempt + 1));
  }
  return response;
}

let busy = false;
async function saveRelease(file, button, status) {
  if (busy) return;
  busy = true;
  button.disabled = true;
  if (downloadButton) downloadButton.disabled = true;
  if (menuToggle) menuToggle.disabled = true;
  const pieces = [];
  let writer;
  try {
    if ('showSaveFilePicker' in window) {
      const handle = await window.showSaveFilePicker({ suggestedName: file.name });
      writer = await handle.createWritable();
    }
    for (let index = 0; index < file.parts.length; index += 1) {
      const part = file.parts[index];
      status.textContent = `正在下载并校验第 ${index + 1}/${file.parts.length} 段…`;
      const response = await fetchWithRetry(part.url);
      if (!response?.ok) throw new Error(`分段暂时无法下载（HTTP ${response?.status ?? '网络错误'}），请重试。`);
      const bytes = await response.arrayBuffer();
      if (bytes.byteLength !== part.size || await sha256(bytes) !== part.sha256) {
        throw new Error(`第 ${index + 1} 段校验失败，请重试。`);
      }
      if (writer) await writer.write(bytes);
      else pieces.push(bytes);
    }
    if (writer) {
      await writer.close();
    } else {
      const blob = new Blob(pieces, { type: 'application/octet-stream' });
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = file.name;
      link.click();
      setTimeout(() => URL.revokeObjectURL(link.href), 60_000);
    }
    status.textContent = `下载完成 · SHA-256 ${file.sha256}`;
  } catch (error) {
    if (writer) await writer.abort().catch(() => {});
    status.textContent = error.name === 'AbortError' ? '已取消下载。' : error.message;
  } finally {
    busy = false;
    button.disabled = false;
    if (downloadButton) downloadButton.disabled = false;
    if (menuToggle) menuToggle.disabled = false;
  }
}

function setMenuOpen(open) {
  if (!menu || !menuToggle) return;
  menu.hidden = !open;
  menuToggle.setAttribute('aria-expanded', String(open));
}

if (container && downloadButton && menuToggle && menu && platformOptions && feedback) {
  const detectedPlatform = detectPlatform();
  const platformFiles = new Map();
  try {
    const manifest = window.BUCC_RELEASE_MANIFEST;
    if (!manifest || !Array.isArray(manifest.files)) throw new Error('下载列表暂时不可用，请稍后重试。');
    for (const file of manifest.files ?? []) {
      const platform = platformFor(file.name);
      if (!platform) continue;
      const previous = platformFiles.get(platform);
      if (!previous || downloadPriority(file, platform) > downloadPriority(previous, platform)) {
        platformFiles.set(platform, file);
      }
    }
    if (!platformFiles.size) throw new Error('暂时没有可用的安装包，请稍后重试。');

    const recommended = platformFiles.has(detectedPlatform) ? detectedPlatform : null;
    if (recommended) {
      downloadButton.innerHTML = `下载 ${PLATFORM_NAMES[recommended]} 版 <span aria-hidden="true">↓</span>`;
      downloadButton.setAttribute('aria-label', `下载 ${PLATFORM_NAMES[recommended]} 版`);
      iosNote.hidden = recommended !== 'ios';
      downloadButton.addEventListener('click', () => saveRelease(platformFiles.get(recommended), downloadButton, feedback));
    } else {
      const fallback = ['macos', 'windows', 'android', 'ios', 'harmony'].find((platform) => platformFiles.has(platform));
      downloadButton.innerHTML = `下载 ${PLATFORM_NAMES[fallback]} 版 <span aria-hidden="true">↓</span>`;
      downloadButton.setAttribute('aria-label', `下载 ${PLATFORM_NAMES[fallback]} 版`);
      iosNote.hidden = fallback !== 'ios';
      downloadButton.addEventListener('click', () => saveRelease(platformFiles.get(fallback), downloadButton, feedback));
    }
    downloadButton.disabled = false;

    for (const [platform, file] of platformFiles) {
      if (platform === recommended) continue;
      const row = document.createElement('div');
      row.className = 'platform-option-row';
      row.setAttribute('role', 'none');
      const option = document.createElement('button');
      option.type = 'button';
      option.className = 'platform-option';
      option.setAttribute('role', 'menuitem');
      option.textContent = `下载 ${PLATFORM_NAMES[platform]} 版`;
      option.addEventListener('click', () => {
        setMenuOpen(false);
        iosNote.hidden = platform !== 'ios';
        saveRelease(file, option, feedback);
      });
      row.append(option);
      if (platform === 'ios') {
        const note = document.createElement('span');
        note.className = 'menu-ios-note';
        note.textContent = '需要签名后才能使用';
        row.append(note);
      }
      platformOptions.append(row);
    }
    if (!platformOptions.children.length) {
      const option = document.createElement('button');
      option.type = 'button';
      option.className = 'platform-option';
      option.textContent = '已是当前系统推荐版本';
      option.disabled = true;
      platformOptions.append(option);
    }
  } catch (error) {
    downloadButton.disabled = true;
    downloadButton.textContent = '暂时无法下载';
    menuToggle.disabled = true;
    feedback.textContent = error.message;
  }

  menuToggle.addEventListener('click', () => setMenuOpen(menu.hidden));
  document.addEventListener('click', (event) => {
    if (!container.contains(event.target)) setMenuOpen(false);
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') setMenuOpen(false);
  });
}
