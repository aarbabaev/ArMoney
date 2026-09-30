import Foundation
import Security

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
        guard status == errSecSuccess, let data = result as? Data else { throw AppError.secureStorage }
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
        guard SecItemAdd(request as CFDictionary, nil) == errSecSuccess else { throw AppError.secureStorage }
    }
    func clear() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw AppError.secureStorage }
    }
}
