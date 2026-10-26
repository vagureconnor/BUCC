import { ArkUIViewController, Compose } from '@cpf-kmp-cmp/compose';
import common from '@ohos.app.ability.common';
import window from '@ohos.window';
import nativeApi from 'libentry.so';
import type { HostBridge, HostRequest, HostCancellation, HostResult, KeepAwakeRequest } from 'libentry.so';
import { MediaHost } from '../platform/MediaHost';
import { NotificationHost } from '../platform/NotificationHost';
import type { ReminderPlanItem } from '../platform/NotificationHost';
import { CalendarHost } from '../platform/CalendarHost';
import type { CalendarPayload } from '../platform/CalendarHost';
import { paymentHost } from '../platform/PaymentHost';
import type { PaymentConfiguration, PaymentCookie } from '../platform/PaymentHost';

interface CancellationToken { cancelled: boolean; }
interface IdPayload { id: number; }
interface InspectPayload { id: number; sourceUrl: string; script: string; }
interface NavigatePayload { id: number; url: string; cookies: PaymentCookie[]; }
interface PaymentAppPayload { url: string; }
interface NotificationPayload { plan: ReminderPlanItem[]; dailyTodo: boolean; }

@Entry
@Component
struct Index {
  private controller?: ArkUIViewController;
  private hostContext?: common.UIAbilityContext;
  private media?: MediaHost;
  private notifications?: NotificationHost;
  private calendar?: CalendarHost;
  private activePaymentId?: number;
  private readonly requests: Map<number, CancellationToken> = new Map<number, CancellationToken>();

  aboutToAppear(): void {
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.hostContext = context;
    this.media = new MediaHost(context);
    this.notifications = new NotificationHost(context);
    this.calendar = new CalendarHost(context);
    const bridge: HostBridge = {
      request: (request: HostRequest): void => { this.request(request); },
      cancel: (request: HostCancellation): void => { this.cancel(request.id); },
      keepAwake: (request: KeepAwakeRequest): void => {
        AppStorage.get<window.Window>('buccMainWindow')?.setWindowKeepScreenOn(request.enabled).catch(() => {});
      },
      paymentBack: (request: HostCancellation): boolean => paymentHost.back(request.id),
      closePayment: (request: HostCancellation): void => { paymentHost.close(request.id); }
    };
    this.controller = nativeApi.createController(context.filesDir, context.cacheDir, bridge);
    AppStorage.setOrCreate<boolean>('buccControllerReady', true);
    const route = AppStorage.get<string>('buccNotificationRoute');
    if (route) { nativeApi.notificationRoute(route); AppStorage.delete('buccNotificationRoute'); }
  }

  private cancel(id: number): void {
    const token = this.requests.get(id);
    if (token) token.cancelled = true;
    paymentHost.cancelWaiter(id.toString());
    this.requests.delete(id);
  }

  private request(request: HostRequest): void {
    if (this.requests.has(request.id)) return;
    const token: CancellationToken = { cancelled: false };
    this.requests.set(request.id, token);
    this.perform(request, token).then((result: HostResult): void => {
      if (!token.cancelled) nativeApi.completeRequest(request.id, result);
    }).catch((): void => {
      if (!token.cancelled) nativeApi.completeRequest(request.id, { status: 'error' });
    }).finally((): void => { this.requests.delete(request.id); });
  }

  private async perform(request: HostRequest, token: CancellationToken): Promise<HostResult> {
    const cancelled = (): boolean => token.cancelled;
    switch (request.operation) {
      case 'pickPhoto': case 'capturePhoto': case 'pickLibraryAttachment':
        return this.media!.run(request.operation, cancelled);
      case 'scheduleNotifications': {
        const payload = JSON.parse(request.payload) as NotificationPayload;
        return { status: 'success', code: await this.notifications!.schedule(payload.plan, payload.dailyTodo, cancelled) };
      }
      case 'cancelNotifications':
        await this.notifications!.cancel(); return { status: 'success' };
      case 'notificationPermissionState':
        return { status: 'success', code: await this.notifications!.permissionState() };
      case 'requestNotificationPermission':
        return { status: 'success', code: await this.notifications!.requestPermission() };
      case 'openNotificationSettings':
        await this.notifications!.openSettings(); return { status: 'success' };
      case 'notifyNewHomework':
        await this.notifications!.notifyHomework(); return { status: 'success' };
      case 'importCourseCalendar':
        return this.calendar!.importCourses(JSON.parse(request.payload) as CalendarPayload, cancelled);
      case 'initializePaymentBrowser':
        paymentHost.initialize(); return { status: 'success' };
      case 'openPayment': {
        const payload = JSON.parse(request.payload) as PaymentConfiguration;
        this.activePaymentId = payload.id;
        await paymentHost.open(payload, cancelled); return { status: 'success' };
      }
      case 'nextPaymentEvent':
        return paymentHost.nextEvent((JSON.parse(request.payload) as IdPayload).id, request.id.toString(), cancelled);
      case 'inspectPayment': {
        const payload = JSON.parse(request.payload) as InspectPayload;
        return { status: 'success', value: await paymentHost.inspect(payload.id, payload.sourceUrl, payload.script) };
      }
      case 'navigatePayment': {
        const payload = JSON.parse(request.payload) as NavigatePayload;
        await paymentHost.navigate(payload.id, payload.url, payload.cookies, cancelled); return { status: 'success' };
      }
      case 'openPaymentApp': {
        const payload = JSON.parse(request.payload) as PaymentAppPayload;
        const context = this.hostContext;
        const customScheme = /^(alipay|alipays|weixin|wxpay|weixinpay|tenpay|uppay|unionpay):\/\//.test(payload.url);
        const alipayLink = /^https:\/\/qr\.alipay\.com\/[A-Za-z0-9_-]{1,256}(?:[?#].*)?$/.test(payload.url);
        if ((!customScheme && !alipayLink) || context === undefined) {
          return { status: 'error' };
        }
        await context.startAbility({
          action: 'ohos.want.action.viewData',
          entities: ['entity.system.browsable'],
          uri: payload.url
        });
        return { status: 'success' };
      }
      case 'revealPayment':
        paymentHost.reveal((JSON.parse(request.payload) as IdPayload).id); return { status: 'success' };
      default: return { status: 'error' };
    }
  }

  onPageShow(): void { this.controller?.onPageShow(); }
  onPageHide(): void { this.controller?.onPageHide(); }
  onBackPress(): boolean { return nativeApi.handleBack() || (this.controller?.onBackPress() ?? false); }

  aboutToDisappear(): void {
    this.requests.forEach((_token: CancellationToken, id: number): void => { this.cancel(id); });
    if (this.activePaymentId !== undefined) paymentHost.close(this.activePaymentId);
    AppStorage.setOrCreate<boolean>('buccControllerReady', false);
  }

  build() {
    Column() {
      if (this.controller) {
        Compose({ controller: this.controller, libraryName: 'entry', onBackPressed: (): boolean => nativeApi.handleBack() })
          .width('100%').height('100%')
      }
    }.width('100%').height('100%')
  }
}
