import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { onRequest } from "../functions/downloads/[file].js";

const filename = "BUCC-2.0.0-iOS-unsigned-abcdef0.ipa";
const encoder = new TextEncoder();
const parts = [encoder.encode("abcde"), encoder.encode("fghij")];
const bytes = new Uint8Array([...parts[0], ...parts[1]]);
const hash = (value) => createHash("sha256").update(value).digest("hex");
const sha256 = hash(bytes);
const manifestFile = {
  name: filename,
  size: bytes.byteLength,
  sha256,
  parts: parts.map((part, index) => ({
    url: `/downloads/parts/part-${index}`,
    size: part.byteLength,
    sha256: hash(part),
  })),
};

function fixture({ missing = false, invalidSha = false, missingPart = false } = {}) {
  const calls = [];
  const file = { ...manifestFile, sha256: invalidSha ? "bad" : sha256 };
  const mockFetch = async (input) => {
    const url = new URL(input);
    calls.push(url.pathname);
    if (url.pathname === "/download-manifest.json") {
      return new Response(JSON.stringify({ version: 1, files: missing ? [] : [file] }));
    }
    const partIndex = Number(url.pathname.at(-1));
    if (missingPart) return new Response(null, { status: 404 });
    return new Response(parts[partIndex]);
  };
  return {
    calls,
    context(fileName = filename, headers = {}, method = "GET") {
      return {
        params: { file: fileName },
        request: new Request(`https://bucc.example/downloads/${encodeURIComponent(fileName)}`, { method, headers }),
        mockFetch,
      };
    },
  };
}

async function invoke(context) {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = context.mockFetch;
  try {
    return await onRequest(context);
  } finally {
    globalThis.fetch = originalFetch;
  }
}

test("streams package chunks with attachment headers before the full file is buffered", async () => {
  const mock = fixture();
  const response = await invoke(mock.context());
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Content-Disposition"), `attachment; filename="${filename}"`);
  assert.equal(response.headers.get("Content-Length"), "10");
  assert.equal(response.headers.get("Content-Type"), "application/octet-stream");
  assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
  assert.equal(response.headers.get("ETag"), `"${sha256}"`);
  assert.equal(response.headers.get("Accept-Ranges"), "bytes");
  const body = new Uint8Array(await response.arrayBuffer());
  assert.deepEqual(body, bytes);
  assert.equal(hash(body), sha256);
  assert.deepEqual(mock.calls, ["/download-manifest.json", "/downloads/parts/part-0", "/downloads/parts/part-1"]);
});

test("supports closed, open-ended, and suffix byte ranges across chunks", async () => {
  for (const [range, expected, contentRange] of [
    ["bytes=2-6", "cdefg", "bytes 2-6/10"],
    ["bytes=7-", "hij", "bytes 7-9/10"],
    ["bytes=-3", "hij", "bytes 7-9/10"],
  ]) {
    const response = await invoke(fixture().context(filename, { Range: range }));
    assert.equal(response.status, 206);
    assert.equal(await response.text(), expected);
    assert.equal(response.headers.get("Content-Range"), contentRange);
  }
});

test("rejects malformed, multiple, and unsatisfiable ranges", async () => {
  for (const range of ["bytes=20-", "bytes=3-2", "bytes=-0", "bytes=0-1,4-5", "items=0-1"]) {
    const response = await invoke(fixture().context(filename, { Range: range }));
    assert.equal(response.status, 416, range);
    assert.equal(response.headers.get("Content-Range"), "bytes */10");
  }
});

test("rejects non-release filenames before looking up the manifest", async () => {
  const mock = fixture();
  assert.equal((await invoke(mock.context("../secrets.ipa"))).status, 404);
  assert.deepEqual(mock.calls, []);
});

test("returns 404 for missing releases and 503 for invalid package metadata", async () => {
  assert.equal((await invoke(fixture({ missing: true }).context())).status, 404);
  assert.equal((await invoke(fixture({ invalidSha: true }).context())).status, 503);
});

test("HEAD returns package metadata without fetching the package chunks", async () => {
  const mock = fixture();
  const response = await invoke(mock.context(filename, { Range: "bytes=1-3" }, "HEAD"));
  assert.equal(response.status, 206);
  assert.equal(response.headers.get("Content-Length"), "3");
  assert.equal(response.headers.get("Content-Range"), "bytes 1-3/10");
  assert.deepEqual(mock.calls, ["/download-manifest.json"]);
});

test("rejects unsupported methods without looking up the manifest", async () => {
  const mock = fixture();
  assert.equal((await invoke(mock.context(filename, {}, "POST"))).status, 405);
  assert.equal(mock.calls.length, 0);
});

test("reports a missing package chunk as a stream failure", async () => {
  const response = await invoke(fixture({ missingPart: true }).context());
  await assert.rejects(response.arrayBuffer());
});
