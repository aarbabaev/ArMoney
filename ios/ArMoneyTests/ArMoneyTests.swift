import XCTest
@testable import ArMoney

final class ArMoneyTests: XCTestCase {
    func testRFC7636Challenge() {
        XCTAssertEqual(OAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }
    func testRandomValuesAreDistinctAndURLSafe() throws {
        let values = try (0..<32).map { _ in try OAuth.random() }
        XCTAssertEqual(Set(values).count, 32)
        for value in values {
            XCTAssertEqual(value.count, 43)
            XCTAssertNotNil(value.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression))
        }
    }
    func testCallbackRequiresExactRedirectStateAndNoDuplicates() throws {
        let good = URL(string: "com.armoney.ios:/oauth/callback?code=abc&state=expected")!
        XCTAssertEqual(try OAuth.callback(good, expected: OAuth.redirect, state: "expected")["code"], "abc")
        for value in [
            "com.armoney.ios:/oauth/callback?code=abc&state=other",
            "com.armoney.ios:/oauth/callback?code=abc",
            "com.armoney.ios:/oauth/callback?code=abc&state=expected&state=expected",
            "com.armoney.ios:/oauth/callback?code=abc&state=expected&code=def",
            "com.armoney.ios://evil/oauth/callback?state=expected",
            "com.armoney.ios:/oauth/logout?state=expected",
            "com.armoney.ios:/oauth/callback?state=expected#error",
            "com.armoney.ios:/oauth/callback?state=expected&error=access_denied"
        ] { XCTAssertThrowsError(try OAuth.callback(URL(string: value)!, expected: OAuth.redirect, state: "expected"), value) }
    }
    func testConfigurationRejectsUnsafeOrigins() throws {
        XCTAssertEqual(try Configuration(origin: "https://bank.local:8443").issuer.absoluteString, "https://bank.local:8443/sso/realms/armoney")
        for value in ["http://bank.local", "https://user:password@bank.local", "https://bank.local/api", "https://bank.local?x=1", "https://bank.local#x", ""] {
            XCTAssertThrowsError(try Configuration(origin: value))
        }
    }
    func testConfigurationRejectsInvalidPlaceholderBeforeLogin() {
        for origin in [
            "https://bank.example.invalid:8443",
            "https://BANK.EXAMPLE.INVALID:8443",
            "https://bank.example.invalid.:8443",
            "https://Bank.Example.InVaLiD.:8443",
            "https://invalid", "https://INVALID."
        ] {
            XCTAssertThrowsError(try Configuration(origin: origin), origin) { error in
                XCTAssertEqual(error as? AppError, .configuration)
                XCTAssertTrue(error.localizedDescription.contains("Config/Local.xcconfig"))
            }
        }
    }
    func testConfigurationRetainsHTTPSLANOrigins() throws {
        for origin in [
            "https://bank.local:8443", "https://bank.local.:8443",
            "https://bank-host:8443", "https://192.168.1.10:8443",
            "https://[fd00::1]:8443", "https://invalid.bank.local:8443"
        ] {
            XCTAssertEqual(try Configuration(origin: origin).origin.absoluteString, origin)
        }
    }
    @MainActor func testSessionDecodingAndExpiry() throws {
        let valid = try HTTPClient.decode(BankSession.self, data: Data("""
        {"access_token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","token_type":"Bearer","expires_in":1800,"expires_at":"2099-01-01T00:00:00.123Z"}
        """.utf8))
        XCTAssertTrue(valid.isValid)
        let expired = BankSession(accessToken: valid.accessToken, tokenType: "Bearer", expiresIn: 1800, expiresAt: "2020-01-01T00:00:00Z")
        XCTAssertFalse(expired.isValid)
        XCTAssertFalse(BankSession(accessToken: valid.accessToken, tokenType: "Bearer", expiresIn: 1800, expiresAt: "invalid").isValid)
        let roundTrip = try JSONDecoder().decode(BankSession.self, from: JSONEncoder().encode(valid))
        XCTAssertEqual(roundTrip.accessToken, valid.accessToken)
    }
    @MainActor func testPendingAndReadyWalletDecoding() throws {
        let wallets = try HTTPClient.decode(WalletList.self, data: FixtureProtocol.wallets).wallets
        XCTAssertNil(wallets[0].ledgerAccountId)
        XCTAssertEqual(wallets[0].provisioningStatus, "PENDING")
        XCTAssertEqual(wallets[1].provisioningStatus, "READY")
        XCTAssertNotNil(wallets[1].ledgerAccountId)
    }
    @MainActor func testAPIUsesOpaqueBearerAndJSONContract() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [FixtureProtocol.self]
        let api = BankAPI(configuration: try Configuration(origin: "https://fixture.test"), http: HTTPClient(session: URLSession(configuration: config)))
        let data = try await api.request("v1/wallets", token: "opaque-test-session")
        XCTAssertEqual(try HTTPClient.decode(WalletList.self, data: data).wallets.count, 2)
        do {
            _ = try await api.request("unauthorized", token: "expired")
            XCTFail("401 must fail closed")
        } catch { XCTAssertEqual(error as? AppError, .authentication) }
        do {
            _ = try await api.request("unavailable")
            XCTFail("503 must not synthesize success")
        } catch { XCTAssertEqual(error as? AppError, .http(503)) }
        do {
            _ = try await api.request("offline")
            XCTFail("Offline must not synthesize success")
        } catch { XCTAssertTrue(error is URLError) }
    }
    func testFormEscapesCode() {
        XCTAssertEqual(String(data: OAuth.form(["code": "a+b&c=d"]), encoding: .utf8), "code=a%2Bb%26c%3Dd")
    }
}

// Immutable fixtures selected from the request; no shared mutable test handler.
final class FixtureProtocol: URLProtocol {
    static let wallets = Data("""
    {"wallets":[{"id":"one","owner_id":"owner","currency":"EUR","status":"ACTIVE","provisioning_status":"PENDING","ledger_account_id":null},{"id":"two","owner_id":"owner","currency":"USD","status":"ACTIVE","provisioning_status":"READY","ledger_account_id":"ledger"}]}
    """.utf8)
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        if path == "/offline" { client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet)); return }
        let status: Int
        if path == "/v1/wallets" {
            status = request.value(forHTTPHeaderField: "Authorization") == "Bearer opaque-test-session" ? 200 : 401
        } else { status = path == "/unauthorized" ? 401 : 503 }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: status == 200 ? Self.wallets : Data())
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
