import Foundation
import Security

private let campusCredentialService = "app.bucc.campus"

@_cdecl("campus_keychain_save")
public func campusKeychainSave(
    _ usernamePointer: UnsafePointer<CChar>?,
    _ passwordPointer: UnsafePointer<CChar>?
) -> Int32 {
    guard let usernamePointer, let passwordPointer else { return -1 }
    let username = String(cString: usernamePointer)
    let password = String(cString: passwordPointer)
    guard let passwordData = password.data(using: .utf8) else { return -1 }

    let identity: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: campusCredentialService,
        kSecAttrAccount as String: username,
    ]
    let attributes: [String: Any] = [
        kSecValueData as String: passwordData,
        kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    ]
    var record = identity
    attributes.forEach { record[$0.key] = $0.value }

    let status = SecItemAdd(record as CFDictionary, nil)
    if status == errSecDuplicateItem {
        let updateStatus = SecItemUpdate(identity as CFDictionary, attributes as CFDictionary)
        return updateStatus == errSecSuccess ? 0 : -1
    }
    return status == errSecSuccess ? 0 : -1
}

@_cdecl("campus_keychain_load")
public func campusKeychainLoad(
    _ usernamePointer: UnsafePointer<CChar>?,
    _ outputPassword: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    guard let usernamePointer, let outputPassword else { return -1 }
    outputPassword.pointee = nil
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: campusCredentialService,
        kSecAttrAccount as String: String(cString: usernamePointer),
        kSecReturnData as String: true,
        kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var result: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    if status == errSecItemNotFound { return 1 }
    guard status == errSecSuccess,
          let data = result as? Data,
          let password = String(data: data, encoding: .utf8),
          let copied = strdup(password) else { return -1 }
    outputPassword.pointee = copied
    return 0
}

@_cdecl("campus_keychain_delete")
public func campusKeychainDelete(_ usernamePointer: UnsafePointer<CChar>?) -> Int32 {
    guard let usernamePointer else { return -1 }
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: campusCredentialService,
        kSecAttrAccount as String: String(cString: usernamePointer),
    ]
    let status = SecItemDelete(query as CFDictionary)
    if status == errSecItemNotFound { return 1 }
    return status == errSecSuccess ? 0 : -1
}

@_cdecl("campus_keychain_free")
public func campusKeychainFree(_ pointer: UnsafeMutablePointer<CChar>?) {
    free(pointer)
}
