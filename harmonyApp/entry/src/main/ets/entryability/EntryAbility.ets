import UIAbility from '@ohos.app.ability.UIAbility';
import type Want from '@ohos.app.ability.Want';
import type AbilityConstant from '@ohos.app.ability.AbilityConstant';
import window from '@ohos.window';
import nativeApi from 'libentry.so';
import { registerComposeInteropBuilder } from '@cpf-kmp-cmp/compose/src/main/ets/compose/ArkUIView';
import { paymentWebBuilder } from '../components/PaymentWeb';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want): void { this.receiveRoute(want); }

  onNewWant(want: Want, _launchParam: AbilityConstant.LaunchParam): void { this.receiveRoute(want); }

  private receiveRoute(want: Want): void {
    const route = want.parameters?.route;
    if (typeof route === 'string' && (route === 'todo' || route === 'schedule')) {
      if (AppStorage.get<boolean>('buccControllerReady')) nativeApi.notificationRoute(route);
      else AppStorage.setOrCreate<string>('buccNotificationRoute', route);
    }
  }

  onWindowStageCreate(stage: window.WindowStage): void {
    registerComposeInteropBuilder('BUCCPayment', paymentWebBuilder);
    AppStorage.setOrCreate<window.Window>('buccMainWindow', stage.getMainWindowSync());
    stage.loadContent('pages/Index');
  }

  onForeground(): void {
    if (AppStorage.get<boolean>('buccControllerReady')) nativeApi.refreshHomeworkOnForeground();
  }

  onBackground(): void {
    if (AppStorage.get<boolean>('buccControllerReady')) nativeApi.notifyBackground();
    AppStorage.get<window.Window>('buccMainWindow')?.setWindowKeepScreenOn(false).catch(() => {});
  }

  onWindowStageDestroy(): void {
    AppStorage.delete('buccMainWindow');
    AppStorage.delete('buccControllerReady');
  }
}
