import Foundation
import Security

// Only operation name and OSStatus are diagnostic; no query, account, or secret
// is included. XCTest failures retain the status needed to diagnose entitlements.
struct KeychainFailure: LocalizedError {
    let operation: String
    let status: OSStatus
    var errorDescription: String? {
        "Secure storage is unavailable (Keychain \(operation), OSStatus \(status)). Unlock your device and retry."
    }
}

struct SessionStore {
    private var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.armoney.ios.session", kSecAttrAccount as String: origin] }
    let origin: String
    func load() throws -> BankSession? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainFailure(operation: "read", status: status) }
        guard let data = result as? Data else { throw AppError.secureStorage }
        // Stored Codable uses Swift property names; network JSON uses snake_case.
        guard let session = try? JSONDecoder().decode(BankSession.self, from: data), session.isValid else {
            try clear(); return nil
        }
        return session
    }
    func save(_ session: BankSession) throws {
        try clear()
        var request = query
        request[kSecValueData as String] = try JSONEncoder().encode(session)
        request[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let added = SecItemAdd(request as CFDictionary, nil)
        guard added == errSecSuccess else { throw KeychainFailure(operation: "add", status: added) }
    }
    func clear() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainFailure(operation: "delete", status: status) }
    }
}

// A separate Keychain item survives session logout/expiry. Only the same verified
// origin and identity can retrieve an uncertain command; no mutable draft is stored.
struct PendingStore {
    let origin: String
    let identity: String
    var account: String { origin + "|" + identity }
    private var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.armoney.ios.pending-payment", kSecAttrAccount as String: account] }
    func load() throws -> PendingSubmission? {
        var request = query
        request[kSecReturnData as String] = true; request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainFailure(operation: "read", status: status) }
        guard let data = result as? Data,
              let pending = try? JSONDecoder().decode(PendingSubmission.self, from: data) else { throw AppError.secureStorage }
        return pending
    }
    func save(_ pending: PendingSubmission) throws {
        let data = try JSONEncoder().encode(pending)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw KeychainFailure(operation: "update", status: status) }
        var request = query; request[kSecValueData as String] = data
        request[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let added = SecItemAdd(request as CFDictionary, nil)
        guard added == errSecSuccess else { throw KeychainFailure(operation: "add", status: added) }
    }
    func clear() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainFailure(operation: "delete", status: status) }
    }
}
