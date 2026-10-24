const FILENAME = /^BUCC-2\.0\.0-[A-Za-z0-9][A-Za-z0-9._-]*\.(?:dmg|msi|exe|apk|aab|ipa|hap|app)$/i;
const SHA256 = /^[a-f0-9]{64}$/;

function failure(status, headers = {}) {
  return new Response(null, {
    status,
    headers: { "X-Content-Type-Options": "nosniff", ...headers },
  });
}

function parseRange(header, size) {
  if (!header) return { offset: 0, length: size, start: 0, end: size - 1, partial: false };
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match || (!match[1] && !match[2])) return null;
  let start;
  let end;
  if (!match[1]) {
    const suffix = Number(match[2]);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return null;
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(match[1]);
    end = match[2] ? Number(match[2]) : size - 1;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start >= size || end < start) return null;
    end = Math.min(end, size - 1);
  }
  return { offset: start, length: end - start + 1, start, end, partial: true };
}

function streamParts(parts, origin, range, fetcher) {
  const end = range.offset + range.length;
  let fileOffset = 0;
  const selected = parts.flatMap((part) => {
    const partStart = fileOffset;
    fileOffset += part.size;
    const start = Math.max(0, range.offset - partStart);
    const stop = Math.min(part.size, end - partStart);
    return start < stop ? [{ ...part, start, stop }] : [];
  });
  let index = 0;
  let reader;
  let partOffset = 0;

  return new ReadableStream({
    async pull(controller) {
      try {
        while (index < selected.length) {
          const part = selected[index];
          if (!reader) {
            const url = new URL(part.url, origin);
            if (url.origin !== origin || !url.pathname.startsWith("/downloads/parts/")) throw new Error("Invalid package part URL");
            const response = await fetcher(url);
            if (!response.ok || !response.body) throw new Error("Package part unavailable");
            reader = response.body.getReader();
            partOffset = 0;
          }
          const { done, value } = await reader.read();
          if (done) {
            reader = null;
            index += 1;
            continue;
          }
          const chunkStart = partOffset;
          const chunkEnd = partOffset + value.byteLength;
          partOffset = chunkEnd;
          const start = Math.max(part.start, chunkStart);
          const stop = Math.min(part.stop, chunkEnd);
          if (start < stop) {
            controller.enqueue(value.subarray(start - chunkStart, stop - chunkStart));
            if (chunkEnd >= part.stop) {
              await reader.cancel();
              reader = null;
              index += 1;
            }
            return;
          }
          if (chunkEnd >= part.stop) {
            await reader.cancel();
            reader = null;
            index += 1;
          }
        }
        controller.close();
      } catch (error) {
        controller.error(error);
      }
    },
    async cancel() {
      await reader?.cancel();
    },
  });
}

export async function onRequest(context) {
  const { request, params } = context;
  const filename = params?.file;
  if (typeof filename !== "string" || !FILENAME.test(filename)) return failure(404);
  if (request.method !== "GET" && request.method !== "HEAD") return failure(405, { Allow: "GET, HEAD" });

  const fetcher = fetch.bind(globalThis);
  let manifest;
  try {
    const response = await fetcher(new URL("/download-manifest.json", request.url), { cache: "no-store" });
    if (!response.ok) return failure(503);
    manifest = await response.json();
  } catch {
    return failure(503);
  }
  const file = manifest?.version === 1 && Array.isArray(manifest.files)
    ? manifest.files.find((entry) => entry.name === filename)
    : null;
  if (!file) return failure(404);
  if (!Number.isSafeInteger(file.size) || file.size <= 0 || !SHA256.test(file.sha256 ?? "") || !Array.isArray(file.parts) || !file.parts.length) {
    return failure(503);
  }
  if (file.parts.some((part) => !Number.isSafeInteger(part.size) || part.size <= 0 || !SHA256.test(part.sha256 ?? "") || typeof part.url !== "string")) {
    return failure(503);
  }
  if (file.parts.reduce((size, part) => size + part.size, 0) !== file.size) return failure(503);

  const range = parseRange(request.headers.get("Range"), file.size);
  if (!range) return failure(416, { "Content-Range": `bytes */${file.size}` });
  const headers = {
    "Content-Type": "application/octet-stream",
    "Content-Disposition": `attachment; filename="${filename}"`,
    "Content-Length": String(range.length),
    "Accept-Ranges": "bytes",
    "X-Content-Type-Options": "nosniff",
    ETag: `"${file.sha256}"`,
    "Cache-Control": "no-store",
  };
  if (range.partial) headers["Content-Range"] = `bytes ${range.start}-${range.end}/${file.size}`;
  const status = range.partial ? 206 : 200;
  if (request.method === "HEAD") return new Response(null, { status, headers });
  return new Response(streamParts(file.parts, new URL(request.url).origin, range, fetcher), { status, headers });
}
