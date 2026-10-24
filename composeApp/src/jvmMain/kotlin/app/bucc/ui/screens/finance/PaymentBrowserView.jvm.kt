package app.bucc.ui.screens.finance

import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory

internal actual fun paymentNativeWebViewFactory(param: WebViewFactoryParam): NativeWebView =
    defaultWebViewFactory(param)
