import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var session: BankSession?
    @Published private(set) var profile: Profile?
    @Published private(set) var needsProfile = false
    @Published private(set) var wallets: [Wallet] = []
    @Published private(set) var busy = false
    @Published var message: String?
    private let browser = BrowserLogin()
    private var api: BankAPI?
    private var store: SessionStore?
    private var expiryTask: Task<Void, Never>?

    init() {
        do {
            let config = try Configuration.bundled()
            api = BankAPI(configuration: config)
            let store = SessionStore(origin: config.origin.absoluteString)
            self.store = store
            session = try store.load()
            scheduleExpiry()
        } catch { message = error.localizedDescription }
    }
    private func scheduleExpiry() {
        expiryTask?.cancel()
        guard let expires = session?.expiration else { return }
        let seconds = max(0, expires.timeIntervalSinceNow)
        expiryTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(seconds)) } catch { return }
            self?.clearSession()
            self?.message = "Your session ended. Sign in again to continue."
        }
    }
    private func clearSession() {
        expiryTask?.cancel(); expiryTask = nil
        session = nil; profile = nil; wallets = []; needsProfile = false
        do { try store?.clear() } catch { message = error.localizedDescription }
    }
    private func handle(_ error: Error) {
        if let appError = error as? AppError, appError == .authentication { clearSession() }
        if (error as? URLError) != nil { message = "Cannot reach ArMoney. Check your connection and retry." }
        else { message = error.localizedDescription }
    }
    private func token() throws -> String {
        guard let session, session.isValid else { throw AppError.authentication }
        return session.accessToken
    }
    func login() async {
        guard !busy else { return }
        busy = true; message = nil
        defer { busy = false }
        do {
            guard let api, let store else { throw AppError.configuration }
            let accessToken = try await browser.login(configuration: api.configuration, http: api.http)
            let result = try await api.request("v1/auth/sso", method: "POST", body: ["access_token": accessToken])
            let session = try HTTPClient.decode(BankSession.self, data: result)
            guard session.isValid else { throw AppError.invalidResponse }
            try store.save(session)
            self.session = session
            scheduleExpiry()
            try await load()
        } catch { handle(error) }
    }
    private func load() async throws {
        guard let api else { throw AppError.configuration }
        let currentToken = try token()
        do {
            let data = try await api.request("v1/users/me", token: currentToken)
            guard session?.accessToken == currentToken else { return }
            profile = try HTTPClient.decode(Profile.self, data: data); needsProfile = false
        } catch AppError.http(404) {
            guard session?.accessToken == currentToken else { return }
            profile = nil; needsProfile = true
        }
        let data = try await api.request("v1/wallets", token: currentToken)
        guard session?.accessToken == currentToken else { return }
        wallets = try HTTPClient.decode(WalletList.self, data: data).wallets
    }
    func refresh() async {
        guard !busy, session != nil else { return }
        busy = true; message = nil
        defer { busy = false }
        do { try await load() } catch { handle(error) }
    }
    func saveProfile(_ name: String) async {
        guard !busy else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.unicodeScalars.count <= 100,
              !trimmed.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            message = "Enter a name of 1–100 characters without control characters."; return
        }
        busy = true; message = nil
        defer { busy = false }
        do {
            guard let api else { throw AppError.configuration }
            let currentToken = try token()
            let data = try await api.request("v1/users/me", method: "PUT", token: currentToken, body: ["display_name": trimmed])
            guard session?.accessToken == currentToken else { return }
            profile = try HTTPClient.decode(Profile.self, data: data); needsProfile = false
        } catch { handle(error) }
    }
    func createWallet(_ currency: String) async {
        guard !busy else { return }
        busy = true; message = nil
        defer { busy = false }
        do {
            guard let api else { throw AppError.configuration }
            _ = try await api.request("v1/wallets", method: "POST", token: token(), body: ["currency": currency])
            try await load()
        } catch { handle(error) }
    }
    func logout() async {
        guard !busy else { return }
        busy = true; message = nil
        defer { busy = false }
        let oldToken = session?.accessToken
        clearSession()
        guard let api else { return }
        var revocationFailed = false
        if let oldToken {
            do { _ = try await api.request("v1/auth/logout", method: "POST", token: oldToken) }
            catch AppError.authentication { /* Already invalid. */ }
            catch { revocationFailed = true }
        }
        do {
            try await browser.logout(configuration: api.configuration)
            if revocationFailed { message = "Signed out on this device. Server session revocation could not be confirmed; it expires within 30 minutes." }
        } catch {
            message = "Signed out on this device. Browser sign-out was not completed; browser SSO may still be active."
            if revocationFailed { message! += " Server session revocation could not be confirmed; it expires within 30 minutes." }
        }
    }
}
