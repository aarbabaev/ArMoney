import Foundation

enum AppError: LocalizedError, Equatable {
    case configuration, authentication, invalidResponse, http(Int), response(Int, String), secureStorage
    var errorDescription: String? {
        switch self {
        case .configuration: return "Set your HTTPS bank origin in Config/Local.xcconfig. Replace the .invalid placeholder with your bank server's LAN hostname or IP address."
        case .authentication: return "Your session ended. Please sign in again."
        case .invalidResponse: return "The server returned an unexpected response."
        case .http(let status): return status == 429 ? "Too many requests. Please wait and retry." : "The request failed (HTTP \(status)). Please retry."
        case .response(let status, let code):
            switch (status, code) {
            case (409, "recipient_changed"): return "The recipient has changed. Review the saved transfer before taking further action."
            case (409, "wallet_ineligible"): return "A wallet is not eligible for this transfer. Review the saved transfer before taking further action."
            case (409, "idempotency_conflict"): return "The saved transfer reference conflicts with an existing request. Keep it saved and contact support before sending a replacement."
            default: return "The request could not be confirmed. Keep the saved transfer and review its status before retrying."
            }
        case .secureStorage: return "Secure session storage is unavailable. Unlock your device and retry."
        }
    }
}

extension AppError {
    // Exact endpoint-specific status/code combinations prove pre-acceptance refusal.
    // Unknown codes, malformed bodies, and payload conflicts never unlock a draft.
    var permitsDiscardingPayment: Bool {
        switch self {
        case .response(400, "invalid_request"), .response(400, "request_rejected"),
             .response(404, "not_found"), .response(409, "recipient_changed"),
             .response(409, "wallet_ineligible"): return true
        default: return false
        }
    }
}

struct Configuration {
    let origin: URL
    init(origin: String) throws {
        guard let c = URLComponents(string: origin), c.scheme == "https", let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil,
              c.path.isEmpty || c.path == "/", let url = c.url else { throw AppError.configuration }
        let normalizedHost = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard normalizedHost != "invalid", !normalizedHost.hasSuffix(".invalid") else { throw AppError.configuration }
        self.origin = url
    }
    static func bundled() throws -> Configuration {
        try Configuration(origin: Bundle.main.object(forInfoDictionaryKey: "BankOrigin") as? String ?? "")
    }
    var issuer: URL { origin.appendingPathComponent("sso/realms/armoney") }
    func oidc(_ endpoint: String) -> URL { issuer.appendingPathComponent("protocol/openid-connect/\(endpoint)") }
}

struct BankSession: Codable {
    let accessToken: String
    let tokenType: String
    let expiresIn: Int
    let expiresAt: String
    var expiration: Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: expiresAt) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: expiresAt)
    }
    var isValid: Bool { tokenType == "Bearer" && accessToken.count == 43 && expiresIn == 1800 && (expiration ?? .distantPast) > Date() }
}
struct Profile: Decodable { let id: String; let identityId: String; let displayName: String; let phoneNumber: String?; let phoneVerified: Bool }
struct Wallet: Decodable, Identifiable {
    let id: String
    let ownerId: String
    let currency: String
    let status: String
    let provisioningStatus: String
    let ledgerAccountId: String?
}
struct WalletList: Decodable { let wallets: [Wallet] }

// Never follow API/token redirects: bearer tokens and authorization codes remain on the configured origin.
final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
@MainActor final class HTTPClient {
    private let session: URLSession
    init(session: URLSession? = nil) {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 30
        config.httpCookieStorage = nil
        self.session = session ?? URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    func send(_ request: URLRequest, structuredErrors: Bool = false) async throws -> Data {
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppError.invalidResponse }
        if http.statusCode == 401 { throw AppError.authentication }
        let success = (200..<300).contains(http.statusCode)
        // Error bodies are bounded separately and are never rendered verbatim.
        let limit = success ? 1_048_576 : 4_096
        var data = Data()
        for try await byte in bytes {
            guard data.count < limit else { throw AppError.invalidResponse }
            data.append(byte)
        }
        guard success else {
            if structuredErrors {
                guard http.value(forHTTPHeaderField: "Content-Type")?.split(separator: ";").first?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "application/json" else { throw AppError.invalidResponse }
                throw Self.paymentError(status: http.statusCode, data: data)
            }
            throw AppError.http(http.statusCode)
        }
        return data
    }
    static func paymentError(status: Int, data: Data) -> AppError {
        guard data.count <= 4096, let text = String(data: data, encoding: .utf8),
              text.range(of: #"\A\s*\{\s*"error"\s*:\s*"[a-z_]{1,64}"\s*\}\s*\z"#, options: .regularExpression) != nil,
              let object = try? JSONDecoder().decode([String: String].self, from: data),
              let code = object["error"] else { return .invalidResponse }
        return .response(status, code)
    }
    static func decode<T: Decodable>(_ type: T.Type, data: Data) throws -> T {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(type, from: data)
    }
}
@MainActor struct BankAPI {
    let configuration: Configuration
    let http: HTTPClient
    init(configuration: Configuration, http: HTTPClient = HTTPClient()) { self.configuration = configuration; self.http = http }
    func request(_ path: String, method: String = "GET", token: String? = nil, body: [String: String]? = nil) async throws -> Data {
        var request = URLRequest(url: configuration.origin.appendingPathComponent(path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        return try await http.send(request)
    }
}

struct Identity: Decodable { let id: String; let email: String? }
struct Balance: Decodable { let walletId: String; let currency: String; let balanceMinor: Int64 }
struct Recipient: Decodable { let identityId: String; let displayName: String; let phoneNumber: String }
struct Payment: Codable, Identifiable {
    let id: String; let requesterId: String; let recipientId: String
    let sourceWalletId: String; let destinationWalletId: String; let recipientPhone: String
    let currency: String; let amountMinor: Int64; let status: String; let rejectionReason: String?
    let createdAt: String; let updatedAt: String
}
struct PaymentList: Decodable { let payments: [Payment] }
struct BankNotification: Decodable, Identifiable {
    let id: String; let paymentId: String; let type: String; let currency: String
    let amountMinor: Int64; let createdAt: String; let readAt: String?
}
struct NotificationList: Decodable { let notifications: [BankNotification] }
struct PaymentCommand: Codable, Equatable {
    let sourceWalletId: String; let recipientId: String; let recipientPhone: String
    let currency: String; let amountMinor: Int64
}
struct PendingSubmission: Codable, Equatable {
    let key: String; let command: PaymentCommand
    private(set) var attempted: Bool
    init(command: PaymentCommand) {
        key = UUID().uuidString.lowercased(); self.command = command; attempted = false
    }
    private enum CodingKeys: String, CodingKey { case key, command, attempted }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        key = try values.decode(String.self, forKey: .key)
        command = try values.decode(PaymentCommand.self, forKey: .command)
        // Old records predate attempt tracking and must be treated as uncertain.
        attempted = try values.decodeIfPresent(Bool.self, forKey: .attempted) ?? true
    }
    mutating func markAttempted() { attempted = true }
    // Called on the pre-attempt snapshot. A retry's refusal cannot disprove that
    // an earlier delayed request is still validating and may commit afterwards.
    func mayDiscardRefusalFromNextAttempt(_ error: AppError) -> Bool {
        !attempted && error.permitsDiscardingPayment
    }
    func matches(_ payment: Payment, identity: String) -> Bool {
        UUID(uuidString: payment.id) != nil && UUID(uuidString: payment.destinationWalletId) != nil &&
        payment.requesterId == identity && payment.sourceWalletId == command.sourceWalletId &&
        payment.recipientId == command.recipientId && payment.recipientPhone == command.recipientPhone &&
        payment.currency == command.currency && payment.amountMinor == command.amountMinor &&
        ["PENDING", "COMPLETED", "REJECTED"].contains(payment.status)
    }
}

enum Money {
    // Parse ASCII decimal input without floating point, rounding, or locale ambiguity.
    static func parse(_ text: String) -> Int64? {
        guard text.utf8.allSatisfy({ (48...57).contains($0) || $0 == 46 }), text.range(of: "^[0-9]+(?:\\.[0-9]{1,2})?$", options: .regularExpression) != nil else { return nil }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard let whole = Int64(parts[0]) else { return nil }
        let (base, overflow) = whole.multipliedReportingOverflow(by: 100)
        guard !overflow else { return nil }
        let fraction = parts.count == 2 ? String(parts[1]) : ""
        let cents = Int64(fraction.isEmpty ? "0" : fraction.count == 1 ? fraction + "0" : fraction)!
        let (value, addedOverflow) = base.addingReportingOverflow(cents)
        return !addedOverflow && value > 0 ? value : nil
    }
    static func format(_ value: Int64, currency: String) -> String {
        let magnitude = value.magnitude
        return "\(value < 0 ? "−" : "")\(magnitude / 100).\(String(format: "%02llu", magnitude % 100)) \(currency)"
    }
    static func validPhone(_ value: String) -> Bool {
        value.utf8.allSatisfy({ (48...57).contains($0) || $0 == 43 }) && value.range(of: "^\\+9715[024568][0-9]{7}$", options: .regularExpression) != nil
    }
}

extension BankAPI {
    func submit(_ pending: PendingSubmission, token: String) async throws -> Payment {
        var request = URLRequest(url: configuration.origin.appendingPathComponent("v1/payments"))
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(pending.key, forHTTPHeaderField: "Idempotency-Key")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        request.httpBody = try encoder.encode(pending.command)
        return try HTTPClient.decode(Payment.self, data: await http.send(request, structuredErrors: true))
    }
}
