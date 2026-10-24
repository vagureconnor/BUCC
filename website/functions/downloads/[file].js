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

export async function onRequest(context) {
  const { request, env, params } = context;
  const filename = params?.file;
  if (typeof filename !== "string" || !FILENAME.test(filename)) return failure(404);
  if (request.method !== "GET" && request.method !== "HEAD") {
    return failure(405, { Allow: "GET, HEAD" });
  }
  const bucket = env?.BUCC_RELEASES_R2;
  if (!bucket || typeof bucket.head !== "function" || typeof bucket.get !== "function") return failure(503);

  let metadata;
  try {
    metadata = await bucket.head(filename);
  } catch {
    return failure(503);
  }
  if (!metadata || !Number.isSafeInteger(metadata.size) || metadata.size <= 0) return failure(404);
  const sha256 = metadata.customMetadata?.sha256;
  if (!SHA256.test(sha256 ?? "")) return failure(503);

  const range = parseRange(request.headers.get("Range"), metadata.size);
  if (!range) return failure(416, { "Content-Range": `bytes */${metadata.size}` });
  const headers = {
    "Content-Type": "application/octet-stream",
    "Content-Disposition": `attachment; filename="${filename}"`,
    "Content-Length": String(range.length),
    "Accept-Ranges": "bytes",
    "X-Content-Type-Options": "nosniff",
    ETag: `"${sha256}"`,
    "Cache-Control": "public, max-age=31536000, immutable",
  };
  if (range.partial) headers["Content-Range"] = `bytes ${range.start}-${range.end}/${metadata.size}`;
  const status = range.partial ? 206 : 200;
  if (request.method === "HEAD") return new Response(null, { status, headers });

  try {
    const object = await bucket.get(filename, range.partial ? { range: { offset: range.offset, length: range.length } } : {});
    if (!object || object.size !== metadata.size || !object.body) return failure(503);
    return new Response(object.body, { status, headers });
  } catch {
    return failure(503);
  }
}
