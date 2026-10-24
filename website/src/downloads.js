const container = document.querySelector('[data-release-downloads]');
const platforms = new Map(
  [...(container?.querySelectorAll('[data-platform]') ?? [])].map((card) => [card.dataset.platform, card]),
);

function platformFor(name) {
  if (/macos/i.test(name)) return 'macos';
  if (/android/i.test(name)) return 'android';
  if (/windows/i.test(name)) return 'windows';
  if (/ios/i.test(name)) return 'ios';
  if (/harmony/i.test(name)) return 'harmony';
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

async function saveRelease(file, button, status) {
  button.disabled = true;
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
      const response = await fetch(part.url);
      if (!response.ok) throw new Error(`分片下载失败（HTTP ${response.status}）`);
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
    status.textContent = `完成。文件 SHA-256：${file.sha256}`;
  } catch (error) {
    if (writer) await writer.abort().catch(() => {});
    status.textContent = error.name === 'AbortError' ? '已取消下载。' : `下载未完成：${error.message}`;
  } finally {
    button.disabled = false;
  }
}

if (container) {
  fetch('/download-manifest.json')
    .then((response) => {
      if (!response.ok) throw new Error('还没有可下载的发布文件。');
      return response.json();
    })
    .then((manifest) => {
      const selectedFiles = new Map();
      for (const file of manifest.files ?? []) {
        const platform = platformFor(file.name);
        if (!platform || !platforms.has(platform)) continue;
        const current = selectedFiles.get(platform);
        if (!current || downloadPriority(file, platform) > downloadPriority(current, platform)) {
          selectedFiles.set(platform, file);
        }
      }
      for (const [platform, card] of platforms) {
        const file = selectedFiles.get(platform);
        if (!file) continue;
        const button = document.createElement('button');
        button.className = 'button primary';
        button.textContent = `下载 ${file.name}`;
        const status = document.createElement('p');
        status.className = 'download-status';
        button.addEventListener('click', () => saveRelease(file, button, status));
        card.append(button, status);
      }
      for (const [platform, card] of platforms) {
        if (!selectedFiles.has(platform)) {
          const status = document.createElement('span');
          status.className = 'status neutral';
          status.textContent = '暂未提供下载';
          card.append(status);
        }
      }
    })
    .catch((error) => { container.textContent = error.message; });
}
