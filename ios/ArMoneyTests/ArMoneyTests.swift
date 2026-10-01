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

final class BankingSafetyTests: XCTestCase {
    func testMoneyUsesExactMinorUnitsAndRejectsAmbiguity() {
        XCTAssertEqual(Money.parse("0.01"), 1)
        XCTAssertEqual(Money.parse("12.5"), 1250)
        XCTAssertEqual(Money.parse("90071992547409.93"), 9_007_199_254_740_993)
        XCTAssertEqual(Money.parse("92233720368547758.07"), Int64.max)
        for input in ["0", "0.00", "-1", "+1", "1,50", "1.001", "1e3", " 1", "1 ", "1\n", "١٢", ".50", "1.", "92233720368547758.08", "92233720368547759", "999999999999999999999"] {
            XCTAssertNil(Money.parse(input), input)
        }
        XCTAssertEqual(Money.format(Int64.max, currency: "EUR"), "92233720368547758.07 EUR")
        XCTAssertEqual(Money.format(Int64.min, currency: "GBP"), "−92233720368547758.08 GBP")
        XCTAssertEqual(Money.format(1, currency: "USD"), "0.01 USD")
    }
    func testPhoneRequiresExactE164() {
        XCTAssertTrue(Money.validPhone("+441234567890"))
        for input in ["441234567890", "+0123456789", "+44 1234567890", "+441234567890\n", "123", "+1234567890123456"] {
            XCTAssertFalse(Money.validPhone(input), input)
        }
    }
    func testLostResponseRecoveryPreservesKeyAndPayloadAcrossSerialization() throws {
        let command = PaymentCommand(sourceWalletId: "source", recipientId: "recipient", recipientPhone: "+441234567890", currency: "EUR", amountMinor: 9_007_199_254_740_993)
        let pending = PendingSubmission(command: command)
        let restored = try JSONDecoder().decode(PendingSubmission.self, from: JSONEncoder().encode(pending))
        XCTAssertEqual(restored, pending)
        XCTAssertNotEqual(PendingSubmission(command: command).key, pending.key)
        let encoder = JSONEncoder(); encoder.keyEncodingStrategy = .convertToSnakeCase
        let json = String(decoding: try encoder.encode(restored.command), as: UTF8.self)
        XCTAssertTrue(json.contains("9007199254740993"))
        XCTAssertTrue(json.contains("source_wallet_id"))
        XCTAssertFalse(json.contains("amountMinor"))
    }
    func testPendingCannotAcceptMismatchedOrUnknownOutcome() {
        let command = PaymentCommand(sourceWalletId: "source", recipientId: "recipient", recipientPhone: "+441234567890", currency: "EUR", amountMinor: 1250)
        let pending = PendingSubmission(command: command)
        func response(amount: Int64 = 1250, status: String = "PENDING", owner: String = "owner") -> Payment {
            Payment(id: "00000000-0000-4000-8000-000000000001", requesterId: owner, recipientId: "recipient", sourceWalletId: "source", destinationWalletId: "00000000-0000-4000-8000-000000000002", recipientPhone: "+441234567890", currency: "EUR", amountMinor: amount, status: status, rejectionReason: nil, createdAt: "date", updatedAt: "date")
        }
        XCTAssertTrue(pending.matches(response(), identity: "owner"))
        XCTAssertTrue(pending.matches(response(status: "COMPLETED"), identity: "owner"))
        XCTAssertTrue(pending.matches(response(status: "REJECTED"), identity: "owner"))
        XCTAssertFalse(pending.matches(response(amount: 1251), identity: "owner"))
        XCTAssertFalse(pending.matches(response(status: "UNKNOWN"), identity: "owner"))
        XCTAssertFalse(pending.matches(response(owner: "other"), identity: "owner"))
    }
    func testPendingKeychainSurvivesSessionClearAndIsolatesOriginAndIdentity() throws {
        let origin = "https://\(UUID().uuidString.lowercased()).test"
        let own = PendingStore(origin: origin, identity: "owner")
        let otherOwner = PendingStore(origin: origin, identity: "other")
        let otherOrigin = PendingStore(origin: origin + ":8443", identity: "owner")
        defer { try? own.clear(); try? otherOwner.clear(); try? otherOrigin.clear() }
        let pending = PendingSubmission(command: PaymentCommand(sourceWalletId: "source", recipientId: "recipient", recipientPhone: "+441234567890", currency: "EUR", amountMinor: 100))
        try own.save(pending)
        XCTAssertNil(try otherOwner.load())
        XCTAssertNil(try otherOrigin.load())
        try SessionStore(origin: origin).clear()
        XCTAssertEqual(try PendingStore(origin: origin, identity: "owner").load(), pending)
        try own.save(pending)
        XCTAssertEqual(try own.load(), pending)
        try own.clear()
        XCTAssertNil(try own.load())
    }
    @MainActor func testPaymentAndNotificationNetworkDecoding() throws {
        let payment = try HTTPClient.decode(Payment.self, data: Data("""
        {"id":"payment","requester_id":"owner","recipient_id":"recipient","source_wallet_id":"source","destination_wallet_id":"destination","recipient_phone":"+441234567890","currency":"EUR","amount_minor":9007199254740993,"status":"PENDING","rejection_reason":null,"created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z"}
        """.utf8))
        XCTAssertEqual(payment.amountMinor, 9_007_199_254_740_993)
        XCTAssertEqual(payment.status, "PENDING")
        let notification = try HTTPClient.decode(BankNotification.self, data: Data("""
        {"id":"notification","payment_id":"payment","type":"PAYMENT_RECEIVED","currency":"EUR","amount_minor":1250,"created_at":"2026-10-01T00:00:00Z","read_at":null}
        """.utf8))
        XCTAssertNil(notification.readAt)
        XCTAssertEqual(notification.paymentId, "payment")
    }
}

final class PaymentRecoveryRegressionTests: XCTestCase {
    @MainActor func testOnlyProvenPreAcceptanceErrorsUnlockExplicitDiscard() {
        for (status, code) in [(400, "invalid_request"), (400, "request_rejected"), (404, "not_found"), (409, "recipient_changed"), (409, "wallet_ineligible")] {
            let error = HTTPClient.paymentError(status: status, data: Data("{\"error\":\"\(code)\"}".utf8))
            XCTAssertTrue(error.permitsDiscardingPayment, "\(status) \(code)")
        }
        for (status, code) in [(409, "idempotency_conflict"), (409, "unknown"), (503, "recipient_changed"), (500, "wallet_ineligible"), (400, "unknown"), (404, "unknown")] {
            let error = HTTPClient.paymentError(status: status, data: Data("{\"error\":\"\(code)\"}".utf8))
            XCTAssertFalse(error.permitsDiscardingPayment, "\(status) \(code)")
        }
        for body in ["", "not json", "{}", "{\"error\":123}", "{\"error\":\"recipient_changed\",\"error\":\"idempotency_conflict\"}", "{\"error\":\"recipient_changed\",\"extra\":true}", String(repeating: " ", count: 4097)] {
            let error = HTTPClient.paymentError(status: 409, data: Data(body.utf8))
            XCTAssertEqual(error, .invalidResponse)
            XCTAssertFalse(error.permitsDiscardingPayment)
        }
        XCTAssertFalse(AppError.http(409).permitsDiscardingPayment)
        XCTAssertFalse(AppError.http(404).permitsDiscardingPayment)
        XCTAssertFalse(AppError.authentication.permitsDiscardingPayment)
    }
    @MainActor func testSubmitReadsStructuredConflictAndKeepsOriginalCommand() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [PaymentRefusalProtocol.self]
        let api = BankAPI(configuration: try Configuration(origin: "https://refusal.test"), http: HTTPClient(session: URLSession(configuration: config)))
        let pending = PendingSubmission(command: PaymentCommand(sourceWalletId: "source", recipientId: "recipient", recipientPhone: "+441234567890", currency: "EUR", amountMinor: 100))
        let original = pending
        for _ in 0..<2 {
            do { _ = try await api.submit(pending, token: "fixture"); XCTFail("A refusal cannot become success") }
            catch {
                XCTAssertEqual(error as? AppError, .response(409, "recipient_changed"))
                XCTAssertTrue((error as? AppError)?.permitsDiscardingPayment == true)
            }
        }
        XCTAssertEqual(pending, original)
    }
    @MainActor func testNullEmailSSOIdentityStillProvidesStorageScope() throws {
        let data = Data("{\"id\":\"00000000-0000-4000-8000-000000000003\",\"email\":null}".utf8)
        let identity = try HTTPClient.decode(Identity.self, data: data)
        XCTAssertNil(identity.email)
        XCTAssertEqual(identity.id, "00000000-0000-4000-8000-000000000003")
        XCTAssertTrue(PendingStore(origin: "https://fixture.test", identity: identity.id).account.hasSuffix(identity.id))
        let legacy = try HTTPClient.decode(Identity.self, data: Data("{\"id\":\"legacy\",\"email\":\"owner@example.test\"}".utf8))
        XCTAssertEqual(legacy.email, "owner@example.test")
    }
}

final class PaymentRefusalProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let valid = request.url?.path == "/v1/payments" && request.httpMethod == "POST" &&
            request.value(forHTTPHeaderField: "Authorization") == "Bearer fixture" &&
            UUID(uuidString: request.value(forHTTPHeaderField: "Idempotency-Key") ?? "") != nil
        let status = valid ? 409 : 400
        let body = valid ? "{\"error\":\"recipient_changed\"}" : "{\"error\":\"invalid_request\"}"
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

final class UncertainAttemptRegressionTests: XCTestCase {
    func testLostOriginalThenKnownRetryRefusalCannotUnlockReplacement() throws {
        let command = PaymentCommand(sourceWalletId: "source", recipientId: "recipient", recipientPhone: "+441234567890", currency: "EUR", amountMinor: 100)
        let first = PendingSubmission(command: command)
        XCTAssertTrue(first.mayDiscardRefusalFromNextAttempt(.response(409, "recipient_changed")))
        // The original POST has been sent but is delayed in backend validation.
        // Its HTTP response is lost; the durable marker was written before sending.
        var sent = first; sent.markAttempted()
        let durable = try JSONEncoder().encode(sent)
        let afterRelaunch = try JSONDecoder().decode(PendingSubmission.self, from: durable)
        XCTAssertTrue(afterRelaunch.attempted)
        for refusal in [AppError.response(404, "not_found"), .response(409, "recipient_changed"), .response(409, "wallet_ineligible"), .response(400, "invalid_request")] {
            // The phone/wallet has changed, so a retry refuses; the earlier request
            // can still commit. This must not allow discarding or a fresh key.
            XCTAssertFalse(afterRelaunch.mayDiscardRefusalFromNextAttempt(refusal))
        }
        XCTAssertEqual(afterRelaunch.key, first.key)
        XCTAssertEqual(afterRelaunch.command, first.command)
        var retried = afterRelaunch; retried.markAttempted()
        XCTAssertEqual(try JSONDecoder().decode(PendingSubmission.self, from: JSONEncoder().encode(retried)), afterRelaunch)
    }
    func testLegacyPendingRecordIsConservativelyUncertain() throws {
        let pending = try JSONDecoder().decode(PendingSubmission.self, from: Data("""
        {"key":"original-reference","command":{"sourceWalletId":"source","recipientId":"recipient","recipientPhone":"+441234567890","currency":"EUR","amountMinor":100}}
        """.utf8))
        XCTAssertTrue(pending.attempted)
        XCTAssertFalse(pending.mayDiscardRefusalFromNextAttempt(.response(409, "wallet_ineligible")))
    }
    func testAttemptMarkerSurvivesKeychainLogoutAndReopen() throws {
        let origin = "https://\(UUID().uuidString.lowercased()).test"
        let store = PendingStore(origin: origin, identity: "owner")
        defer { try? store.clear() }
        var pending = PendingSubmission(command: PaymentCommand(sourceWalletId: "source", recipientId: "recipient", recipientPhone: "+441234567890", currency: "EUR", amountMinor: 100))
        pending.markAttempted(); try store.save(pending)
        try SessionStore(origin: origin).clear()
        let recovered = try XCTUnwrap(PendingStore(origin: origin, identity: "owner").load())
        XCTAssertTrue(recovered.attempted)
        XCTAssertFalse(recovered.mayDiscardRefusalFromNextAttempt(.response(404, "not_found")))
        XCTAssertEqual(recovered.key, pending.key)
    }
}
