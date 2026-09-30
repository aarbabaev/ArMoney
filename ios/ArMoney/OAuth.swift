import AuthenticationServices
import CryptoKit
import Security
import UIKit

enum OAuth {
    static let clientID = "armoney-ios"
    static let redirect = "com.armoney.ios:/oauth/callback"
    static let logoutRedirect = "com.armoney.ios:/oauth/logout"
    static func random() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw AppError.authentication }
        return base64(Data(bytes))
    }
    static func base64(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
    static func challenge(_ verifier: String) -> String { base64(Data(SHA256.hash(data: Data(verifier.utf8)))) }
    static func callback(_ url: URL, expected: String, state: String) throws -> [String: String] {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false), components.fragment == nil else { throw AppError.authentication }
        let items = components.queryItems ?? []
        components.query = nil
        guard components.string == expected else { throw AppError.authentication }
        var values: [String: String] = [:]
        for item in items {
            guard values[item.name] == nil, let value = item.value else { throw AppError.authentication }
            values[item.name] = value
        }
        guard values["state"] == state, values["error"] == nil else { throw AppError.authentication }
        return values
    }
    static func form(_ values: [String: String]) -> Data {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return Data(values.sorted { $0.key < $1.key }.map { "\($0.key.addingPercentEncoding(withAllowedCharacters: allowed)!)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed)!)" }.joined(separator: "&").utf8)
    }
}

@MainActor
final class BrowserLogin: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var browser: ASWebAuthenticationSession?
    private var anchor: ASPresentationAnchor?
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor { anchor! }
    private func browse(_ url: URL) async throws -> URL {
        guard browser == nil,
              let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
                .flatMap({ $0.windows }).first(where: { $0.isKeyWindow }) else { throw AppError.authentication }
        anchor = window
        defer { browser = nil; anchor = nil }
        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "com.armoney.ios") { url, error in
                if let url { continuation.resume(returning: url) }
                else { continuation.resume(throwing: error ?? AppError.authentication) }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            browser = session
            if !session.start() { continuation.resume(throwing: AppError.authentication) }
        }
    }
    func login(configuration: Configuration, http: HTTPClient) async throws -> String {
        let verifier = try OAuth.random(), state = try OAuth.random(), nonce = try OAuth.random()
        var url = URLComponents(url: configuration.oidc("auth"), resolvingAgainstBaseURL: false)!
        url.queryItems = ["client_id": OAuth.clientID, "redirect_uri": OAuth.redirect, "response_type": "code",
            "scope": "openid profile email", "state": state, "nonce": nonce,
            "code_challenge": OAuth.challenge(verifier), "code_challenge_method": "S256"].map { URLQueryItem(name: $0.key, value: $0.value) }
        let values = try OAuth.callback(try await browse(url.url!), expected: OAuth.redirect, state: state)
        guard let code = values["code"], !code.isEmpty else { throw AppError.authentication }
        if let issuer = values["iss"], issuer != configuration.issuer.absoluteString { throw AppError.authentication }
        var request = URLRequest(url: configuration.oidc("token"))
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = OAuth.form(["grant_type": "authorization_code", "client_id": OAuth.clientID,
            "redirect_uri": OAuth.redirect, "code": code, "code_verifier": verifier])
        struct Tokens: Decodable { let accessToken: String; let tokenType: String }
        let tokens = try HTTPClient.decode(Tokens.self, data: try await http.send(request))
        guard tokens.tokenType.lowercased() == "bearer", !tokens.accessToken.isEmpty, tokens.accessToken.count < 65_536 else { throw AppError.authentication }
        // ID/refresh tokens are deliberately neither consumed nor persisted. The bank verifies the access token.
        return tokens.accessToken
    }
    func logout(configuration: Configuration) async throws {
        let state = try OAuth.random()
        var url = URLComponents(url: configuration.oidc("logout"), resolvingAgainstBaseURL: false)!
        url.queryItems = ["client_id": OAuth.clientID, "post_logout_redirect_uri": OAuth.logoutRedirect, "state": state].map { URLQueryItem(name: $0.key, value: $0.value) }
        _ = try OAuth.callback(try await browse(url.url!), expected: OAuth.logoutRedirect, state: state)
    }
}
