export const ORIGIN: string = 'https://mall.cc-pay.cn';
export let rootPosts: number = 0;
export let blockedPosts: number = 0;

// The shared Kotlin navigation policy has separate JVM coverage. This test double
// isolates actual ArkWeb window binding and never allows a real network request.
export function allowPaymentUrl(_id: number, url: string): boolean {
  return url.startsWith(ORIGIN + '/fixture-');
}
export function allowPaymentCookie(_id: number, origin: string): boolean {
  return origin === ORIGIN + '/';
}
export function allowPaymentBack(id: number, url: string): boolean {
  return allowPaymentUrl(id, url);
}
export function allowPaymentRequest(id: number, url: string, method: string, root: boolean): boolean {
  if (!allowPaymentUrl(id, url)) return false;
  if (method !== 'POST') return method === 'GET';
  if (root && url === ORIGIN + '/fixture-root' && rootPosts === 0) {
    rootPosts++;
    return true;
  }
  blockedPosts++;
  return false;
}

export function fixtureResponse(request: WebResourceRequest): WebResourceResponse {
  const url: string = request.getRequestUrl();
  let html: string = '<!doctype html><title>Blocked fixture</title>';
  let code: number = 403;
  if (allowPaymentUrl(1, url)) {
    code = 200;
    const path: string = url.substring(ORIGIN.length);
    html = '<!doctype html><meta name="viewport" content="width=device-width"><body>' + path;
    if (path === '/fixture-root') {
      html += '<p><a id="a" target="_blank" href="/fixture-menu-a">Menu A</a></p>' +
        '<p><button id="b" onclick="window.open(\'/fixture-menu-b\',\'_blank\')">Menu B</button></p>' +
        '<p><button id="c" onclick="const w=window.open(\'\',\'_blank\');setTimeout(()=>w.location=\'/fixture-menu-c\',50)">Menu C</button></p>';
    } else {
      html += '<p><a id="nested" target="_blank" href="/fixture-nested">Nested</a></p>' +
        '<p><a id="detail" href="/fixture-detail">Detail</a></p>';
    }
    html += '</body>';
  }
  const response: WebResourceResponse = new WebResourceResponse();
  response.setResponseData(html);
  response.setResponseEncoding('UTF-8');
  response.setResponseMimeType('text/html');
  response.setResponseCode(code);
  response.setReasonMessage(code === 200 ? 'OK' : 'Forbidden');
  response.setResponseIsReady(true);
  return response;
}
