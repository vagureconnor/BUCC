import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { onRequest } from "../functions/downloads/[file].js";

const filename = "BUCC-2.0.0-iOS-unsigned-abcdef0.ipa";
const bytes = new TextEncoder().encode("abcdefghij");
const sha256 = createHash("sha256").update(bytes).digest("hex");
const get = (context) => onRequest({ ...context, request: new Request(context.request, { method: "GET" }) });
const head = (context) => onRequest({ ...context, request: new Request(context.request, { method: "HEAD" }) });

function fixture({ missing = false, invalidSha = false } = {}) {
  const calls = [];
  const object = {
    size: bytes.byteLength,
    customMetadata: { sha256: invalidSha ? "bad" : sha256 },
    body: new Blob([bytes]).stream(),
  };
  const bucket = {
    async head(key) {
      calls.push(["head", key]);
      return missing ? null : object;
    },
    async get(key, options) {
      calls.push(["get", key, options]);
      if (missing) return null;
      const range = options?.range;
      return { ...object, body: new Blob([range ? bytes.slice(range.offset, range.offset + range.length) : bytes]).stream() };
    },
  };
  return {
    calls,
    context(file = filename, headers = {}) {
      return {
        params: { file },
        env: { BUCC_RELEASES_R2: bucket },
        request: new Request("https://bucc.example/downloads/" + encodeURIComponent(file), { headers }),
      };
    },
  };
}

test("streams complete bytes from R2 and serves safe immutable headers", async () => {
  const mock = fixture();
  const response = await get(mock.context());
  const body = new Uint8Array(await response.arrayBuffer());
  assert.equal(response.status, 200);
  assert.deepEqual(body, bytes);
  assert.equal(createHash("sha256").update(body).digest("hex"), sha256);
  assert.equal(response.headers.get("Content-Length"), "10");
  assert.equal(response.headers.get("Accept-Ranges"), "bytes");
  assert.equal(response.headers.get("Content-Type"), "application/octet-stream");
  assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
  assert.equal(response.headers.get("ETag"), `"${sha256}"`);
  assert.match(response.headers.get("Cache-Control"), /immutable/);
});

test("supports closed, open-ended, and suffix byte ranges", async () => {
  for (const [range, expected, contentRange] of [
    ["bytes=2-6", "cdefg", "bytes 2-6/10"],
    ["bytes=7-", "hij", "bytes 7-9/10"],
    ["bytes=-3", "hij", "bytes 7-9/10"],
  ]) {
    const response = await get(fixture().context(filename, { Range: range }));
    assert.equal(response.status, 206);
    assert.equal(await response.text(), expected);
    assert.equal(response.headers.get("Content-Range"), contentRange);
  }
});

test("rejects malformed, multiple, and unsatisfiable ranges", async () => {
  for (const range of ["bytes=20-", "bytes=3-2", "bytes=-0", "bytes=0-1,4-5", "items=0-1"]) {
    const response = await get(fixture().context(filename, { Range: range }));
    assert.equal(response.status, 416, range);
    assert.equal(response.headers.get("Content-Range"), "bytes */10");
  }
});

test("rejects non-release filenames before touching R2", async () => {
  const mock = fixture();
  const response = await get(mock.context("../secrets.ipa"));
  assert.equal(response.status, 404);
  assert.equal(mock.calls.length, 0);
});

test("returns 404 for missing releases and 503 for missing SHA metadata", async () => {
  assert.equal((await get(fixture({ missing: true }).context())).status, 404);
  assert.equal((await get(fixture({ invalidSha: true }).context())).status, 503);
});

test("HEAD returns metadata without reading object bytes", async () => {
  const mock = fixture();
  const response = await head(mock.context(filename, { Range: "bytes=1-3" }));
  assert.equal(response.status, 206);
  assert.equal(response.headers.get("Content-Length"), "3");
  assert.equal(response.headers.get("Content-Range"), "bytes 1-3/10");
  assert.deepEqual(mock.calls, [["head", filename]]);
});

test("rejects unsupported methods without reading R2", async () => {
  const mock = fixture();
  const response = await onRequest({ ...mock.context(), request: new Request("https://bucc.example", { method: "POST" }) });
  assert.equal(response.status, 405);
  assert.equal(response.headers.get("Allow"), "GET, HEAD");
  assert.equal(mock.calls.length, 0);
});
