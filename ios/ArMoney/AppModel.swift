import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var session: BankSession?
    @Published private(set) var identity: Identity?
    @Published private(set) var profile: Profile?
    @Published private(set) var needsProfile = false
    @Published private(set) var wallets: [Wallet] = []
    @Published private(set) var balances: [String: Balance] = [:]
    @Published private(set) var balanceErrors: [String: String] = [:]
    @Published private(set) var payments: [Payment] = []
    @Published private(set) var notifications: [BankNotification] = []
    @Published private(set) var recipient: Recipient?
    @Published private(set) var pending: PendingSubmission?
    @Published private(set) var lastPayment: Payment?
    @Published private(set) var paymentDetails: [String: Payment] = [:]
    @Published private(set) var pendingRefused = false
    @Published private(set) var recoveryReady = false
    @Published private(set) var busy = false
    @Published var message: String?
    private let browser = BrowserLogin()
    private var api: BankAPI?
    private var store: SessionStore?
    private var expiryTask: Task<Void, Never>?
    private var generation = UUID()

    init() {
        do {
            let config = try Configuration.bundled()
            api = BankAPI(configuration: config)
            let store = SessionStore(origin: config.origin.absoluteString)
            self.store = store; session = try store.load(); scheduleExpiry()
        } catch { message = error.localizedDescription }
    }
    private func scheduleExpiry() {
        expiryTask?.cancel()
        guard let expires = session?.expiration else { return }
        let current = generation
        expiryTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(max(0, expires.timeIntervalSinceNow))) } catch { return }
            guard let self, self.generation == current else { return }
            self.clearSession(); self.message = "Your session ended. Sign in again. Unconfirmed transfers are preserved securely."
        }
    }
    private func clearSession() {
        generation = UUID(); expiryTask?.cancel(); expiryTask = nil
        session = nil; identity = nil; profile = nil; wallets = []; balances = [:]; balanceErrors = [:]
        payments = []; notifications = []; recipient = nil; pending = nil; lastPayment = nil; paymentDetails = [:]; pendingRefused = false
        recoveryReady = false; needsProfile = false; busy = false
        do { try store?.clear() } catch { message = error.localizedDescription }
    }
    private func handle(_ error: Error, generation current: UUID) {
        guard generation == current else { return }
        if let appError = error as? AppError, appError == .authentication { clearSession() }
        message = error is URLError ? "Cannot reach ArMoney. Check your connection and retry. Unconfirmed transfers remain saved." : error.localizedDescription
    }
    private func token() throws -> String {
        guard let session, session.isValid else { throw AppError.authentication }
        return session.accessToken
    }
    private func pendingStore() throws -> PendingStore {
        guard let api, let identity else { throw AppError.authentication }
        return PendingStore(origin: api.configuration.origin.absoluteString, identity: identity.id)
    }
    func login() async {
        guard !busy else { return }
        let current = generation; busy = true; message = nil
        defer { if generation == current { busy = false } }
        do {
            guard let api, let store else { throw AppError.configuration }
            let accessToken = try await browser.login(configuration: api.configuration, http: api.http)
            guard generation == current else { return }
            let data = try await api.request("v1/auth/sso", method: "POST", body: ["access_token": accessToken])
            guard generation == current else { return }
            let session = try HTTPClient.decode(BankSession.self, data: data)
            guard session.isValid else { throw AppError.invalidResponse }
            try store.save(session); self.session = session; scheduleExpiry()
            try await load(current)
        } catch { handle(error, generation: current) }
    }
    private func load(_ current: UUID) async throws {
        guard let api else { throw AppError.configuration }
        let bearer = try token()
        let who = try await api.request("v1/auth/me", token: bearer)
        guard generation == current else { return }
        identity = try HTTPClient.decode(Identity.self, data: who)
        recoveryReady = false
        pending = try pendingStore().load(); recoveryReady = true
        do {
            let data = try await api.request("v1/users/me", token: bearer)
            guard generation == current else { return }
            profile = try HTTPClient.decode(Profile.self, data: data); needsProfile = false
        } catch AppError.http(404) {
            guard generation == current else { return }
            profile = nil; needsProfile = true
        }
        let data = try await api.request("v1/wallets", token: bearer)
        guard generation == current else { return }
        wallets = try HTTPClient.decode(WalletList.self, data: data).wallets
        balances = [:]; balanceErrors = [:]
        for wallet in wallets where wallet.currency == "AED" && wallet.status == "ACTIVE" && wallet.provisioningStatus == "READY" {
            do {
                let data = try await api.request("v1/wallets/\(wallet.id)/balance", token: bearer)
                guard generation == current else { return }
                let balance = try HTTPClient.decode(Balance.self, data: data)
                guard balance.walletId == wallet.id, balance.currency == wallet.currency, balance.balanceMinor >= 0 else { throw AppError.invalidResponse }
                balances[wallet.id] = balance
            } catch {
                guard generation == current else { return }
                if error as? AppError == .authentication { throw error }
                balanceErrors[wallet.id] = "Balance unavailable. Refresh to retry."
            }
        }
        let history = try await api.request("v1/payments", token: bearer)
        guard generation == current else { return }
        payments = try HTTPClient.decode(PaymentList.self, data: history).payments
        for payment in payments { paymentDetails[payment.id] = payment }
        if let last = lastPayment, let updated = paymentDetails[last.id] { lastPayment = updated }
        let inbox = try await api.request("v1/notifications", token: bearer)
        guard generation == current else { return }
        notifications = try HTTPClient.decode(NotificationList.self, data: inbox).notifications
    }
    func refresh() async {
        guard !busy, session != nil else { return }
        let current = generation; busy = true; message = nil
        defer { if generation == current { busy = false } }
        do { try await load(current) } catch { handle(error, generation: current) }
    }
    func saveProfile(_ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.unicodeScalars.count <= 100,
              !trimmed.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            message = "Enter a name of 1–100 characters without control characters."; return
        }
        await updateProfile(path: "v1/users/me", body: ["display_name": trimmed])
    }
    func savePhone(_ phone: String) async {
        guard Money.validPhone(phone) else { message = "Use an international number such as +441234567890, without spaces."; return }
        await updateProfile(path: "v1/users/me/phone", body: ["phone_number": phone])
    }
    private func updateProfile(path: String, body: [String: String]) async {
        guard !busy else { return }
        let current = generation; busy = true; message = nil
        defer { if generation == current { busy = false } }
        do {
            guard let api else { throw AppError.configuration }
            _ = try await api.request(path, method: "PUT", token: token(), body: body)
            guard generation == current else { return }
            try await load(current)
        } catch { handle(error, generation: current) }
    }
    func createWallet() async {
        guard !busy else { return }
        let current = generation; busy = true; message = nil
        defer { if generation == current { busy = false } }
        do {
            guard let api else { throw AppError.configuration }
            _ = try await api.request("v1/wallets", method: "POST", token: token(), body: ["currency": "AED"])
            guard generation == current else { return }
            try await load(current)
        } catch { handle(error, generation: current) }
    }
    func resetRecipient() { recipient = nil }
    func resolve(_ phone: String) async {
        guard !busy, pending == nil, recoveryReady else { return }
        recipient = nil
        guard Money.validPhone(phone) else { message = "Enter a complete international phone number, including + and country code."; return }
        let current = generation; busy = true; message = nil
        defer { if generation == current { busy = false } }
        do {
            guard let api else { throw AppError.configuration }
            let data = try await api.request("v1/recipients/resolve", method: "POST", token: token(), body: ["phone_number": phone])
            guard generation == current else { return }
            let result = try HTTPClient.decode(Recipient.self, data: data)
            guard result.phoneNumber == phone, UUID(uuidString: result.identityId) != nil else { throw AppError.invalidResponse }
            guard result.identityId != identity?.id else { message = "Choose someone other than yourself."; return }
            recipient = result
        } catch { handle(error, generation: current) }
    }
    func send(wallet: Wallet, amount: String) async {
        guard !busy, pending == nil, recoveryReady, let recipient, let amountMinor = Money.parse(amount),
              wallet.currency == "AED", wallet.status == "ACTIVE", wallet.provisioningStatus == "READY" else { return }
        let current = generation
        do {
            let command = PaymentCommand(sourceWalletId: wallet.id, recipientId: recipient.identityId,
                recipientPhone: recipient.phoneNumber, currency: wallet.currency, amountMinor: amountMinor)
            let submission = PendingSubmission(command: command)
            // Persistence must succeed before the first network attempt.
            try pendingStore().save(submission); pending = submission; pendingRefused = false
            await retryPending()
        } catch { handle(error, generation: current) }
    }
    func retryPending() async {
        guard !busy, recoveryReady, let submission = pending, let identity else { return }
        let current = generation; busy = true; message = nil; pendingRefused = false
        defer { if generation == current { busy = false } }
        do {
            guard let api else { throw AppError.configuration }
            // Write-ahead marker: a crash at any point after this save is uncertain.
            // Do not send if the marker cannot be persisted securely.
            var attemptedSubmission = submission
            attemptedSubmission.markAttempted()
            try pendingStore().save(attemptedSubmission)
            pending = attemptedSubmission
            let result = try await api.submit(attemptedSubmission, token: token())
            guard generation == current else { return }
            guard submission.matches(result, identity: identity.id) else { throw AppError.invalidResponse }
            // A matching durable response establishes acceptance. History can recover its
            // status by payment ID, including PENDING, without another client command.
            try pendingStore().clear(); pending = nil; pendingRefused = false; recipient = nil; lastPayment = result; paymentDetails[result.id] = result
            message = result.status == "PENDING" ? "Transfer accepted and pending. Refresh history for its confirmed outcome." : result.status == "COMPLETED" ? "Transfer completed." : "Transfer rejected: \(result.rejectionReason ?? "not eligible")."
            try await load(current)
        } catch {
            guard generation == current else { return }
            // Only a first attempt can prove non-acceptance. A known refusal from a
            // retry says nothing about a delayed earlier attempt of the same command.
            if let refusal = error as? AppError, submission.mayDiscardRefusalFromNextAttempt(refusal) { pendingRefused = true }
            handle(error, generation: current)
        }
    }
    func discardRefusedSubmission() {
        guard !busy, pendingRefused else { return }
        do { try pendingStore().clear(); pending = nil; pendingRefused = false; recipient = nil }
        catch { message = error.localizedDescription }
    }
    func loadPayment(_ id: String) async {
        guard !busy else { return }
        let current = generation; busy = true
        defer { if generation == current { busy = false } }
        do {
            guard let api else { throw AppError.configuration }
            let data = try await api.request("v1/payments/\(id)", token: token())
            guard generation == current else { return }
            let result = try HTTPClient.decode(Payment.self, data: data)
            guard result.id == id else { throw AppError.invalidResponse }
            paymentDetails[id] = result
        } catch { handle(error, generation: current) }
    }
    func readNotification(_ item: BankNotification) async {
        guard !busy else { return }
        let current = generation; busy = true; message = nil
        defer { if generation == current { busy = false } }
        do {
            guard let api else { throw AppError.configuration }
            _ = try await api.request("v1/notifications/\(item.id)/read", method: "POST", token: token())
            guard generation == current else { return }
            try await load(current)
        } catch { handle(error, generation: current) }
    }
    func logout() async {
        guard !busy else { return }
        let oldToken = session?.accessToken
        clearSession(); let current = generation; busy = true; message = nil
        defer { if generation == current { busy = false } }
        guard let api else { return }
        var revocationFailed = false
        if let oldToken {
            do { _ = try await api.request("v1/auth/logout", method: "POST", token: oldToken) }
            catch AppError.authentication { }
            catch { revocationFailed = true }
        }
        guard generation == current else { return }
        do {
            try await browser.logout(configuration: api.configuration)
            guard generation == current else { return }
            if revocationFailed { message = "Signed out on this device. Server revocation could not be confirmed; the session expires within 30 minutes." }
        } catch {
            guard generation == current else { return }
            message = "Signed out on this device. Browser sign-out was not completed; browser SSO may still be active."
            if revocationFailed { message! += " Server revocation could not be confirmed; the session expires within 30 minutes." }
        }
    }
}
