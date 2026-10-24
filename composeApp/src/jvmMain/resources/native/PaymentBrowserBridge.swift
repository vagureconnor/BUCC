import AppKit
import Foundation
import WebKit
import CoreImage

@_silgen_name("bucc_webview_create") private func webviewCreate(_ window: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer?
@_silgen_name("bucc_webview_browser") private func webviewBrowser(_ engine: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer?
@_silgen_name("bucc_webview_destroy") private func webviewDestroy(_ engine: UnsafeMutableRawPointer?)

public typealias BuccPaymentPolicy = @convention(c) (UnsafePointer<CChar>?, UnsafePointer<CChar>?, Int32) -> Int32
public typealias BuccPaymentEvent = @convention(c) (Int64, UnsafePointer<CChar>?) -> Void

private var paymentWindows: [Int64: PaymentBrowserWindow] = [:]
private var nextPaymentWindow: Int64 = 1
private func paymentOnMain<T>(_ operation: () -> T) -> T {
    if Thread.isMainThread { return operation() }
    return DispatchQueue.main.sync(execute: operation)
}

private final class PaymentBrowserWindow: NSObject, WKNavigationDelegate, WKUIDelegate, NSWindowDelegate {
    let id: Int64
    let window: NSWindow
    let root: WKWebView
    let engineWindow: NSWindow
    let embedded: Bool
    let container = NSView()
    let engine: UnsafeMutableRawPointer
    let policy: BuccPaymentPolicy
    let callback: BuccPaymentEvent
    let host = NSView()
    let status = NSTextField(labelWithString: "正在打开官方支付页面…")
    private weak var toolbar: NSStackView?
    private weak var toolbarControls: NSStackView?
    private var pages: [WKWebView] = []
    private var loadTimeout: DispatchWorkItem?
    private var closed = false
    private var postHistory = Set<URL>()
    private var responseErrors: [ObjectIdentifier: Int] = [:]
    private var pendingExternal: URL?
    private var pendingDialog: (view: WKWebView, cancel: () -> Void)?
    private let recoveryCover = NSView()
    private let recoveryLabel = NSTextField(labelWithString: "正在打开支付页面…")
    private var recoveryEnabled = false
    private var recoveryCovered = false

    init?(id: Int64, config: [String: Any], policy: @escaping BuccPaymentPolicy, callback: @escaping BuccPaymentEvent) {
        self.id = id; self.policy = policy; self.callback = callback
        engineWindow = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 760),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        engineWindow.isReleasedWhenClosed = false
        if let address = config["parentWindow"] as? NSNumber, let pointer = UnsafeMutableRawPointer(bitPattern: address.uintValue) {
            window = Unmanaged<NSWindow>.fromOpaque(pointer).takeUnretainedValue()
            embedded = true
        } else { window = engineWindow; embedded = false }
        guard let engine = webviewCreate(Unmanaged.passUnretained(engineWindow).toOpaque()),
              let pointer = webviewBrowser(engine) else { return nil }
        self.engine = engine
        let upstream = Unmanaged<WKWebView>.fromOpaque(pointer).takeUnretainedValue()
        if config["ephemeral"] as? Bool == true {
            let configuration = WKWebViewConfiguration()
            configuration.websiteDataStore = .nonPersistent()
            configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
            root = WKWebView(frame: upstream.frame, configuration: configuration)
        } else { root = upstream }
        super.init()
        if !embedded {
            window.title = "BUCC · 校园财务"
            window.tabbingMode = .disallowed
            window.delegate = self
            window.contentView = container
        } else { window.contentView?.addSubview(container) }
        let content = container
        let back = NSButton(title: "返回", target: self, action: #selector(backPressed))
        back.bezelStyle = .rounded
        let close = NSButton(title: "关闭支付页", target: self, action: #selector(closePressed))
        close.bezelStyle = .rounded
        let retry = NSButton(title: "重试打开支付页", target: self, action: #selector(retryPressed))
        retry.bezelStyle = .rounded; retry.isHidden = config["post"] == nil
        retry.title = "重新识别"
        let controls = NSStackView(views: [back, retry, close])
        controls.orientation = .horizontal; controls.spacing = 8
        for button in [back, retry, close] {
            button.setContentCompressionResistancePriority(.required, for: .horizontal)
        }
        let toolbar = NSStackView(views: [controls, status])
        self.toolbar = toolbar; toolbarControls = controls
        status.lineBreakMode = .byTruncatingTail
        status.maximumNumberOfLines = 1
        status.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        toolbar.orientation = .vertical; toolbar.alignment = .leading; toolbar.spacing = 2
        toolbar.edgeInsets = NSEdgeInsets(top: 4, left: 12, bottom: 4, right: 12)
        status.widthAnchor.constraint(equalTo: toolbar.widthAnchor, constant: -24).isActive = true
        toolbar.translatesAutoresizingMaskIntoConstraints = false
        host.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(toolbar); content.addSubview(host)
        NSLayoutConstraint.activate([
            toolbar.leadingAnchor.constraint(equalTo: content.leadingAnchor), toolbar.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            toolbar.topAnchor.constraint(equalTo: content.topAnchor), toolbar.heightAnchor.constraint(equalToConstant: 64),
            host.leadingAnchor.constraint(equalTo: content.leadingAnchor), host.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            host.topAnchor.constraint(equalTo: toolbar.bottomAnchor), host.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])
        recoveryEnabled = config["post"] != nil
        recoveryCovered = recoveryEnabled
        recoveryCover.wantsLayer = true
        recoveryCover.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
        recoveryLabel.alignment = .center
        recoveryLabel.lineBreakMode = .byWordWrapping
        recoveryLabel.maximumNumberOfLines = 0
        recoveryLabel.translatesAutoresizingMaskIntoConstraints = false
        recoveryCover.addSubview(recoveryLabel)
        NSLayoutConstraint.activate([
            recoveryLabel.centerXAnchor.constraint(equalTo: recoveryCover.centerXAnchor),
            recoveryLabel.centerYAnchor.constraint(equalTo: recoveryCover.centerYAnchor),
            recoveryLabel.widthAnchor.constraint(lessThanOrEqualTo: recoveryCover.widthAnchor, multiplier: 0.85),
        ])
        install(root)
        pages = [root]
        display(root)
        if embedded {
            setBounds(x: config["x"] as? Double ?? 0, y: config["y"] as? Double ?? 0,
                      width: config["width"] as? Double ?? 0, height: config["height"] as? Double ?? 0)
        } else { window.center(); window.makeKeyAndOrderFront(nil) }
        let cookies = config["cookies"] as? [[String: Any]] ?? []
        let group = DispatchGroup()
        for cookie in cookies {
            guard let origin = cookie["origin"] as? String, let url = URL(string: origin), let host = url.host,
                  let name = cookie["name"] as? String, let value = cookie["value"] as? String else { continue }
            var properties: [HTTPCookiePropertyKey: Any] = [
                .name: name, .value: value, .domain: cookie["domain"] as? String ?? host,
                .path: cookie["path"] as? String ?? "/", .originURL: url,
            ]
            if cookie["secure"] as? Bool == true { properties[.secure] = "TRUE" }
            if cookie["httpOnly"] as? Bool == true { properties[HTTPCookiePropertyKey("HttpOnly")] = "TRUE" }
            guard let native = HTTPCookie(properties: properties) else { continue }
            group.enter(); root.configuration.websiteDataStore.httpCookieStore.setCookie(native) { group.leave() }
        }
        // Cookie completion precedes the one and only initial request; no JS form reconstruction.
        group.notify(queue: .main) { [weak self] in
            guard let self, !self.closed, let raw = config["url"] as? String, let url = URL(string: raw) else { return }
            var request = URLRequest(url: url)
            if let encoded = config["post"] as? String, let body = Data(base64Encoded: encoded) {
                request.httpMethod = "POST"; request.httpBody = body
                request.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
                if let host = url.host { request.setValue("https://\(host)", forHTTPHeaderField: "Origin") }
                request.setValue(raw, forHTTPHeaderField: "Referer")
            }
            self.event(["type": "loading"])
            self.root.load(request)
            self.armTimeout()
        }
    }

    func setBounds(x: Double, y: Double, width: Double, height: Double) {
        guard embedded, let content = window.contentView else { return }
        let top = content.isFlipped ? y : content.bounds.height - y - height
        container.frame = NSRect(x: x, y: top, width: max(0, width), height: max(0, height))
        container.layoutSubtreeIfNeeded()
    }
    private func install(_ view: WKWebView) {
        view.navigationDelegate = self; view.uiDelegate = self
        view.configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        // The generic upstream widget permits clipboard scripting; financial pages do not need it.
        view.configuration.preferences.setValue(false, forKey: "javaScriptCanAccessClipboard")
        view.configuration.preferences.setValue(false, forKey: "DOMPasteAllowed")
        view.allowsBackForwardNavigationGestures = false
    }
    private func display(_ view: WKWebView) {
        if recoveryEnabled && isQrResponse(view.url) { recoveryCovered = true }
        host.subviews.forEach { $0.removeFromSuperview() }
        view.removeFromSuperview(); view.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(view)
        NSLayoutConstraint.activate([view.leadingAnchor.constraint(equalTo: host.leadingAnchor), view.trailingAnchor.constraint(equalTo: host.trailingAnchor),
                                     view.topAnchor.constraint(equalTo: host.topAnchor), view.bottomAnchor.constraint(equalTo: host.bottomAnchor)])
        updateRecoveryCover()
    }
    private func updateRecoveryCover() {
        recoveryCover.removeFromSuperview()
        guard recoveryCovered else { return }
        recoveryCover.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(recoveryCover)
        NSLayoutConstraint.activate([
            recoveryCover.leadingAnchor.constraint(equalTo: host.leadingAnchor), recoveryCover.trailingAnchor.constraint(equalTo: host.trailingAnchor),
            recoveryCover.topAnchor.constraint(equalTo: host.topAnchor), recoveryCover.bottomAnchor.constraint(equalTo: host.bottomAnchor),
        ])
    }
    func coverResponse(_ message: String?) {
        recoveryCovered = message != nil
        recoveryLabel.stringValue = message ?? ""
        updateRecoveryCover()
        event(["type": "cover", "covered": recoveryCovered])
    }
    private func isQrResponse(_ url: URL?) -> Bool {
        guard let url else { return false }
        return (url.host == "zfw.buaa.edu.cn" || url.host == "d.buaa.edu.cn") &&
            (url.path.hasSuffix("/pay/card") || url.path.hasSuffix("/qrcode"))
    }
    func snapshotQr(_ source: String, token: Int64) {
        guard let current = pages.last, current.url?.absoluteString == source,
              allowed(URLRequest(url: current.url!), rootRequest: false) == 1 else {
            event(["type": "script", "token": token, "value": [], "ok": false]); return
        }
        let configuration = WKSnapshotConfiguration()
        configuration.snapshotWidth = 1024
        current.takeSnapshot(with: configuration) { [weak self, weak current] image, _ in
            guard let self, let current, !self.closed, current === self.pages.last,
                  current.url?.absoluteString == source else { return }
            var payloads: [String] = []
            if let bytes = image?.tiffRepresentation, let ci = CIImage(data: bytes),
               let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) {
                payloads = detector.features(in: ci).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
                    .filter { $0.count <= 8192 }
                payloads = Array(Set(payloads).prefix(16))
            }
            self.event(["type": "script", "token": token, "value": payloads, "ok": true])
        }
    }
    func event(_ values: [String: Any]) {
        guard !closed else { return }
        var payload = values
        payload["canGoBack"] = canGoBack
        payload["recoveryCovered"] = recoveryCovered
        payload["recoveryCoverOnTop"] = recoveryCover.superview === host && host.subviews.last === recoveryCover && !recoveryCover.isHidden
        payload["recoveryCoverFillsContent"] = recoveryCover.superview === host && host.bounds.width > 0 && host.bounds.height > 0 && recoveryCover.frame.contains(host.bounds)
        if let toolbar, let controls = toolbarControls {
            let buttons = controls.convert(controls.bounds, to: toolbar)
            let text = status.convert(status.bounds, to: toolbar)
            let toolbarFrame = toolbar.convert(toolbar.bounds, to: container)
            let webFrame = host.convert(host.bounds, to: container)
            payload["toolbarLayout"] = [
                "buttonsWithin": buttons.width > 0 && toolbar.bounds.contains(buttons),
                "statusWithin": text.width > 0 && toolbar.bounds.contains(text),
                "separated": !buttons.intersects(text),
                "contentBelow": webFrame.maxY <= toolbarFrame.minY + 0.5,
                "buttonCount": controls.arrangedSubviews.filter { !$0.isHidden }.count,
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: payload), let text = String(data: data, encoding: .utf8) else { return }
        text.withCString { callback(id, $0) }
    }
    private var canGoBack: Bool {
        guard let current = pages.last else { return false }
        if let previous = current.backForwardList.backItem?.url,
           previous.absoluteString != "about:blank", !postHistory.contains(previous), !isQrResponse(previous),
           current.canGoBack { return true }
        return pages.count > 1 && !isQrResponse(pages[pages.count - 2].url)
    }
    private func allowed(_ request: URLRequest, rootRequest: Bool) -> Int32 {
        guard let url = request.url?.absoluteString, !url.isEmpty else {
            return !rootRequest && (request.httpMethod ?? "GET") == "GET" ? 1 : 0
        }
        return url.withCString { u in (request.httpMethod ?? "GET").withCString { m in policy(u, m, rootRequest ? 1 : 0) } }
    }
    private func armTimeout() {
        loadTimeout?.cancel()
        let timer = DispatchWorkItem { [weak self] in
            self?.status.stringValue = "支付页面加载超时，请返回；不会自动重发充值请求。"
            self?.event(["type": "error", "reason": "timeout"])
        }
        loadTimeout = timer; DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: timer)
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard !closed else { decisionHandler(.cancel); return }
        let decision = allowed(action.request, rootRequest: webView === root && action.targetFrame?.isMainFrame == true)
        if decision == 2 {
            decisionHandler(.cancel)
            guard let url = action.request.url else { return }
            offerPaymentApp(url)
        } else if decision == 1 {
            if recoveryEnabled && isQrResponse(action.request.url) { coverResponse("正在打开支付页面…") }
            if action.request.httpMethod == "POST", let url = action.request.url { postHistory.insert(url) }
            decisionHandler(.allow)
        } else {
            status.stringValue = "此跳转不在官方支付范围内，已保留当前页面，可返回重试。"
            event(["type": "blocked", "url": action.request.url?.absoluteString ?? ""])
            decisionHandler(.cancel)
        }
    }
    private func paymentAppTarget(_ url: URL) -> URL? {
        let schemes: Set<String> = ["alipay", "alipays", "weixin", "wxpay", "weixinpay", "tenpay", "uppay", "unionpay"]
        if let scheme = url.scheme?.lowercased(), schemes.contains(scheme) { return url }
        if url.scheme?.lowercased() == "intent" {
            let raw = url.absoluteString
            guard raw.hasPrefix("intent://"), raw.hasSuffix(";end"),
                  let marker = raw.range(of: "#Intent;") else { return nil }
            let options = raw[marker.upperBound..<raw.index(raw.endIndex, offsetBy: -4)]
            let values = options.split(separator: ";").filter { $0.hasPrefix("scheme=") }
            guard values.count == 1,
                  let scheme = values.first.map({ String($0.dropFirst("scheme=".count)).lowercased() }),
                  schemes.contains(scheme) else { return nil }
            let suffixStart = raw.index(raw.startIndex, offsetBy: "intent:".count)
            let suffix = raw[suffixStart..<marker.lowerBound]
            return URL(string: "\(scheme):\(suffix)")
        }
        guard url.scheme?.lowercased() == "https", url.port == nil || url.port == 443,
              url.user == nil, url.password == nil else { return nil }
        if url.host?.lowercased() == "qr.alipay.com" { return url }
        guard let host = url.host?.lowercased(),
              (host == "ds.alipay.com" && (url.path.isEmpty || url.path == "/") ||
               host == "render.alipay.com" && (url.path == "/p/s/i" || url.path == "/p/s/i/")),
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let item = items.first(where: { $0.name == "scheme" }),
              items.filter({ $0.name == "scheme" }).count == 1,
              let raw = item.value, let target = URL(string: raw),
              let scheme = target.scheme?.lowercased(), schemes.contains(scheme),
              target.host != nil, target.user == nil, target.password == nil else { return nil }
        return target
    }

    private func offerPaymentApp(_ url: URL) {
        guard pendingExternal == nil else { return }
        guard let target = paymentAppTarget(url) else {
            status.stringValue = "无法识别安全的支付应用链接，请返回选择网页支付。"
            return
        }
        pendingExternal = target
        let app = ["alipay": "支付宝", "alipays": "支付宝", "weixin": "微信", "wxpay": "微信", "weixinpay": "微信", "tenpay": "微信", "uppay": "云闪付", "unionpay": "云闪付"][target.scheme?.lowercased() ?? ""] ?? "支付应用"
        let alert = NSAlert(); alert.messageText = "使用\(app)继续"
        alert.informativeText = "官方页面请求打开\(app)。确认后将交给已安装的应用处理。"
        alert.addButton(withTitle: "打开\(app)"); alert.addButton(withTitle: "取消")
        presentDialog(alert, for: pages.last ?? root) { [weak self] response in
            guard let self else { return }
            self.pendingExternal = nil
            guard !self.closed else { return }
            if response == .alertFirstButtonReturn && !NSWorkspace.shared.open(target) {
                self.status.stringValue = "未找到可处理此链接的支付应用，请返回选择网页支付。"
            }
        }
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard !closed, action.targetFrame == nil else { return nil }
        let decision = allowed(action.request, rootRequest: false)
        if decision == 2, let url = action.request.url { offerPaymentApp(url); return nil }
        guard decision == 1, pages.count < 16 else { return nil }
        // Returning the supplied configuration's child preserves opener, cookies and POST data.
        let child = WKWebView(frame: host.bounds, configuration: configuration)
        install(child); pages.append(child); display(child); armTimeout()
        event(["type": "window", "depth": pages.count])
        return child
    }
    func webViewDidClose(_ webView: WKWebView) {
        guard !closed, let index = pages.firstIndex(where: { $0 === webView }), index > 0 else { return }
        for page in pages[index...] { dismissDialog(for: page); page.stopLoading(); page.navigationDelegate = nil; page.uiDelegate = nil }
        pages.removeSubrange(index...)
        if let parent = pages.last {
            if isQrResponse(parent.url) {
                coverResponse("支付页面已关闭，请返回充值页；不会重发充值请求。")
            }
            display(parent); loadTimeout?.cancel(); status.stringValue = parent.url?.host ?? "官方支付页面"
            event(["type": "finished", "url": parent.url?.absoluteString ?? "", "root": parent === root, "depth": pages.count, "active": true, "status": responseErrors[ObjectIdentifier(parent)] ?? 200])
            if parent.isLoading { armTimeout() }
        }
    }
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        dismissDialog(for: webView)
        responseErrors.removeValue(forKey: ObjectIdentifier(webView))
        guard webView === pages.last else { return }
        event(["type": "navigating"])
        status.stringValue = "正在加载 \(webView.url?.host ?? "官方支付页面")…"; armTimeout()
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if webView === pages.last { loadTimeout?.cancel() }
        if recoveryEnabled && webView === pages.last && isQrResponse(webView.url) { coverResponse("正在识别支付入口…") }
        if webView === pages.last, let code = responseErrors[ObjectIdentifier(webView)] { status.stringValue = "官方页面返回 HTTP \(code)，请返回重试；未自动重发。" }
        else if webView === pages.last { status.stringValue = webView.url?.host ?? "官方支付页面" }
        event(["type": "finished", "url": webView.url?.absoluteString ?? "", "root": webView === root, "depth": pages.count, "active": webView === pages.last, "embedded": embedded && root.window === window && container.superview === window.contentView, "width": Int(container.bounds.width), "status": responseErrors[ObjectIdentifier(webView)] ?? 200])
    }
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        guard !closed else { decisionHandler(.cancel); return }
        if response.isForMainFrame, let http = response.response as? HTTPURLResponse, http.statusCode >= 400 {
            responseErrors[ObjectIdentifier(webView)] = http.statusCode
            status.stringValue = "官方页面返回 HTTP \(http.statusCode)，请返回重试；未自动重发。"
            event(["type": "error", "reason": "http", "code": http.statusCode])
        }
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { failed(error) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { failed(error) }
    private func failed(_ error: Error) {
        if (error as NSError).code == NSURLErrorCancelled { return }
        loadTimeout?.cancel(); status.stringValue = "官方支付页面加载失败，请返回；不会自动重发充值请求。"
        event(["type": "error", "reason": "navigation", "code": (error as NSError).code])
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        loadTimeout?.cancel(); status.stringValue = "支付页面进程已结束，请返回重新准备；未自动重发。"
        event(["type": "error", "reason": "process"])
    }
    private func presentDialog(_ alert: NSAlert, for view: WKWebView, completion: @escaping (NSApplication.ModalResponse) -> Void) {
        guard !closed, view === pages.last, pendingDialog == nil else { completion(.abort); return }
        var resolved = false
        let finish: (NSApplication.ModalResponse) -> Void = { [weak self] response in
            guard !resolved else { return }
            resolved = true
            self?.pendingDialog = nil
            completion(response)
        }
        pendingDialog = (view, { [weak self] in
            self?.window.endSheet(alert.window, returnCode: .abort)
            finish(.abort)
        })
        alert.beginSheetModal(for: window, completionHandler: finish)
        event(["type": "dialog"])
    }
    private func dismissDialog(for view: WKWebView? = nil) {
        guard let dialog = pendingDialog, view == nil || dialog.view === view else { return }
        dialog.cancel()
    }
    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = NSAlert(); alert.messageText = webView.url?.host ?? "官方支付页面"; alert.informativeText = message
        presentDialog(alert, for: webView) { _ in completionHandler() }
    }
    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = NSAlert(); alert.messageText = webView.url?.host ?? "官方支付页面"; alert.informativeText = message
        alert.addButton(withTitle: "确定"); alert.addButton(withTitle: "取消")
        presentDialog(alert, for: webView) { result in completionHandler(result == .alertFirstButtonReturn) }
    }
    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        let alert = NSAlert(); alert.messageText = webView.url?.host ?? "官方支付页面"; alert.informativeText = prompt
        let field: NSTextField = prompt.contains("密码") ? NSSecureTextField() : NSTextField()
        field.frame = NSRect(x: 0, y: 0, width: 320, height: 26); field.stringValue = defaultText ?? ""
        alert.accessoryView = field; alert.addButton(withTitle: "确定"); alert.addButton(withTitle: "取消")
        presentDialog(alert, for: webView) { response in completionHandler(response == .alertFirstButtonReturn ? field.stringValue : nil) }
    }
    @objc private func retryPressed() { event(["type": "retry"]) }
    @objc private func backPressed() { if !back() { close() } }
    @objc private func closePressed() { close() }
    func back() -> Bool {
        guard !closed, let current = pages.last else { return false }
        if let previous = current.backForwardList.backItem?.url,
           previous.absoluteString != "about:blank", !postHistory.contains(previous),
           !isQrResponse(previous),
           current.canGoBack, allowed(URLRequest(url: previous), rootRequest: false) == 1 {
            current.goBack(); return true
        }
        if pages.count > 1 {
            if isQrResponse(pages[pages.count - 2].url) { return false }
            webViewDidClose(current); return true
        }
        return false
    }
    func navigate(_ url: String, cookies: [[String: Any]]) {
        guard let target = URL(string: url), allowed(URLRequest(url: target), rootRequest: false) == 1 else { return }
        let group = DispatchGroup()
        for cookie in cookies {
            guard let name = cookie["name"] as? String, let value = cookie["value"] as? String,
                  let domain = cookie["domain"] as? String ?? URL(string: cookie["origin"] as? String ?? "")?.host else { continue }
            var properties: [HTTPCookiePropertyKey: Any] = [.name: name, .value: value, .domain: domain, .path: cookie["path"] as? String ?? "/"]
            if cookie["secure"] as? Bool == true { properties[.secure] = "TRUE" }
            if cookie["httpOnly"] as? Bool == true { properties[HTTPCookiePropertyKey("HttpOnly")] = "TRUE" }
            if let native = HTTPCookie(properties: properties) {
                group.enter(); root.configuration.websiteDataStore.httpCookieStore.setCookie(native) { group.leave() }
            }
        }
        group.notify(queue: .main) { [weak self] in
            guard let self, !self.closed else { return }
            let configuration = WKWebViewConfiguration(); configuration.websiteDataStore = self.root.configuration.websiteDataStore
            let child = WKWebView(frame: self.host.bounds, configuration: configuration)
            self.install(child); self.pages.append(child); self.display(child); child.load(URLRequest(url: target)); self.armTimeout()
        }
    }
    func evaluate(_ script: String, token: Int64) {
        guard let current = pages.last else { return }
        current.evaluateJavaScript(script) { [weak self] value, error in
            self?.event(["type": "script", "token": token, "value": value ?? NSNull(), "ok": error == nil])
        }
    }
    func windowWillClose(_ notification: Notification) { close() }
    func close() {
        guard !closed else { return }
        event(["type": "closed"]); closed = true; loadTimeout?.cancel()
        dismissDialog()
        for page in pages { page.stopLoading(); page.navigationDelegate = nil; page.uiDelegate = nil }
        pages.removeAll(); container.removeFromSuperview()
        if !embedded { window.orderOut(nil) }
        DispatchQueue.main.async { [engine, id, engineWindow] in
            webviewDestroy(engine); engineWindow.close()
            if let controller = paymentWindows.removeValue(forKey: id) {
                "{\"type\":\"released\"}".withCString { controller.callback(id, $0) }
            }
        }
    }
}

@_cdecl("bucc_payment_open") public func buccPaymentOpen(_ json: UnsafePointer<CChar>?, _ policy: @escaping BuccPaymentPolicy, _ event: @escaping BuccPaymentEvent) -> Int64 {
    guard let json, let data = String(cString: json).data(using: .utf8), let config = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return 0 }
    return paymentOnMain {
        let id = nextPaymentWindow; nextPaymentWindow += 1
        guard let controller = PaymentBrowserWindow(id: id, config: config, policy: policy, callback: event) else { return 0 }
        paymentWindows[id] = controller; return id
    }
}
@_cdecl("bucc_payment_back") public func buccPaymentBack(_ id: Int64) -> Int32 {
    DispatchQueue.main.async { _ = paymentWindows[id]?.back() }
    return 1
}
@_cdecl("bucc_payment_close") public func buccPaymentClose(_ id: Int64) {
    DispatchQueue.main.async { paymentWindows[id]?.close() }
}
@_cdecl("bucc_payment_show") public func buccPaymentShow(_ id: Int64) {
    DispatchQueue.main.async { paymentWindows[id]?.window.makeKeyAndOrderFront(nil) }
}
@_cdecl("bucc_payment_eval") public func buccPaymentEval(_ id: Int64, _ script: UnsafePointer<CChar>?, _ token: Int64) {
    guard let script else { return }; let value = String(cString: script)
    DispatchQueue.main.async { paymentWindows[id]?.evaluate(value, token: token) }
}
@_cdecl("bucc_payment_navigate") public func buccPaymentNavigate(_ id: Int64, _ json: UnsafePointer<CChar>?) {
    guard let json, let data = String(cString: json).data(using: .utf8), let config = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let url = config["url"] as? String else { return }
    DispatchQueue.main.async { paymentWindows[id]?.navigate(url, cookies: config["cookies"] as? [[String: Any]] ?? []) }
}

@_cdecl("bucc_payment_available") public func buccPaymentAvailable() -> Int32 { 1 }
@_cdecl("bucc_payment_message") public func buccPaymentMessage(_ id: Int64, _ message: UnsafePointer<CChar>?) {
    guard let message else { return }; let value = String(cString: message)
    DispatchQueue.main.async { paymentWindows[id]?.status.stringValue = value }
}

@_cdecl("bucc_payment_bounds") public func buccPaymentBounds(_ id: Int64, _ x: Double, _ y: Double, _ width: Double, _ height: Double) {
    DispatchQueue.main.async { paymentWindows[id]?.setBounds(x: x, y: y, width: width, height: height) }
}

@_cdecl("bucc_payment_cover") public func buccPaymentCover(_ id: Int64, _ message: UnsafePointer<CChar>?) {
    let value = message.map { String(cString: $0) }
    DispatchQueue.main.async { paymentWindows[id]?.coverResponse(value) }
}
@_cdecl("bucc_payment_qr_snapshot") public func buccPaymentQrSnapshot(_ id: Int64, _ source: UnsafePointer<CChar>?, _ token: Int64) {
    guard let source else { return }; let value = String(cString: source)
    DispatchQueue.main.async { paymentWindows[id]?.snapshotQr(value, token: token) }
}
