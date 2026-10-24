const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { inflateSync } = require('node:zlib');

const repoRoot = path.resolve(__dirname, '../../../..');
const resourceRoot = path.join(repoRoot, 'composeApp/src/commonMain/composeResources/files/payment-qr');
const fixturePath = path.join(__dirname, '../resources/payment-qr/official-fixture.png');
const bundle = fs.readFileSync(path.join(resourceRoot, 'jsqr-1.4.0.js'), 'utf8');
const scanner = fs.readFileSync(path.join(resourceRoot, 'payment-qr-scanner.js'), 'utf8');
const fixture = decodePng(fs.readFileSync(fixturePath));

function decodePng(buffer) {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  assert.deepEqual(buffer.subarray(0, 8), signature, 'fixture is a PNG');
  let offset = 8;
  let width;
  let height;
  let bitDepth;
  let colorType;
  const idat = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += length + 12;
  }
  assert.equal(bitDepth, 8, 'fixture uses 8-bit pixels');
  assert.ok(colorType === 6 || colorType === 2, 'fixture uses RGBA or RGB pixels');
  const channels = colorType === 6 ? 4 : 3;
  const rowBytes = width * channels;
  const compressed = inflateSync(Buffer.concat(idat));
  const raw = Buffer.alloc(height * rowBytes);
  let input = 0;
  for (let y = 0; y < height; y += 1) {
    const filter = compressed[input++];
    for (let x = 0; x < rowBytes; x += 1) {
      const value = compressed[input++];
      const left = x >= channels ? raw[y * rowBytes + x - channels] : 0;
      const up = y > 0 ? raw[(y - 1) * rowBytes + x] : 0;
      const upperLeft = y > 0 && x >= channels ? raw[(y - 1) * rowBytes + x - channels] : 0;
      let predictor = 0;
      if (filter === 1) predictor = left;
      else if (filter === 2) predictor = up;
      else if (filter === 3) predictor = Math.floor((left + up) / 2);
      else if (filter === 4) predictor = paeth(left, up, upperLeft);
      else assert.equal(filter, 0, `supported PNG filter ${filter}`);
      raw[y * rowBytes + x] = (value + predictor) & 255;
    }
  }
  const rgba = new Uint8ClampedArray(width * height * 4);
  for (let i = 0, p = 0; i < width * height; i += 1, p += channels) {
    const out = i * 4;
    rgba[out] = raw[p];
    rgba[out + 1] = raw[p + 1];
    rgba[out + 2] = raw[p + 2];
    rgba[out + 3] = channels === 4 ? raw[p + 3] : 255;
  }
  return { width, height, pixels: rgba };
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
}

function runScanner(image, shouldTaint = false) {
  const element = {
    tagName: 'IMG',
    naturalWidth: image.width,
    naturalHeight: image.height,
    getBoundingClientRect: () => ({ width: image.width, height: image.height }),
    get src() { throw new Error('scanner must decode pixels instead of reading the image URL'); },
    pixels: image.pixels,
  };
  const sandbox = {
    window: { getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity: '1' }) },
    document: {
      querySelectorAll: (selector) => {
        assert.equal(selector, 'img, canvas');
        return [element];
      },
      createElement: (tagName) => {
        assert.equal(tagName, 'canvas');
        const canvas = { width: 0, height: 0 };
        const context = {
          drawImage: (source) => {
            assert.equal(source, element);
            if (shouldTaint) {
              const error = new Error('tainted canvas');
              error.name = 'SecurityError';
              throw error;
            }
            canvas.pixels = source.pixels;
          },
          getImageData: () => ({ data: canvas.pixels }),
        };
        canvas.getContext = () => context;
        return canvas;
      },
    },
  };
  return JSON.parse(vm.runInNewContext(`${bundle}\n;${scanner}`, sandbox));
}

test('decodes a real local synthetic PNG QR from displayed pixels', () => {
  assert.deepEqual(runScanner(fixture), ['https://cashier.cc-pay.cn/cashier?order=synthetic-fixture']);
});

test('blank image yields no payload and a tainted image has a host-visible marker', () => {
  const blank = { width: 64, height: 64, pixels: new Uint8ClampedArray(64 * 64 * 4).fill(255) };
  for (let i = 3; i < blank.pixels.length; i += 4) blank.pixels[i] = 255;
  assert.deepEqual(runScanner(blank), []);
  assert.deepEqual(runScanner(fixture, true), ['__BUCC_QR_CROSS_ORIGIN__']);
});

test('official QR carrier is not starved by decorative navigation candidates', () => {
  const kotlin = fs.readFileSync(path.join(repoRoot, 'composeApp/src/commonMain/kotlin/app/bucc/ui/screens/finance/PaymentNavigationRules.kt'), 'utf8');
  const script = kotlin.match(/PAYMENT_RESPONSE_CANDIDATES_SCRIPT = """([\s\S]*?)"""/)[1];
  const qr = { src: 'https://zfw.buaa.edu.cn/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Forder%3Dsynthetic' };
  const decoration = Array.from({length: 70}, (_, i) => ({ href: `https://zfw.buaa.edu.cn/menu/${i}` }));
  const sandbox = { document: { querySelectorAll: selector => selector.includes('qrcode') ? [qr] : [...decoration, qr] } };
  const values = JSON.parse(vm.runInNewContext(script, sandbox));
  assert.ok(values.includes(qr.src));
  assert.ok(values.length <= 64);
});
