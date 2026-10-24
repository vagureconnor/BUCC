import { ArkUIViewController } from '@cpf-kmp-cmp/compose/src/main/cpp/types/libcompose_arkui_utils';

export interface HostRequest { id: number; operation: string; payload: string; }
export interface HostCancellation { id: number; }
export interface KeepAwakeRequest { enabled: boolean; }
export interface HostResult {
  status: string;
  code?: string;
  bytes?: ArrayBuffer;
  fileName?: string;
  mimeType?: string;
  value?: string;
  eventCount?: number;
  notificationStatus?: string;
  kind?: string;
  url?: string;
}
export interface HostBridge {
  request: (request: HostRequest) => void;
  cancel: (request: HostCancellation) => void;
  keepAwake: (request: KeepAwakeRequest) => void;
  paymentBack: (request: HostCancellation) => boolean;
  closePayment: (request: HostCancellation) => void;
}
export const createController: (files: string, cache: string, bridge: HostBridge) => ArkUIViewController;
export const completeRequest: (id: number, result: HostResult) => void;
export const handleBack: () => boolean;
export const notifyBackground: () => void;
export const refreshHomeworkOnForeground: () => void;
export const notificationRoute: (route: string) => void;
export const allowPaymentUrl: (id: number, url: string) => boolean;
export const allowPaymentRequest: (id: number, url: string, method: string, isRoot: boolean) => boolean;
export const allowPaymentBack: (id: number, url: string) => boolean;
export const allowPaymentCookie: (id: number, origin: string) => boolean;
