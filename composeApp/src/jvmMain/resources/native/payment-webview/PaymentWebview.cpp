#include "include/webview/webview.h"

extern "C" {

void *bucc_webview_create(void *nsWindow) {
  return webview_create(0, nsWindow);
}

void *bucc_webview_browser(void *w) {
  return webview_get_native_handle(
      w, WEBVIEW_NATIVE_HANDLE_KIND_BROWSER_CONTROLLER);
}

void bucc_webview_destroy(void *w) {
  if (w != nullptr) {
    webview_destroy(w);
  }
}

} // extern "C"
