import common from '@ohos.app.ability.common';
import deviceInfo from '@ohos.deviceInfo';
import notificationManager from '@ohos.notificationManager';
import preferences from '@ohos.data.preferences';
import reminderAgentManager from '@ohos.reminderAgentManager';
import settings from '@ohos.settings';
import wantAgent from '@ohos.app.ability.wantAgent';
import type { WantAgent } from '@ohos.app.ability.wantAgent';
import type { BusinessError } from '@ohos.base';

export interface ReminderPlanItem {
  eventId: string;
  triggerAtMillis: number;
}

const PREF_NAME: string = 'BUCCHarmonyReminders';
const PREF_KEY: string = 'reminderIds';
const PERMISSION_REQUESTED_KEY: string = 'notificationPermissionRequested';
const MAX_OWNED_REMINDERS: number = 32;
const BUNDLE_NAME: string = 'com.bucc.client';
const ENTRY_ABILITY: string = 'EntryAbility';
const DAILY_TODO_NOTIFICATION_ID: number = 32000;

function errorCode(error: BusinessError): number {
  return typeof error.code === 'number' ? error.code : 0;
}

function localDateTime(timestamp: number): reminderAgentManager.LocalDateTime {
  const date: Date = new Date(timestamp);
  return {
    year: date.getFullYear(),
    month: date.getMonth() + 1,
    day: date.getDate(),
    hour: date.getHours(),
    minute: date.getMinutes(),
    second: date.getSeconds()
  };
}

function nextDailyTodo(): reminderAgentManager.LocalDateTime {
  const now: Date = new Date();
  let first: Date = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 10, 0, 0, 0);
  if (first.getTime() <= now.getTime()) {
    first = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 10, 0, 0, 0);
  }
  return localDateTime(first.getTime());
}

export class NotificationHost {
  private readonly context: common.UIAbilityContext;
  private operation: Promise<void> = Promise.resolve();

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  async schedule(plan: ReminderPlanItem[], dailyTodo: boolean, isCancelled: () => boolean): Promise<string> {
    return this.exclusive(async (): Promise<string> => {
      const prefs: preferences.Preferences = await preferences.getPreferences(this.context, PREF_NAME);
      if (!(await this.cancelOwned(prefs))) {
        return 'unavailable';
      }
      if (isCancelled()) {
        return 'unavailable';
      }

      const courses: Array<ReminderPlanItem> = this.validPlan(plan);
      if (!dailyTodo && courses.length === 0) {
        return 'scheduled_approximate';
      }

      try {
        await notificationManager.requestEnableNotification(this.context);
      } catch (_error) {
        return 'permission_required';
      }

      const batch: Array<number> = [];
      let clipped: boolean = false;
      const maxCourses: number = dailyTodo ? MAX_OWNED_REMINDERS - 1 : MAX_OWNED_REMINDERS;
      if (courses.length > maxCourses) {
        courses.length = maxCourses;
        clipped = true;
      }

      try {
        if (dailyTodo) {
          if (isCancelled()) {
            return await this.cleanupBatch(prefs, batch, 'unavailable');
          }
          const dailyRequest: reminderAgentManager.ReminderRequestCalendar = {
            reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_CALENDAR,
            dateTime: nextDailyTodo(),
            repeatDays: [1, 2, 3, 4, 5, 6, 7],
            title: '今日待办',
            content: '打开 BUCC 查看待办',
            notificationId: DAILY_TODO_NOTIFICATION_ID,
            wantAgent: this.reminderWantAgent('todo')
          };
          const reminderId: number = await reminderAgentManager.publishReminder(dailyRequest);
          batch.push(reminderId);
          await this.persistIds(prefs, batch);
        }

        for (let index: number = 0; index < courses.length; index++) {
          if (isCancelled()) {
            return await this.cleanupBatch(prefs, batch, 'unavailable');
          }
          const item: ReminderPlanItem = courses[index];
          const notificationId: number = 32100 + index;
          const request: reminderAgentManager.ReminderRequestCalendar = {
            reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_CALENDAR,
            dateTime: localDateTime(item.triggerAtMillis),
            title: '课前提醒',
            content: '课程即将开始',
            notificationId: notificationId,
            wantAgent: this.reminderWantAgent('schedule')
          };
          const reminderId: number = await reminderAgentManager.publishReminder(request);
          batch.push(reminderId);
          await this.persistIds(prefs, batch);
        }
        return clipped ? 'partial_capacity' : 'scheduled_approximate';
      } catch (_error) {
        const code: number = errorCode(_error as BusinessError);
        if (code === 1700002) {
          await this.persistIds(prefs, batch);
          return 'partial_capacity';
        }
        if (code === 201 || code === 1700001) {
          return await this.cleanupBatch(prefs, batch, 'permission_required');
        }
        return await this.cleanupBatch(prefs, batch, 'unavailable');
      }
    });
  }

  async cancel(): Promise<void> {
    await this.exclusive(async (): Promise<void> => {
      const prefs: preferences.Preferences = await preferences.getPreferences(this.context, PREF_NAME);
      await this.cancelOwned(prefs);
    });
  }

  async permissionState(): Promise<string> {
    try {
      if (await notificationManager.isNotificationEnabled()) {
        return 'granted';
      }
      const prefs: preferences.Preferences = await preferences.getPreferences(this.context, PREF_NAME);
      const requested: preferences.ValueType = await prefs.get(PERMISSION_REQUESTED_KEY, false);
      return requested === true ? 'denied' : 'not_determined';
    } catch (_error) {
      return 'unsupported';
    }
  }

  async requestPermission(): Promise<string> {
    try {
      const prefs: preferences.Preferences = await preferences.getPreferences(this.context, PREF_NAME);
      await prefs.put(PERMISSION_REQUESTED_KEY, true);
      await prefs.flush();
    } catch (_error) {
      return 'unsupported';
    }
    try {
      await notificationManager.requestEnableNotification(this.context);
    } catch (_error) {
      return await this.permissionState();
    }
    return await this.permissionState();
  }

  async openSettings(): Promise<void> {
    try {
      if (deviceInfo.sdkApiVersion >= 26) {
        settings.openAppDetailSettingsPage(this.context, BUNDLE_NAME);
      } else {
        await notificationManager.openNotificationSettings(this.context);
      }
    } catch (_error) {
      // Keep platform errors out of logs and the UI bridge.
    }
  }

  async notifyHomework(): Promise<void> {
    try {
      await notificationManager.requestEnableNotification(this.context);
      const agent = await this.createWantAgent('todo', 32001);
      await notificationManager.publish({
        id: 32001,
        content: {
          notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: {
            title: '有新作业',
            text: '打开 BUCC 查看待办'
          }
        },
        wantAgent: agent
      });
    } catch (_error) {
      // Notification failures are intentionally silent; no business or account data is logged.
    }
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

  private validPlan(plan: ReminderPlanItem[]): Array<ReminderPlanItem> {
    const now: number = Date.now();
    const seen: Set<string> = new Set<string>();
    const valid: Array<ReminderPlanItem> = [];
    for (let index: number = 0; index < plan.length; index++) {
      const item: ReminderPlanItem = plan[index];
      if (item.eventId.length === 0 || !Number.isFinite(item.triggerAtMillis) || item.triggerAtMillis <= now ||
        seen.has(item.eventId)) {
        continue;
      }
      seen.add(item.eventId);
      valid.push(item);
    }
    valid.sort((left: ReminderPlanItem, right: ReminderPlanItem): number =>
      left.triggerAtMillis - right.triggerAtMillis);
    return valid;
  }

  private async createWantAgent(route: string, requestCode: number): Promise<WantAgent> {
    return wantAgent.getWantAgent({
      wants: [{
        bundleName: BUNDLE_NAME,
        abilityName: ENTRY_ABILITY,
        parameters: { route: route }
      }],
      actionType: wantAgent.OperationType.START_ABILITY,
      requestCode: requestCode,
      actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
    });
  }

  private reminderWantAgent(route: string): reminderAgentManager.WantAgent {
    return { pkgName: BUNDLE_NAME, abilityName: ENTRY_ABILITY, parameters: { route: route } };
  }

  private async loadIds(prefs: preferences.Preferences): Promise<Array<number>> {
    const value: preferences.ValueType = await prefs.get(PREF_KEY, '[]');
    if (typeof value !== 'string') {
      return [];
    }
    try {
      const parsed: number[] = JSON.parse(value) as number[];
      return parsed.filter((id: number): boolean => Number.isInteger(id) && id > 0);
    } catch (_error) {
      return [];
    }
  }

  private async persistIds(prefs: preferences.Preferences, ids: Array<number>): Promise<void> {
    await prefs.put(PREF_KEY, JSON.stringify(ids));
    await prefs.flush();
  }

  private async cancelOwned(prefs: preferences.Preferences): Promise<boolean> {
    const ids: Array<number> = await this.loadIds(prefs);
    const remaining: Array<number> = [];
    for (let index: number = 0; index < ids.length; index++) {
      try {
        await reminderAgentManager.cancelReminder(ids[index]);
      } catch (_error) {
        if (errorCode(_error as BusinessError) !== 1700003) {
          remaining.push(ids[index]);
        }
      }
    }
    await this.persistIds(prefs, remaining);
    return remaining.length === 0;
  }

  private async cleanupBatch(prefs: preferences.Preferences, batch: Array<number>, status: string): Promise<string> {
    const remaining: Array<number> = [];
    for (let index: number = 0; index < batch.length; index++) {
      try {
        await reminderAgentManager.cancelReminder(batch[index]);
      } catch (_error) {
        if (errorCode(_error as BusinessError) !== 1700003) {
          remaining.push(batch[index]);
        }
      }
    }
    try {
      await this.persistIds(prefs, remaining);
    } catch (_error) {
      return 'unavailable';
    }
    return remaining.length === 0 ? status : 'unavailable';
  }
}
