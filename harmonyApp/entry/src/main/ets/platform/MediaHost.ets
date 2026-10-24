import common from '@ohos.app.ability.common';
import camera from '@ohos.multimedia.camera';
import cameraPicker from '@ohos.multimedia.cameraPicker';
import fileIo from '@ohos.file.fs';
import photoAccessHelper from '@ohos.file.photoAccessHelper';
import picker from '@ohos.file.picker';
import scanBarcode from '@hms.core.scan.scanBarcode';
import scanCore from '@hms.core.scan.scanCore';
import type { BusinessError } from '@ohos.base';

export interface MediaResult {
  status: string;
  bytes?: ArrayBuffer;
  fileName?: string;
  mimeType?: string;
  value?: string;
}

const IMAGE_MAX_BYTES: number = 20 * 1024 * 1024;
const ATTACHMENT_MAX_BYTES: number = 10 * 1024 * 1024;

function result(status: string): MediaResult {
  return { status: status };
}

function baseName(uriOrName: string): string {
  const clean: string = uriOrName.split('?')[0].split('#')[0];
  const last: string = clean.substring(clean.lastIndexOf('/') + 1);
  try {
    return decodeURIComponent(last);
  } catch (_error) {
    return last;
  }
}

function mimeType(fileName: string): string {
  const extension: string = fileName.toLowerCase().substring(fileName.lastIndexOf('.') + 1);
  switch (extension) {
    case 'jpg':
    case 'jpeg':
      return 'image/jpeg';
    case 'png':
      return 'image/png';
    case 'bmp':
      return 'image/bmp';
    case 'gif':
      return 'image/gif';
    case 'webp':
      return 'image/webp';
    case 'heic':
      return 'image/heic';
    case 'heif':
      return 'image/heif';
    case 'pdf':
      return 'application/pdf';
    default:
      return 'application/octet-stream';
  }
}

function hasExpectedAttachmentSignature(fileName: string, bytes: Uint8Array): boolean {
  const extension: string = fileName.toLowerCase().substring(fileName.lastIndexOf('.') + 1);
  if (extension === 'pdf') {
    return bytes.length >= 5 && bytes[0] === 0x25 && bytes[1] === 0x50 &&
      bytes[2] === 0x44 && bytes[3] === 0x46 && bytes[4] === 0x2d;
  }
  if (extension === 'png') {
    return bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 &&
      bytes[2] === 0x4e && bytes[3] === 0x47 && bytes[4] === 0x0d &&
      bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a;
  }
  if (extension === 'jpg' || extension === 'jpeg') {
    return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  }
  if (extension === 'bmp') {
    return bytes.length >= 2 && bytes[0] === 0x42 && bytes[1] === 0x4d;
  }
  return false;
}

function readUri(uri: string, fileName: string, limit: number): MediaResult {
  let file: fileIo.File | null = null;
  try {
    file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
    const fileSize: number = fileIo.statSync(file.fd).size;
    if (fileSize <= 0 || fileSize > limit) {
      return result('error');
    }

    const data: ArrayBuffer = new ArrayBuffer(fileSize);
    const bytes: Uint8Array = new Uint8Array(data);
    let offset: number = 0;
    while (offset < fileSize) {
      const chunk: ArrayBuffer = new ArrayBuffer(Math.min(64 * 1024, fileSize - offset));
      const count: number = fileIo.readSync(file.fd, chunk);
      if (count <= 0) {
        return result('error');
      }
      bytes.set(new Uint8Array(chunk, 0, count), offset);
      offset += count;
    }
    const output: MediaResult = {
      status: 'success',
      bytes: data,
      fileName: baseName(fileName),
      mimeType: mimeType(fileName)
    };
    return output;
  } catch (_error) {
    return result('error');
  } finally {
    if (file !== null) {
      try {
        fileIo.closeSync(file);
      } catch (_error) {
        return result('error');
      }
    }
  }
}

export class MediaHost {
  private readonly context: common.UIAbilityContext;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  async run(operation: string, isCancelled: () => boolean): Promise<MediaResult> {
    if (isCancelled()) {
      return result('cancelled');
    }
    try {
      let output: MediaResult;
      switch (operation) {
        case 'pickPhoto':
          output = await this.pickPhoto(isCancelled);
          break;
        case 'capturePhoto':
          output = await this.capturePhoto(isCancelled);
          break;
        case 'pickLibraryAttachment':
          output = await this.pickLibraryAttachment(isCancelled);
          break;
        case 'scanLibraryQr':
          output = await this.scanLibraryQr(isCancelled);
          break;
        default:
          return result('error');
      }
      return isCancelled() ? result('cancelled') : output;
    } catch (_error) {
      return result('error');
    }
  }

  private async pickPhoto(isCancelled: () => boolean): Promise<MediaResult> {
    const photoPicker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
    const selection: photoAccessHelper.PhotoSelectResult = await photoPicker.select({
      MIMEType: photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE,
      maxSelectNumber: 1
    });
    if (isCancelled()) {
      return result('cancelled');
    }
    if (selection.photoUris.length === 0) {
      return result('cancelled');
    }

    // The picker grants read access to this URI; querying the whole media library
    // would introduce an unnecessary READ_IMAGEVIDEO permission requirement.
    const uri: string = selection.photoUris[0];
    return readUri(uri, baseName(uri), IMAGE_MAX_BYTES);
  }

  private async capturePhoto(isCancelled: () => boolean): Promise<MediaResult> {
    const profile: cameraPicker.PickerProfile = new cameraPicker.PickerProfile();
    profile.cameraPosition = camera.CameraPosition.CAMERA_POSITION_BACK;
    const captured: cameraPicker.PickerResult = await cameraPicker.pick(
      this.context,
      [cameraPicker.PickerMediaType.PHOTO],
      profile
    );
    if (isCancelled()) {
      return result('cancelled');
    }
    if (captured.resultCode !== 0 || captured.resultUri.length === 0) {
      return result('error');
    }
    return readUri(captured.resultUri, baseName(captured.resultUri), IMAGE_MAX_BYTES);
  }

  private async pickLibraryAttachment(isCancelled: () => boolean): Promise<MediaResult> {
    const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(this.context);
    const uris: Array<string> = await documentPicker.select({
      maxSelectNumber: 1,
      fileSuffixFilters: ['Images (.jpg, .jpeg, .png, .bmp)|.jpg,.jpeg,.png,.bmp', 'PDF|.pdf']
    });
    if (isCancelled()) {
      return result('cancelled');
    }
    if (uris.length === 0) {
      return result('cancelled');
    }

    const fileName: string = baseName(uris[0]);
    const selected: MediaResult = readUri(uris[0], fileName, ATTACHMENT_MAX_BYTES);
    if (selected.status !== 'success' || selected.bytes === undefined) {
      return selected;
    }
    if (!hasExpectedAttachmentSignature(fileName, new Uint8Array(selected.bytes))) {
      return result('error');
    }
    return selected;
  }

  private async scanLibraryQr(isCancelled: () => boolean): Promise<MediaResult> {
    try {
      const scanned: scanBarcode.ScanResult = await scanBarcode.startScanForResult(this.context, {
        scanTypes: [scanCore.ScanType.QR_CODE],
        enableMultiMode: false
      });
      if (isCancelled()) {
        return result('cancelled');
      }
      return { status: 'success', value: scanned.originalValue };
    } catch (error) {
      if (isCancelled()) {
        return result('cancelled');
      }
      const scanError: BusinessError = error as BusinessError;
      if (scanError.code === 1000500002) {
        return result('cancelled');
      }
      return result('error');
    }
  }
}
