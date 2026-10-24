#include "napi/native_api.h"
#include <string>

extern "C" {
napi_value androidx_compose_ui_arkui_init(napi_env, napi_value);
napi_value bucc_create_controller(napi_env, const char *, const char *, napi_value);
void bucc_complete_request(int, napi_value);
bool bucc_handle_back();
void bucc_notify_background();
void bucc_refresh_homework_on_foreground();
void bucc_notification_route(const char *);
bool bucc_payment_allow_url(int, const char *);
bool bucc_payment_allow_request(int, const char *, const char *, bool);
bool bucc_payment_allow_back(int, const char *);
bool bucc_payment_allow_cookie(int, const char *);
}

static bool ReadText(napi_env env, napi_value value, size_t maximum, std::string &text) {
    size_t length = 0;
    if (napi_get_value_string_utf8(env, value, nullptr, 0, &length) != napi_ok || length == 0 || length > maximum) return false;
    text.resize(length + 1);
    size_t copied = 0;
    if (napi_get_value_string_utf8(env, value, text.data(), text.size(), &copied) != napi_ok || copied != length) return false;
    text.resize(copied);
    return text.find('\0') == std::string::npos;
}
static bool ReadDirectory(napi_env env, napi_value value, std::string &directory) {
    return ReadText(env, value, 4096, directory) && directory[0] == '/';
}

static napi_value Create(napi_env env, napi_callback_info info) {
    napi_value arguments[3]; size_t count = 3;
    napi_get_cb_info(env, info, &count, arguments, nullptr, nullptr);
    std::string files, cache; napi_valuetype kind;
    if (count != 3 || !ReadDirectory(env, arguments[0], files) || !ReadDirectory(env, arguments[1], cache) ||
        napi_typeof(env, arguments[2], &kind) != napi_ok || kind != napi_object) {
        napi_throw_type_error(env, nullptr, "Invalid BUCC application host");
        return nullptr;
    }
    return bucc_create_controller(env, files.c_str(), cache.c_str(), arguments[2]);
}

static napi_value Complete(napi_env env, napi_callback_info info) {
    napi_value arguments[2]; size_t count = 2; int32_t id = 0; napi_valuetype kind;
    napi_get_cb_info(env, info, &count, arguments, nullptr, nullptr);
    if (count != 2 || napi_get_value_int32(env, arguments[0], &id) != napi_ok || id <= 0 ||
        napi_typeof(env, arguments[1], &kind) != napi_ok || kind != napi_object) {
        napi_throw_type_error(env, nullptr, "Invalid BUCC host response");
        return nullptr;
    }
    bucc_complete_request(id, arguments[1]);
    napi_value result; napi_get_undefined(env, &result); return result;
}

static napi_value Back(napi_env env, napi_callback_info) {
    napi_value result; napi_get_boolean(env, bucc_handle_back(), &result); return result;
}
static napi_value Background(napi_env env, napi_callback_info) {
    bucc_notify_background();
    napi_value result; napi_get_undefined(env, &result); return result;
}
static napi_value RefreshHomework(napi_env env, napi_callback_info) {
    bucc_refresh_homework_on_foreground();
    napi_value result; napi_get_undefined(env, &result); return result;
}
static napi_value Route(napi_env env, napi_callback_info info) {
    napi_value value; size_t count = 1; std::string route;
    napi_get_cb_info(env, info, &count, &value, nullptr, nullptr);
    if (count == 1 && ReadText(env, value, 16, route)) bucc_notification_route(route.c_str());
    napi_value result; napi_get_undefined(env, &result); return result;
}
static napi_value CheckPayment(napi_env env, napi_callback_info info, int kind) {
    napi_value arguments[4]; size_t count = 4; int32_t id = 0; std::string url, method; bool root = false;
    napi_get_cb_info(env, info, &count, arguments, nullptr, nullptr);
    bool valid = count == (kind == 1 ? 4 : 2) && napi_get_value_int32(env, arguments[0], &id) == napi_ok && id > 0 &&
        ReadText(env, arguments[1], 8192, url);
    if (valid && kind == 1) valid = ReadText(env, arguments[2], 16, method) && napi_get_value_bool(env, arguments[3], &root) == napi_ok;
    bool allowed = valid && (kind == 0 ? bucc_payment_allow_url(id, url.c_str()) :
        kind == 1 ? bucc_payment_allow_request(id, url.c_str(), method.c_str(), root) :
        kind == 2 ? bucc_payment_allow_back(id, url.c_str()) : bucc_payment_allow_cookie(id, url.c_str()));
    napi_value result; napi_get_boolean(env, allowed, &result); return result;
}
static napi_value AllowUrl(napi_env env, napi_callback_info info) { return CheckPayment(env, info, 0); }
static napi_value AllowRequest(napi_env env, napi_callback_info info) { return CheckPayment(env, info, 1); }
static napi_value AllowBack(napi_env env, napi_callback_info info) { return CheckPayment(env, info, 2); }
static napi_value AllowCookie(napi_env env, napi_callback_info info) { return CheckPayment(env, info, 3); }
static napi_value Initialize(napi_env env, napi_value exports) {
    androidx_compose_ui_arkui_init(env, exports);
    napi_property_descriptor methods[] = {
        {"createController", nullptr, Create, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"completeRequest", nullptr, Complete, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"handleBack", nullptr, Back, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"notifyBackground", nullptr, Background, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"refreshHomeworkOnForeground", nullptr, RefreshHomework, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"notificationRoute", nullptr, Route, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"allowPaymentUrl", nullptr, AllowUrl, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"allowPaymentRequest", nullptr, AllowRequest, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"allowPaymentBack", nullptr, AllowBack, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"allowPaymentCookie", nullptr, AllowCookie, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(methods) / sizeof(methods[0]), methods);
    return exports;
}
static napi_module module = {.nm_version = 1, .nm_flags = 0, .nm_filename = nullptr,
    .nm_register_func = Initialize, .nm_modname = "entry", .nm_priv = nullptr, .reserved = {nullptr}};
extern "C" __attribute__((constructor)) void RegisterBuccHost() { napi_module_register(&module); }
