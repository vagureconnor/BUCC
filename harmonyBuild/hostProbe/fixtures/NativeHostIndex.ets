import common from '@ohos.app.ability.common';
import calendarManager from '@ohos.calendarManager';
import notificationManager from '@ohos.notificationManager';
import reminderAgentManager from '@ohos.reminderAgentManager';
import { MediaHost } from '../platform/MediaHost';
import { CalendarHost } from '../platform/CalendarHost';
import type { CalendarPayload } from '../platform/CalendarHost';
import { NotificationHost } from '../platform/NotificationHost';

@Entry
@Component
struct Index {
  @State private result: string = 'Synthetic native host fixtures only';
  @State private busy: boolean = false;

  private context(): common.UIAbilityContext {
    return this.getUIContext().getHostContext() as common.UIAbilityContext;
  }

  private check(value: boolean, message: string): void {
    if (!value) throw new Error(message);
  }

  private perform(task: () => Promise<string>): void {
    this.busy = true;
    task().then(value => { this.result = value; })
      .catch((error: Error) => { this.result = 'FAIL ' + error.message; })
      .finally(() => { this.busy = false; });
  }

  private async media(operation: string): Promise<string> {
    const host = new MediaHost(this.context());
    const cancelled = await host.run(operation, (): boolean => true);
    this.check(cancelled.status === 'cancelled', 'Media pre-cancel');
    const result = await host.run(operation, (): boolean => false);
    return operation + ' returned ' + result.status;
  }

  private async calendar(): Promise<string> {
    const host = new CalendarHost(this.context());
    const start: number = Date.now() + 86400000;
    const payload: CalendarPayload = { accountId: 'SYNTHETIC-HOST-FIXTURE', termName: 'Synthetic term',
      events: [{ id: 'a', title: 'BUCC synthetic course A', location: 'Fixture', startMillis: start, endMillis: start + 3600000 },
        { id: 'b', title: 'BUCC synthetic course B', location: 'Fixture', startMillis: start + 7200000, endMillis: start + 10800000 }] };
    this.check((await host.importCourses(payload, (): boolean => true)).status === 'cancelled', 'Calendar pre-cancel');
    const first = await host.importCourses(payload, (): boolean => false);
    if (first.status === 'permission') return 'Calendar permission refusal handled';
    this.check(first.status === 'success' && first.eventCount === 2, 'Calendar insert ' + first.status);
    const manager = calendarManager.getCalendarManager(this.context());
    const owned = (await manager.getAllCalendars()).find(calendar => calendar.getAccount().name === 'BUCC synthetic host fixture');
    this.check(owned !== undefined, 'Synthetic calendar missing');
    if (owned === undefined) throw new Error('Synthetic calendar missing');
    try {
      this.check((await owned.getEvents()).length === 2, 'Calendar insert count');
      payload.events[0].title = 'BUCC synthetic course A updated';
      const second = await host.importCourses(payload, (): boolean => false);
      this.check(second.status === 'success' && second.eventCount === 2, 'Calendar repeat import');
      const events = await owned.getEvents();
      this.check(events.length === 2 && events.some(event => event.title === payload.events[0].title), 'Calendar stable-ID upsert');
    } finally {
      await manager.deleteCalendar(owned);
    }
    return 'PASS calendar insert, repeat upsert, pre-cancel, synthetic calendar cleaned';
  }

  private async notifications(): Promise<string> {
    const host = new NotificationHost(this.context());
    await host.cancel();
    const start: number = Date.now() + 86400000;
    try {
      const status = await host.schedule([{ eventId: 'synthetic-a', triggerAtMillis: start },
        { eventId: 'synthetic-a', triggerAtMillis: start },
        { eventId: 'synthetic-b', triggerAtMillis: start + 3600000 }], true, (): boolean => false);
      if (status === 'permission_required') return 'Notification permission refusal handled';
      if (status === 'partial_capacity') {
        await host.cancel();
        this.check((await reminderAgentManager.getValidReminders()).length === 0, 'Capacity cleanup');
        await host.notifyHomework();
        const notifications = await notificationManager.getActiveNotifications();
        this.check(notifications.some(item => item.id === 32001), 'Immediate notification missing');
        await notificationManager.cancel(32001);
        return 'LIMIT scheduled reminders: OS capacity; PASS cleanup and immediate native notification';
      }
      this.check(status === 'scheduled_approximate', 'Reminder schedule ' + status);
      this.check((await reminderAgentManager.getValidReminders()).length === 3, 'Reminder count/dedup');
      await host.cancel();
      this.check((await reminderAgentManager.getValidReminders()).length === 0, 'Owned reminder cleanup');
      return 'PASS native reminder publish, dedup, query, cancel/cleanup';
    } finally {
      await host.cancel();
    }
  }

  build() {
    Column({ space: 20 }) {
      Text(this.result).fontSize(22).padding(20)
      Button('Calendar fixture').onClick(() => this.perform(() => this.calendar())).enabled(!this.busy)
      Button('Notification fixture').onClick(() => this.perform(() => this.notifications())).enabled(!this.busy)
      Button('Photo picker').onClick(() => this.perform(() => this.media('pickPhoto'))).enabled(!this.busy)
      Button('Camera picker').onClick(() => this.perform(() => this.media('capturePhoto'))).enabled(!this.busy)
      Button('Attachment picker').onClick(() => this.perform(() => this.media('pickLibraryAttachment'))).enabled(!this.busy)
    }.width('100%').height('100%').justifyContent(FlexAlign.Center)
  }
}
