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

function startReleaseDownload(file, status) {
  const link = document.createElement('a');
  link.href = `/downloads/${encodeURIComponent(file.name)}`;
  link.download = file.name;
  link.click();
  status.textContent = `已开始下载 ${file.name}，请在浏览器下载列表中查看进度。`;
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
      downloadButton.addEventListener('click', () => startReleaseDownload(platformFiles.get(recommended), feedback));
    } else {
      const fallback = ['macos', 'windows', 'android', 'ios', 'harmony'].find((platform) => platformFiles.has(platform));
      downloadButton.innerHTML = `下载 ${PLATFORM_NAMES[fallback]} 版 <span aria-hidden="true">↓</span>`;
      downloadButton.setAttribute('aria-label', `下载 ${PLATFORM_NAMES[fallback]} 版`);
      iosNote.hidden = fallback !== 'ios';
      downloadButton.addEventListener('click', () => startReleaseDownload(platformFiles.get(fallback), feedback));
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
        startReleaseDownload(file, feedback);
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
