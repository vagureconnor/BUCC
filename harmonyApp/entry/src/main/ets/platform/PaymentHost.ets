import webview from '@ohos.web.webview';
import util from '@ohos.util';
import * as nativeApi from 'libentry.so';

export interface PaymentCookie {
  origin: string;
  header: string;
}

export interface PaymentConfiguration {
  id: number;
  url: string;
  formPost?: string;
  cookies: PaymentCookie[];
}

export interface PaymentEvent {
  status: string;
  kind?: string;
  url?: string;
}

export interface PaymentFrameInfo {
  key: number;
  controller: webview.WebviewController;
  src: string;
  root: boolean;
  active: boolean;
}

interface PaymentFrame {
  key: number;
  controller: webview.WebviewController;
  root: boolean;
  bound: boolean;
}

interface PaymentSession {
  id: number;
  targetUrl: string;
  formPost: string;
  recoveryCovered: boolean;
  covered: boolean;
  frames: Array<PaymentFrame>;
  activeFrame: number;
  events: Array<PaymentEvent>;
  eventWaiters: Array<string>;
  notifyViews?: () => void;
  nextFrameKey: number;
  closed: boolean;
}

interface PaymentWaiter {
  requestId: string;
  sessionId: number;
  resolve: (event: PaymentEvent) => void;
  timerId: number;
}

function isAboutBlank(url: string): boolean {
  return url === 'about:blank';
}

function deniedResponse(): WebResourceResponse {
  const response: WebResourceResponse = new WebResourceResponse();
  response.setResponseData('<!doctype html><title>Blocked</title>');
  response.setResponseEncoding('UTF-8');
  response.setResponseMimeType('text/html');
  response.setResponseCode(403);
  response.setReasonMessage('Forbidden');
  response.setResponseIsReady(true);
  return response;
}

export class PaymentHost {
  private session?: PaymentSession;
  private readonly waiters: Map<string, PaymentWaiter> = new Map<string, PaymentWaiter>();
  private operation: Promise<void> = Promise.resolve();

  initialize(): void {
    webview.WebviewController.initializeWebEngine();
    webview.WebviewController.setWebDebuggingAccess(false);
  }

  async open(config: PaymentConfiguration, isCancelled: () => boolean): Promise<void> {
    this.initialize();
    await this.exclusive(async (): Promise<void> => {
      if (this.session !== undefined) {
        this.closeSession(this.session);
      }
      webview.WebCookieManager.clearAllCookiesSync(true);
      webview.WebStorage.deleteAllData(true);
      if (isCancelled()) {
        return;
      }

      const root: PaymentFrame = {
        key: 1,
        controller: new webview.WebviewController(),
        root: true,
        bound: false
      };
      const session: PaymentSession = {
        id: config.id,
        targetUrl: config.url,
        formPost: config.formPost || '',
        recoveryCovered: (config.formPost || '').length > 0,
        covered: (config.formPost || '').length > 0,
        frames: [root],
        activeFrame: 0,
        events: [],
        eventWaiters: [],
        nextFrameKey: 2,
        closed: false
      };
      this.session = session;

      if (!nativeApi.allowPaymentUrl(config.id, config.url)) {
        this.emit(config.id, { status: 'success', kind: 'blocked', url: config.url });
        session.targetUrl = '';
        session.formPost = '';
        return;
      }

      try {
        for (let index: number = 0; index < config.cookies.length; index++) {
          if (isCancelled() || this.session !== session) {
            this.closeSession(session);
            webview.WebCookieManager.clearAllCookiesSync(true);
            webview.WebStorage.deleteAllData(true);
            return;
          }
          const cookie: PaymentCookie = config.cookies[index];
          if (cookie.header.length > 0 && nativeApi.allowPaymentCookie(config.id, cookie.origin)) {
            await webview.WebCookieManager.configCookie(cookie.origin, cookie.header, true, true);
          }
        }
        if (isCancelled() || this.session !== session) {
          this.closeSession(session);
        }
      } catch (_error) {
        this.emit(config.id, { status: 'success', kind: 'error' });
        session.targetUrl = '';
        session.formPost = '';
        webview.WebCookieManager.clearAllCookiesSync(true);
        webview.WebStorage.deleteAllData(true);
      }
    });
  }

  async nextEvent(sessionId: number, requestId: string, isCancelled: () => boolean): Promise<PaymentEvent> {
    this.cancelWaiter(requestId);
    const session: PaymentSession | undefined = this.current(sessionId);
    if (session === undefined || isCancelled()) {
      return { status: 'cancelled' };
    }
    if (session.events.length > 0) {
      return session.events.shift() as PaymentEvent;
    }
    return new Promise<PaymentEvent>((resolve: (event: PaymentEvent) => void): void => {
      const waiter: PaymentWaiter = {
        requestId: requestId,
        sessionId: sessionId,
        resolve: resolve,
        timerId: 0
      };
      waiter.timerId = setInterval((): void => {
        if (isCancelled()) {
          this.finishWaiter(waiter, { status: 'cancelled' });
        } else if (this.current(sessionId) === undefined) {
          this.finishWaiter(waiter, { status: 'cancelled' });
        }
      }, 100);
      this.waiters.set(requestId, waiter);
      session.eventWaiters.push(requestId);
    });
  }

  cancelWaiter(requestId: string): void {
    const waiter: PaymentWaiter | undefined = this.waiters.get(requestId);
    if (waiter !== undefined) {
      this.finishWaiter(waiter, { status: 'cancelled' });
    }
  }

  async inspect(id: number, sourceUrl: string, script: string): Promise<string> {
    const session: PaymentSession | undefined = this.current(id);
    const controller: webview.WebviewController | undefined = this.activeController(session);
    if (session === undefined || controller === undefined || !nativeApi.allowPaymentUrl(id, sourceUrl) ||
      controller.getUrl() !== sourceUrl) {
      return '';
    }
    try {
      const value: string = await controller.runJavaScript(script);
      if (this.current(id) !== session || controller.getUrl() !== sourceUrl || value.length > 131072) {
        return '';
      }
      return value;
    } catch (_error) {
      return '';
    }
  }

  async navigate(id: number, url: string, cookies: PaymentCookie[], isCancelled: () => boolean): Promise<void> {
    await this.exclusive(async (): Promise<void> => {
      const session: PaymentSession | undefined = this.current(id);
      const controller: webview.WebviewController | undefined = this.activeController(session);
      if (session === undefined || controller === undefined || isCancelled()) {
        return;
      }
      if (session.recoveryCovered) {
        session.covered = true;
        this.changed(session);
      }
      if (!nativeApi.allowPaymentUrl(id, url)) {
        this.blocked(id, url);
        return;
      }
      try {
        for (let index: number = 0; index < cookies.length; index++) {
          if (isCancelled() || this.current(id) !== session) {
            return;
          }
          const cookie: PaymentCookie = cookies[index];
          if (cookie.header.length > 0 && nativeApi.allowPaymentCookie(id, cookie.origin)) {
            await webview.WebCookieManager.configCookie(cookie.origin, cookie.header, true, true);
          }
        }
        if (!isCancelled() && this.current(id) === session && nativeApi.allowPaymentUrl(id, url)) {
          controller.loadUrl(url);
        }
      } catch (_error) {
        this.emit(id, { status: 'success', kind: 'error' });
      }
    });
  }

  reveal(id: number): void {
    const session: PaymentSession | undefined = this.current(id);
    if (session !== undefined && session.recoveryCovered && session.covered) {
      session.covered = false;
      this.changed(session);
    }
  }

  isCovered(id: number): boolean {
    return this.current(id)?.covered === true;
  }

  back(id: number): boolean {
    const session: PaymentSession | undefined = this.current(id);
    if (session === undefined) {
      return false;
    }
    const frame: PaymentFrame = session.frames[session.activeFrame];
    try {
      const history = frame.controller.getBackForwardEntries();
      if (history.currentIndex > 0) {
        const previousUrl: string = history.getItemAtIndex(history.currentIndex - 1).historyUrl;
        if (nativeApi.allowPaymentBack(id, previousUrl)) {
          frame.controller.backOrForward(-1);
          return true;
        }
      }
    } catch (_error) {
      // A component may not be bound yet; allow the parent window stack to handle Back.
    }
    if (session.activeFrame > 0) {
      const removed: PaymentFrame = session.frames.pop() as PaymentFrame;
      try {
        removed.controller.stop();
      } catch (_error) {
        // Ignore teardown races.
      }
      session.activeFrame = session.frames.length - 1;
      this.changed(session);
      return true;
    }
    return false;
  }

  close(id: number): void {
    const session: PaymentSession | undefined = this.current(id);
    if (session !== undefined) {
      this.closeSession(session);
    }
  }

  frames(id: number): Array<PaymentFrameInfo> {
    const session: PaymentSession | undefined = this.current(id);
    if (session === undefined) {
      return [];
    }
    return session.frames.map((frame: PaymentFrame, index: number): PaymentFrameInfo => ({
      key: frame.key,
      controller: frame.controller,
      src: frame.root ? 'about:blank' : '',
      root: frame.root,
      active: index === session.activeFrame
    }));
  }

  subscribe(id: number, notify: () => void): void {
    const session: PaymentSession | undefined = this.current(id);
    if (session !== undefined) {
      session.notifyViews = notify;
    }
  }

  unsubscribe(id: number): void {
    const session: PaymentSession | undefined = this.current(id);
    if (session !== undefined) {
      session.notifyViews = undefined;
    }
  }

  addWindow(id: number, targetUrl: string, handler: ControllerHandler): number {
    const session: PaymentSession | undefined = this.current(id);
    if (session === undefined || (!isAboutBlank(targetUrl) && !nativeApi.allowPaymentUrl(id, targetUrl))) {
      this.emit(id, { status: 'success', kind: 'blocked', url: targetUrl });
      return 0;
    }
    const frame: PaymentFrame = {
      key: session.nextFrameKey++,
      controller: new webview.WebviewController(),
      root: false,
      bound: false
    };
    // Resolve onWindowNew before waiting for the child component to attach;
    // otherwise window.open can keep the renderer blocked during composition.
    try {
      handler.setWebController(frame.controller);
    } catch (_error) {
      return 0;
    }
    session.frames.push(frame);
    session.activeFrame = session.frames.length - 1;
    this.changed(session);
    return frame.key;
  }

  bindFrame(id: number, key: number): void {
    const session: PaymentSession | undefined = this.current(id);
    const frame: PaymentFrame | undefined = this.findFrame(session, key);
    if (session === undefined || frame === undefined || frame.bound) {
      return;
    }
    frame.bound = true;
  }

  onPageBegin(id: number, key: number, url: string): void {
    const session: PaymentSession | undefined = this.current(id);
    if (session === undefined || session.frames[session.activeFrame].key !== key || isAboutBlank(url)) {
      return;
    }
    if (!nativeApi.allowPaymentUrl(id, url)) {
      this.emit(id, { status: 'success', kind: 'blocked', url: url });
      return;
    }
    this.emit(id, { status: 'success', kind: 'loading', url: url });
  }

  onPageEnd(id: number, key: number, url: string): void {
    const session: PaymentSession | undefined = this.current(id);
    if (session === undefined || session.frames[session.activeFrame].key !== key) {
      return;
    }
    const frame: PaymentFrame | undefined = this.findFrame(session, key);
    if (isAboutBlank(url)) {
      // Wait for the initial blank document before submitting once; its initial
      // navigation would otherwise abort a POST started in onControllerAttached.
      if (frame !== undefined && frame.root && frame.bound) this.loadRoot(session, frame);
      return;
    }
    if (!isAboutBlank(url) && nativeApi.allowPaymentUrl(id, url)) {
      this.emit(id, { status: 'success', kind: 'loaded', url: url });
    }
  }

  onError(id: number, key: number): void {
    const session: PaymentSession | undefined = this.current(id);
    if (session === undefined || session.frames[session.activeFrame].key !== key) {
      return;
    }
    this.emit(id, { status: 'success', kind: 'error' });
  }

  blocked(id: number, url: string): void {
    this.emit(id, { status: 'success', kind: 'blocked', url: url });
  }

  allowUrl(id: number, url: string): boolean {
    return isAboutBlank(url) || nativeApi.allowPaymentUrl(id, url);
  }

  intercept(id: number, root: boolean, request: WebResourceRequest): WebResourceResponse | null {
    const url: string = request.getRequestUrl();
    if (isAboutBlank(url)) {
      return null;
    }
    const method: string = request.getRequestMethod().toUpperCase();
    const mainFrame: boolean = request.isMainFrame();
    const mustGate: boolean = mainFrame || method === 'POST';
    if (!mustGate) {
      return null;
    }
    if (!nativeApi.allowPaymentRequest(id, url, method, root && mainFrame)) {
      this.emit(id, { status: 'success', kind: 'blocked', url: url });
      return deniedResponse();
    }
    return null;
  }

  private loadRoot(session: PaymentSession, root: PaymentFrame): void {
    if (session.closed || session.targetUrl.length === 0 || !nativeApi.allowPaymentUrl(session.id, session.targetUrl)) {
      return;
    }
    const url: string = session.targetUrl;
    const formPost: string = session.formPost;
    session.targetUrl = '';
    session.formPost = '';
    if (formPost.length > 0) {
      try {
        const decoded: Uint8Array = new util.Base64Helper().decodeSync(formPost);
        const bytes: ArrayBuffer = new ArrayBuffer(decoded.length);
        new Uint8Array(bytes).set(decoded);
        root.controller.postUrl(url, bytes);
      } catch (_error) {
        this.emit(session.id, { status: 'success', kind: 'error' });
      }
    } else {
      root.controller.loadUrl(url);
    }
  }

  private current(id: number): PaymentSession | undefined {
    return this.session !== undefined && this.session.id === id && !this.session.closed ? this.session : undefined;
  }

  private activeController(session: PaymentSession | undefined): webview.WebviewController | undefined {
    return session === undefined ? undefined : session.frames[session.activeFrame]?.controller;
  }

  private findFrame(session: PaymentSession | undefined, key: number): PaymentFrame | undefined {
    if (session === undefined) {
      return undefined;
    }
    for (let index: number = 0; index < session.frames.length; index++) {
      if (session.frames[index].key === key) {
        return session.frames[index];
      }
    }
    return undefined;
  }

  private changed(session: PaymentSession): void {
    if (session.notifyViews !== undefined) {
      session.notifyViews();
    }
  }

  private emit(id: number, event: PaymentEvent): void {
    const session: PaymentSession | undefined = this.current(id);
    if (session === undefined) {
      return;
    }
    while (session.eventWaiters.length > 0) {
      const requestId: string = session.eventWaiters.shift() as string;
      const waiter: PaymentWaiter | undefined = this.waiters.get(requestId);
      if (waiter !== undefined) {
        this.finishWaiter(waiter, event);
        return;
      }
    }
    if (session.events.length < 64) {
      session.events.push(event);
    }
  }

  private finishWaiter(waiter: PaymentWaiter, event: PaymentEvent): void {
    clearInterval(waiter.timerId);
    this.waiters.delete(waiter.requestId);
    const session: PaymentSession | undefined = this.current(waiter.sessionId);
    if (session !== undefined) {
      session.eventWaiters = session.eventWaiters.filter((id: string): boolean => id !== waiter.requestId);
    }
    waiter.resolve(event);
  }

  private closeSession(session: PaymentSession): void {
    if (session.closed) {
      return;
    }
    session.closed = true;
    for (let index: number = 0; index < session.frames.length; index++) {
      try {
        session.frames[index].controller.stop();
      } catch (_error) {
        // Ignore teardown races.
      }
    }
    session.frames = [];
    session.targetUrl = '';
    session.formPost = '';
    session.events = [];
    session.notifyViews = undefined;
    this.waiters.forEach((waiter: PaymentWaiter): void => {
      if (waiter.sessionId === session.id) {
        this.finishWaiter(waiter, { status: 'cancelled' });
      }
    });
    if (this.session === session) {
      this.session = undefined;
    }
    webview.WebCookieManager.clearAllCookiesSync(true);
    webview.WebStorage.deleteAllData(true);
  }

  private async exclusive<T>(task: () => Promise<T>): Promise<T> {
    const previous: Promise<void> = this.operation;
    let release: () => void = (): void => {};
    this.operation = new Promise<void>((resolve: () => void): void => {
      release = resolve;
    });
    await previous;
    try {
      return await task();
    } finally {
      release();
    }
  }
}

export const paymentHost: PaymentHost = new PaymentHost();
