import Foundation

enum AppError: LocalizedError, Equatable {
    case configuration, authentication, invalidResponse, http(Int), secureStorage
    var errorDescription: String? {
        switch self {
        case .configuration: return "Set your HTTPS bank origin in Config/Local.xcconfig. Replace the .invalid placeholder with your bank server's LAN hostname or IP address."
        case .authentication: return "Your session ended. Please sign in again."
        case .invalidResponse: return "The server returned an unexpected response."
        case .http(let status): return status == 429 ? "Too many requests. Please wait and retry." : "The request failed (HTTP \(status)). Please retry."
        case .secureStorage: return "Secure session storage is unavailable. Unlock your device and retry."
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
struct Profile: Decodable { let id: String; let identityId: String; let displayName: String }
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
    func send(_ request: URLRequest) async throws -> Data {
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 { throw AppError.authentication }
            throw AppError.http(http.statusCode)
        }
        var data = Data()
        for try await byte in bytes {
            guard data.count < 1_048_576 else { throw AppError.invalidResponse }
            data.append(byte)
        }
        return data
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
