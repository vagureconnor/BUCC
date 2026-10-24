import util from '@ohos.util';
import type { Permissions } from '@kit.AbilityKit';
import abilityAccessCtrl from '@ohos.abilityAccessCtrl';
import calendarManager from '@ohos.calendarManager';
import common from '@ohos.app.ability.common';
import cryptoFramework from '@ohos.security.cryptoFramework';

export interface CourseCalendarEvent {
  id: string;
  title: string;
  location: string;
  startMillis: number;
  endMillis: number;
}

export interface CalendarPayload {
  accountId: string;
  termName: string;
  events: CourseCalendarEvent[];
  reminderMinutes?: number;
}

export interface CalendarResult {
  status: string;
  eventCount: number;
  notificationStatus: string;
}

const CALENDAR_ACCOUNT_NAME: string = 'BUCC courses';
const CALENDAR_DISPLAY_NAME: string = 'BUCC课程';
const CALENDAR_TIME_ZONE: string = 'Asia/Shanghai';
const MAX_EVENT_COUNT: number = 2000;

function calendarResult(status: string, eventCount: number, notificationStatus: string): CalendarResult {
  return {
    status: status,
    eventCount: eventCount,
    notificationStatus: notificationStatus
  };
}

function isValidTimestamp(value: number): boolean {
  return Number.isFinite(value) && value > 0 && value <= 8640000000000000;
}

async function eventIdentifier(
  accountId: string,
  termName: string,
  eventId: string,
  isCancelled: () => boolean
): Promise<string | null> {
  if (isCancelled()) {
    return null;
  }
  const digest = cryptoFramework.createMd('SHA256');
  const input: Uint8Array = new util.TextEncoder().encodeInto(accountId + '\0' + termName + '\0' + eventId);
  if (isCancelled()) {
    return null;
  }
  await digest.update({ data: input });
  if (isCancelled()) {
    return null;
  }
  const result = await digest.digest();
  if (isCancelled()) {
    return null;
  }
  let hex: string = '';
  for (let index: number = 0; index < result.data.length; index += 1) {
    const part: string = result.data[index].toString(16);
    hex += part.length === 1 ? '0' + part : part;
  }
  return 'BUCC:' + hex;
}

function notificationStatus(reminderMinutes?: number): string {
  if (reminderMinutes === undefined) {
    return '日程已导入，未设置日历提醒。';
  }
  return '已设置提前' + reminderMinutes.toString() + '分钟的日历提醒；提醒显示受系统日历设置影响。';
}

export class CalendarHost {
  private readonly context: common.UIAbilityContext;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  async importCourses(payload: CalendarPayload, isCancelled: () => boolean): Promise<CalendarResult> {
    let completed: number = 0;
    try {
      if (isCancelled()) {
        return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
      }
      const inputEvents: CourseCalendarEvent[] | null = this.validatedEvents(payload);
      if (inputEvents === null) {
        return calendarResult('error', 0, '课程数据无效，请重新检查后重试。');
      }

      const accessManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
      const requestedPermissions: Array<Permissions> = [
        'ohos.permission.READ_CALENDAR',
        'ohos.permission.WRITE_CALENDAR'
      ];
      if (isCancelled()) {
        return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
      }
      const permissionResult = await accessManager.requestPermissionsFromUser(this.context, requestedPermissions);
      if (isCancelled()) {
        return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
      }
      if (permissionResult.authResults.length !== requestedPermissions.length ||
        permissionResult.authResults[0] !== 0 || permissionResult.authResults[1] !== 0) {
        return calendarResult('permission', 0, '请在系统权限设置中允许日历读写权限后重试。');
      }

      const manager: calendarManager.CalendarManager = calendarManager.getCalendarManager(this.context);
      if (isCancelled()) {
        return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
      }
      const calendars: calendarManager.Calendar[] = await manager.getAllCalendars();
      if (isCancelled()) {
        return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
      }

      let ownCalendar: calendarManager.Calendar | null = null;
      for (const calendar of calendars) {
        const account: calendarManager.CalendarAccount = calendar.getAccount();
        if (account.name === CALENDAR_ACCOUNT_NAME && account.type === calendarManager.CalendarType.LOCAL) {
          ownCalendar = calendar;
          break;
        }
      }
      if (ownCalendar === null) {
        if (isCancelled()) {
          return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
        }
        const account: calendarManager.CalendarAccount = {
          name: CALENDAR_ACCOUNT_NAME,
          type: calendarManager.CalendarType.LOCAL,
          displayName: CALENDAR_DISPLAY_NAME
        };
        ownCalendar = await manager.createCalendar(account);
        if (isCancelled()) {
          return calendarResult('cancelled', 0, '日历已准备，但导入已取消，未写入课程。');
        }
      }

      if (payload.reminderMinutes !== undefined) {
        if (isCancelled()) {
          return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
        }
        await ownCalendar.setConfig({ enableReminder: true });
        if (isCancelled()) {
          return calendarResult('cancelled', 0, '日历已准备，但导入已取消，未写入课程。');
        }
      }

      if (isCancelled()) {
        return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
      }
      // Omitting both optional arguments includes identifier on API 20+.
      // Passing undefined explicitly is rejected by the native API 26 binding.
      const existingEvents: calendarManager.Event[] = await ownCalendar.getEvents();
      if (isCancelled()) {
        return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
      }
      const eventsByIdentifier: Map<string, calendarManager.Event> = new Map<string, calendarManager.Event>();
      for (const existing of existingEvents) {
        if (existing.identifier !== undefined && existing.identifier.startsWith('BUCC:')) {
          eventsByIdentifier.set(existing.identifier, existing);
        }
      }

      for (const course of inputEvents) {
        if (isCancelled()) {
          return this.interruptedResult(completed);
        }
        const identifier: string | null = await eventIdentifier(
          payload.accountId,
          payload.termName,
          course.id,
          isCancelled
        );
        if (identifier === null || isCancelled()) {
          return this.interruptedResult(completed);
        }

        const existing: calendarManager.Event | undefined = eventsByIdentifier.get(identifier);
        const event: calendarManager.Event = {
          type: calendarManager.EventType.NORMAL,
          title: course.title,
          location: { location: course.location },
          startTime: course.startMillis,
          endTime: course.endMillis,
          timeZone: CALENDAR_TIME_ZONE,
          identifier: identifier
        };
        if (payload.reminderMinutes !== undefined) {
          event.reminderTime = [payload.reminderMinutes];
        }
        if (existing !== undefined) {
          if (existing.id === undefined) {
            throw new Error('Existing BUCC event has no id');
          }
          event.id = existing.id;
          if (isCancelled()) {
            return this.interruptedResult(completed);
          }
          await ownCalendar.updateEvent(event);
        } else {
          if (isCancelled()) {
            return this.interruptedResult(completed);
          }
          event.id = await ownCalendar.addEvent(event);
          eventsByIdentifier.set(identifier, event);
        }
        completed += 1;
        if (isCancelled()) {
          return this.interruptedResult(completed);
        }
      }

      return calendarResult('success', completed, notificationStatus(payload.reminderMinutes));
    } catch (_error) {
      if (isCancelled()) {
        return this.interruptedResult(completed);
      }
      if (completed > 0) {
        return calendarResult('partial', completed, '部分日程未导入，可手动重试；已有日程不会重复。');
      }
      return calendarResult('error', 0, '日历导入失败，请检查权限和系统日历后重试。');
    }
  }

  private validatedEvents(payload: CalendarPayload): CourseCalendarEvent[] | null {
    if (payload.accountId.length === 0 || payload.termName.length === 0 ||
      payload.events.length > MAX_EVENT_COUNT ||
      (payload.reminderMinutes !== undefined &&
        (!Number.isFinite(payload.reminderMinutes) || payload.reminderMinutes < 0))) {
      return null;
    }
    const unique: CourseCalendarEvent[] = [];
    const seenIds: Set<string> = new Set<string>();
    for (const event of payload.events) {
      if (event.id.length === 0 || event.title.length === 0 || event.title.length > 5000 ||
        event.location.length > 5000 || !isValidTimestamp(event.startMillis) ||
        !isValidTimestamp(event.endMillis) || event.endMillis <= event.startMillis) {
        return null;
      }
      if (!seenIds.has(event.id)) {
        seenIds.add(event.id);
        unique.push(event);
      }
    }
    return unique;
  }

  private interruptedResult(completed: number): CalendarResult {
    if (completed === 0) {
      return calendarResult('cancelled', 0, '导入已取消，未写入日程。');
    }
    return calendarResult('partial', completed, '部分日程未导入，可手动重试；已有日程不会重复。');
  }
}
