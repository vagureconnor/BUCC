import type webview from '@ohos.web.webview';
import { paymentHost } from '../platform/PaymentHost';
import type { PaymentFrameInfo } from '../platform/PaymentHost';

function rejectWindow(handler: ControllerHandler): void {
  // ArkWeb documents null as the rejection signal, although the installed signature omits null.
  const rejected: webview.WebviewController | null = null;
  handler.setWebController(rejected!);
}

@Component
struct PaymentWebPane {
  @Prop sessionId: number = 0;
  @State private frames: PaymentFrameInfo[] = [];
  @State private activeKey: number = 0;

  aboutToAppear(): void {
    const update = (): void => {
      this.frames = paymentHost.frames(this.sessionId);
      this.activeKey = this.frames.find(frame => frame.active)?.key || 0;
    };
    update();
    paymentHost.subscribe(this.sessionId, update);
  }

  aboutToDisappear(): void {
    paymentHost.unsubscribe(this.sessionId);
    paymentHost.close(this.sessionId);
  }

  private onWindowNew(event: OnWindowNewEvent): void {
    const target: string = event.targetUrl.length === 0 ? 'about:blank' : event.targetUrl;
    if (target !== 'about:blank' && !paymentHost.allowUrl(this.sessionId, target)) {
      rejectWindow(event.handler);
      paymentHost.blocked(this.sessionId, target);
      return;
    }
    const key: number = paymentHost.addWindow(this.sessionId, target, event.handler);
    if (key === 0) {
      rejectWindow(event.handler);
    }
  }

  build() {
    Stack() {
      Column() {
      ForEach(this.frames, (frame: PaymentFrameInfo) => {
        Web({
          src: frame.src,
          controller: frame.controller,
          incognitoMode: true
        })
          .width('100%')
          .height(frame.key === this.activeKey ? '100%' : 0)
          .visibility(frame.key === this.activeKey ? Visibility.Visible : Visibility.None)
          .javaScriptAccess(true)
          .multiWindowAccess(true)
          .allowWindowOpenMethod(true)
          .domStorageAccess(true)
          .fileAccess(false)
          .onControllerAttached((): void => {
            paymentHost.bindFrame(this.sessionId, frame.key);
          })
          .onPageBegin((event: OnPageBeginEvent): void => {
            paymentHost.onPageBegin(this.sessionId, frame.key, event.url);
          })
          .onPageEnd((event: OnPageEndEvent): void => {
            paymentHost.onPageEnd(this.sessionId, frame.key, event.url);
          })
          .onErrorReceive((_event: OnErrorReceiveEvent): void => {
            paymentHost.onError(this.sessionId, frame.key);
          })
          .onOverrideUrlLoading((request: WebResourceRequest): boolean => {
            const url: string = request.getRequestUrl();
            const allowed: boolean = paymentHost.allowUrl(this.sessionId, url);
            if (!allowed) {
              paymentHost.blocked(this.sessionId, url);
            }
            return !allowed;
          })
          .onInterceptRequest((event: OnInterceptRequestEvent): WebResourceResponse | null =>
            paymentHost.intercept(this.sessionId, frame.root, event.request))
          .onWindowNew((event: OnWindowNewEvent): void => {
            this.onWindowNew(event);
          })
      }, (frame: PaymentFrameInfo): string => `${this.sessionId}:${frame.key}`);
      }
      .width('100%')
      .height('100%')

      if (paymentHost.isCovered(this.sessionId)) {
        Column() {
          Text('正在确认官方支付入口…')
            .fontSize(16)
            .fontColor('#FF202124')
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#FFFFFFFF')
        .zIndex(1)
      }
    }
    .width('100%')
    .height('100%')
  }
}

interface PaymentParameters { id: number; }

@Builder
export function paymentWebBuilder(params: object) {
  PaymentWebPane({ sessionId: (params as PaymentParameters).id });
}
