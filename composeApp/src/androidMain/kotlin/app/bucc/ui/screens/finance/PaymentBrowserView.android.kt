package app.bucc.ui.screens.finance

import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.WebViewFactoryParam

internal actual fun paymentNativeWebViewFactory(param: WebViewFactoryParam): NativeWebView =
    PaymentRootWebView(param.context)
