import ComposeApp
import Foundation
import Network
import Security
import UIKit

/// Installs the native services consumed by the shared TD implementation.
/// Call `TdNetworkBridgeInstaller.install()` before creating MainViewController.
@objc final class TdNetworkBridgeInstaller: NSObject {
    @objc static func install() {
        TdNativeBridgeInstaller.shared.install(bridge: TdNetworkBridge())
    }
}

private final class TdNetworkBridge: NSObject, TdNativePlatformBridge {
    private let keychainService = "app.bucc.td.healthcloud"

    func request(
        host: String,
        port: Int32,
        bytes: KotlinByteArray,
        timeoutMillis: Int64,
        callback: TdNativeNetworkCallback
    ) -> TdNativeCancellable {
        let request = TdNWRequest(
            host: host,
            port: UInt16(exactly: port) ?? 0,
            payload: bytes.asData(),
            timeout: .milliseconds(Int(timeoutMillis)),
            callback: callback
        )
        request.start()
        return request
    }

    func password(studentId: String) -> String? {
        var query = keychainQuery(studentId: studentId)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func savePassword(studentId: String, password: String) {
        let query = keychainQuery(studentId: studentId)
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = Data(password.utf8)
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(attributes as CFDictionary, nil)
    }

    func deletePassword(studentId: String) {
        SecItemDelete(keychainQuery(studentId: studentId) as CFDictionary)
    }

    func pickImage(callback: TdNativeImageCallback) -> TdNativeCancellable {
        let request = TdImagePickerRequest(callback: callback)
        DispatchQueue.main.async { request.start() }
        return request
    }

    private func keychainQuery(studentId: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: studentId,
        ]
    }
}

private final class TdImagePickerRequest: NSObject, TdNativeCancellable,
    UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    private let callback: TdNativeImageCallback
    private let picker = UIImagePickerController()
    private let completionLock = NSLock()
    private var finished = false

    init(callback: TdNativeImageCallback) {
        self.callback = callback
        super.init()
        picker.delegate = self
        picker.sourceType = .photoLibrary
        picker.mediaTypes = ["public.image"]
    }

    func start() {
        guard UIImagePickerController.isSourceTypeAvailable(.photoLibrary) else {
            finish(error: "当前设备无法访问照片图库")
            return
        }
        guard let presenter = Self.topViewController() else {
            finish(error: "无法显示照片选择器")
            return
        }
        presenter.present(picker, animated: true)
    }

    func cancel() {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.markFinished() else { return }
            self.picker.dismiss(animated: true)
        }
    }

    func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        guard let image = info[.originalImage] as? UIImage,
              let data = image.jpegData(compressionQuality: 0.9) else {
            picker.dismiss(animated: true)
            finish(error: "无法读取所选图片")
            return
        }
        picker.dismiss(animated: true)
        guard markFinished() else { return }
        let timestamp = Int(Date().timeIntervalSince1970)
        callback.onSuccess(
            name: "td_\(timestamp).jpg",
            mimeType: "image/jpeg",
            bytes: data.asKotlinByteArray()
        )
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
        guard markFinished() else { return }
        callback.onFailure(message: "")
    }

    private func finish(error: String) {
        guard markFinished() else { return }
        callback.onFailure(message: error)
    }

    private func markFinished() -> Bool {
        completionLock.lock()
        defer { completionLock.unlock() }
        guard !finished else { return false }
        finished = true
        return true
    }

    private static func topViewController() -> UIViewController? {
        let root = UIApplication.shared.windows.first(where: { $0.isKeyWindow })?.rootViewController
        var current = root
        while let presented = current?.presentedViewController {
            current = presented
        }
        if let navigation = current as? UINavigationController {
            return navigation.visibleViewController ?? navigation
        }
        if let tab = current as? UITabBarController {
            return tab.selectedViewController ?? tab
        }
        return current
    }
}

private final class TdNWRequest: NSObject, TdNativeCancellable {
    private static let maximumResponseBytes = 8 * 1024 * 1024

    private let connection: NWConnection
    private let payload: Data
    private let timeout: DispatchTimeInterval
    private let callback: TdNativeNetworkCallback
    private let queue = DispatchQueue(label: "app.bucc.td.network")
    private let completionLock = NSLock()
    private var finished = false

    init(
        host: String,
        port: UInt16,
        payload: Data,
        timeout: DispatchTimeInterval,
        callback: TdNativeNetworkCallback
    ) {
        self.connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port) ?? NWEndpoint.Port(rawValue: 8888)!,
            using: .tcp
        )
        self.payload = payload
        self.timeout = timeout
        self.callback = callback
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.sendRequest()
            case .failed(let error):
                self.finish(error: "无法连接 TD 服务器：\(error.localizedDescription)")
            case .cancelled:
                self.finish(error: "TD 请求已取消")
            default:
                break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + timeout) { [weak self] in
            self?.finish(error: "TD 请求超时，请确认已连接校园网或完整 VPN")
        }
    }

    func cancel() {
        finish(error: "TD 请求已取消")
    }

    private func sendRequest() {
        connection.send(content: payload, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            if let error {
                self.finish(error: "发送 TD 请求失败：\(error.localizedDescription)")
            } else {
                self.receiveExactly(5, accumulated: Data()) { result in
                    switch result {
                    case .failure(let error):
                        self.finish(error: error.localizedDescription)
                    case .success(let header):
                        let length =
                            (Int(header[0]) << 24) |
                            (Int(header[1]) << 16) |
                            (Int(header[2]) << 8) |
                            Int(header[3])
                        guard length > 0, length <= Self.maximumResponseBytes else {
                            self.finish(error: "TD 响应长度无效：\(length)")
                            return
                        }
                        self.receiveExactly(length, accumulated: Data()) { bodyResult in
                            switch bodyResult {
                            case .failure(let error):
                                self.finish(error: error.localizedDescription)
                            case .success(let body):
                                self.finish(response: header + body)
                            }
                        }
                    }
                }
            }
        })
    }

    private func receiveExactly(
        _ count: Int,
        accumulated: Data,
        completion: @escaping (Result<Data, Error>) -> Void
    ) {
        let remaining = count - accumulated.count
        guard remaining > 0 else {
            completion(.success(accumulated))
            return
        }
        connection.receive(
            minimumIncompleteLength: 1,
            maximumLength: remaining
        ) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error {
                completion(.failure(error))
                return
            }
            var next = accumulated
            if let data { next.append(data) }
            if next.count == count {
                completion(.success(next))
            } else if isComplete || data?.isEmpty != false {
                completion(.failure(TdBridgeError("TD 连接在接收完整响应前已关闭")))
            } else {
                self.receiveExactly(count, accumulated: next, completion: completion)
            }
        }
    }

    private func finish(response: Data) {
        guard markFinished() else { return }
        connection.cancel()
        callback.onSuccess(response: response.asKotlinByteArray())
    }

    private func finish(error: String) {
        guard markFinished() else { return }
        connection.cancel()
        callback.onFailure(message: error)
    }

    private func markFinished() -> Bool {
        completionLock.lock()
        defer { completionLock.unlock() }
        guard !finished else { return false }
        finished = true
        return true
    }
}

private struct TdBridgeError: LocalizedError {
    let errorDescription: String?

    init(_ message: String) {
        self.errorDescription = message
    }
}

private extension KotlinByteArray {
    func asData() -> Data {
        var result = Data(count: Int(size))
        result.withUnsafeMutableBytes { rawBuffer in
            guard let destination = rawBuffer.bindMemory(to: UInt8.self).baseAddress else { return }
            for index in 0..<Int(size) {
                destination[index] = UInt8(bitPattern: get(index: Int32(index)))
            }
        }
        return result
    }
}

private extension Data {
    func asKotlinByteArray() -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        withUnsafeBytes { rawBuffer in
            let source = rawBuffer.bindMemory(to: UInt8.self)
            for index in 0..<count {
                result.set(index: Int32(index), value: Int8(bitPattern: source[index]))
            }
        }
        return result
    }
}
