import { paymentWebBuilder } from '../components/PaymentWeb';
import { paymentHost } from '../platform/PaymentHost';
import webview from '@ohos.web.webview';
import { ORIGIN, rootPosts, blockedPosts } from '../platform/FixturePolicy';

function pause(): Promise<void> {
  return new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 100); });
}

interface FixtureParameters { id: number; }

@Entry
@Component
struct Index {
  private parameters: FixtureParameters = { id: 1 };
  @State private ready: boolean = false;
  @State private result: string = 'Starting intercepted ArkWeb fixtures';

  aboutToAppear(): void {
    this.run().catch((error: Error): void => { this.result = 'FAIL ' + error.message; });
  }

  private check(value: boolean, message: string): void {
    if (!value) throw new Error(message);
  }

  private async waitPage(path: string, frames: number): Promise<void> {
    for (let attempt: number = 0; attempt < 60; attempt++) {
      try {
        const list = paymentHost.frames(1);
        const current = list.find(frame => frame.active);
        if (list.length === frames && current !== undefined && current.controller.getUrl() === ORIGIN + path) {
          const content: string = await current.controller.runJavaScript('document.body.innerText');
          if (content.includes(path)) return;
        }
      } catch (_error) {}
      await pause();
    }
    const current = paymentHost.frames(1).find(frame => frame.active);
    throw new Error('Page timeout ' + path + ' frames=' + paymentHost.frames(1).length + ' url=' + current?.controller.getUrl());
  }

  private async script(source: string): Promise<string> {
    const current = paymentHost.frames(1).find(frame => frame.active);
    if (current === undefined) throw new Error('Missing active frame');
    return current.controller.runJavaScript(source);
  }

  private async run(): Promise<void> {
    await paymentHost.open({ id: 1, url: ORIGIN + '/fixture-root', formPost: 'Zml4dHVyZT0x',
      cookies: [{ origin: ORIGIN + '/', header: 'BUCC_SYNTHETIC=fixture; Path=/; Secure' }] }, (): boolean => false);
    this.ready = true;
    await this.waitPage('/fixture-root', 1);
    this.check(rootPosts === 1, 'Initial POST count');
    for (const menu of ['a', 'b', 'c']) {
      this.result = 'Testing menu ' + menu;
      await this.script('document.getElementById("' + menu + '").click()');
      await this.waitPage('/fixture-menu-' + menu, 2);
      this.check((await this.script('document.cookie')).includes('BUCC_SYNTHETIC=fixture'), 'Child cookie');
      await this.script('document.getElementById("nested").click()');
      await this.waitPage('/fixture-nested', 3);
      await this.script('document.getElementById("detail").click()');
      await this.waitPage('/fixture-detail', 3);
      this.check(paymentHost.back(1), 'History back');
      await this.waitPage('/fixture-nested', 3);
      this.check(paymentHost.back(1), 'Nested back');
      await this.waitPage('/fixture-menu-' + menu, 2);
      this.check(paymentHost.back(1), 'Menu back');
      await this.waitPage('/fixture-root', 1);
      this.check(rootPosts === 1, 'POST was replayed');
    }
    await this.script('window.open("https://unrelated.invalid/", "_blank")');
    await pause();
    this.check(paymentHost.frames(1).length === 1, 'Foreign window opened');
    await this.waitPage('/fixture-root', 1);
    const foreignCookie: string = await webview.WebCookieManager.fetchCookie('https://unrelated.invalid/', true, true);
    this.check(!foreignCookie.includes('BUCC_SYNTHETIC'), 'Cookie leaked to foreign origin');
    const root = paymentHost.frames(1)[0].controller;
    root.postUrl(ORIGIN + '/fixture-root', new ArrayBuffer(1));
    for (let attempt: number = 0; attempt < 60 && blockedPosts === 0; attempt++) await pause();
    this.check(rootPosts === 1 && blockedPosts === 1, 'Repeat POST not blocked');
    this.result = 'PASS 3 menus, blank window, nested navigation, history/back, cookie isolation, no POST replay, repeat POST blocked';
  }

  build() {
    Column() {
      Text(this.result).fontSize(20).padding(16)
      if (this.ready) {
        Column() { paymentWebBuilder(this.parameters); }.layoutWeight(1)
      }
    }.width('100%').height('100%')
  }
}
