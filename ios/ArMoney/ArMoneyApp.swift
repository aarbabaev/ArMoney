import SwiftUI

@main
struct ArMoneyApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .tint(Color(red: 0.08, green: 0.42, blue: 0.32))
                .task { await model.refresh() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { Task { await model.refresh() } }
                }
        }
    }
}

struct ContentView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        Group {
            if model.session == nil { NavigationStack { signIn.navigationTitle("ArMoney") } }
            else {
                TabView {
                    NavigationStack { WalletsView(model: model) }.tabItem { Label("Wallets", systemImage: "wallet.bifold.fill") }
                    NavigationStack { TransfersView(model: model) }.tabItem { Label("Transfers", systemImage: "arrow.left.arrow.right") }
                    NavigationStack { NotificationsView(model: model) }.tabItem { Label("Notifications", systemImage: "bell.fill") }
                        .badge(model.notifications.filter { $0.readAt == nil }.count)
                    NavigationStack { ProfileView(model: model) }.tabItem { Label("Profile", systemImage: "person.crop.circle") }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if let message = model.message {
                HStack(alignment: .top) {
                    Image(systemName: "info.circle")
                    Text(message).font(.footnote).accessibilityIdentifier("statusMessage")
                    Spacer(minLength: 0)
                    Button { model.message = nil } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("Dismiss message")
                }.padding().background(.regularMaterial)
            }
        }
        .overlay(alignment: .top) {
            if model.busy { ProgressView("Updating…").padding(12).background(.regularMaterial, in: Capsule()).padding(.top, 8).allowsHitTesting(false) }
        }
    }
    private var signIn: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "building.columns.fill").font(.system(size: 54)).foregroundStyle(.tint).accessibilityHidden(true)
                Text("Your money,\nyour next chapter.").font(.largeTitle.bold())
                Text("Your wallets. Your people. One place to stay in control.").foregroundStyle(.secondary)
                Button { Task { await model.login() } } label: {
                    Text("Sign in with ArMoney").frame(maxWidth: .infinity).padding(.vertical, 8)
                }.buttonStyle(.borderedProminent).disabled(model.busy).accessibilityIdentifier("signIn")
                Text("Secure sign-in opens your browser. Unconfirmed transfers are kept safely on this device for your next session.").font(.footnote).foregroundStyle(.secondary)
            }.padding(28).frame(maxWidth: 560, alignment: .leading)
        }
    }
}

struct WalletsView: View {
    @ObservedObject var model: AppModel
    @State private var currency = "EUR"
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(model.profile.map { "Hello, \($0.displayName)" } ?? "Welcome to ArMoney").font(.title2.bold())
                    Text("A clear view of each currency.").foregroundStyle(.secondary)
                }.padding(.vertical, 12)
            }
            Section("Your wallets") {
                if model.wallets.isEmpty { ContentUnavailableView("No wallets yet", systemImage: "wallet.bifold", description: Text("Open your first currency wallet below.")) }
                ForEach(model.wallets) { wallet in
                    VStack(alignment: .leading, spacing: 10) {
                        Label(wallet.currency, systemImage: "wallet.bifold.fill").font(.headline).foregroundStyle(.tint)
                        if let balance = model.balances[wallet.id] {
                            Text(Money.format(balance.balanceMinor, currency: wallet.currency)).font(.largeTitle.bold()).monospacedDigit().minimumScaleFactor(0.7)
                        } else {
                            Text(model.balanceErrors[wallet.id] ?? (wallet.status == "CLOSED" ? "Wallet closed" : wallet.provisioningStatus == "READY" ? "Balance not loaded" : "Setting up your wallet…"))
                                .foregroundStyle(.secondary)
                        }
                        Text(wallet.status == "CLOSED" ? "CLOSED" : wallet.provisioningStatus).font(.caption.weight(.semibold))
                    }.padding(.vertical, 12).accessibilityElement(children: .combine)
                }
            }
            Section("Open a currency wallet") {
                Picker("Currency", selection: $currency) { ForEach(["EUR", "USD", "GBP"], id: \.self) { Text($0).tag($0) } }
                Button("Open \(currency) wallet") { Task { await model.createWallet(currency) } }.disabled(model.busy)
            }
            Section { Text("Balances come from the ledger. An unavailable balance is never shown as zero.").font(.footnote).foregroundStyle(.secondary) }
        }
        .navigationTitle("Wallets").refreshable { await model.refresh() }
        .toolbar { Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("Refresh wallets").disabled(model.busy) }
    }
}

struct TransfersView: View {
    @ObservedObject var model: AppModel
    @State private var phone = ""
    @State private var walletID = ""
    @State private var amount = ""
    @State private var confirm = false
    private var eligible: [Wallet] { model.wallets.filter { $0.status == "ACTIVE" && $0.provisioningStatus == "READY" } }
    private var selected: Wallet? { eligible.first { $0.id == walletID } }
    var body: some View {
        List {
            if let pending = model.pending {
                Section("Transfer needs confirmation") {
                    Label("The result is not known yet", systemImage: "clock.arrow.circlepath").font(.headline)
                    Text(Money.format(pending.command.amountMinor, currency: pending.command.currency))
                    Text("To \(pending.command.recipientPhone)")
                    Text("Retry checks the original transfer using its saved reference. Do not send a replacement transfer.").font(.footnote).foregroundStyle(.secondary)
                    Button("Retry original transfer") { Task { await model.retryPending() } }.disabled(model.busy)
                    if model.pendingRefused {
                        Text("The server refused this request before accepting a payment. You can discard it and start again.").font(.footnote)
                        Button("Discard refused request", role: .destructive) { model.discardRefusedSubmission() }.disabled(model.busy)
                    }
                }
            } else {
                Section {
                    TextField("+441234567890", text: $phone).keyboardType(.phonePad).textContentType(.telephoneNumber).disabled(model.busy)
                        .onChange(of: phone) { _, _ in model.resetRecipient() }
                    Button("Find recipient") { Task { await model.resolve(phone) } }.disabled(model.busy || !model.recoveryReady || !Money.validPhone(phone))
                    if let recipient = model.recipient {
                        Label(recipient.displayName, systemImage: "person.crop.circle.badge.checkmark").font(.headline)
                        Text(recipient.phoneNumber).foregroundStyle(.secondary)
                        Picker("From", selection: $walletID) {
                            Text("Choose a wallet").tag("")
                            ForEach(eligible) { Text($0.currency).tag($0.id) }
                        }.disabled(model.busy)
                        TextField("Amount, e.g. 12.50", text: $amount).keyboardType(.decimalPad).disabled(model.busy)
                        if !amount.isEmpty && Money.parse(amount) == nil { Text("Enter a positive amount with at most two decimal digits, using a dot.").font(.footnote).foregroundStyle(.red) }
                        Button("Review transfer") { confirm = true }.buttonStyle(.borderedProminent)
                            .disabled(model.busy || selected == nil || Money.parse(amount) == nil)
                    }
                } header: { Text("Send to a phone number") } footer: { Text("Only verified phone numbers can receive transfers. The recipient needs a ready wallet in the same currency.") }
            }
            if let last = model.lastPayment {
                Section("Latest submission") { NavigationLink { PaymentDetail(model: model, original: last) } label: { PaymentRow(payment: last, identityID: model.identity?.id) } }
            }
            Section {
                if model.payments.isEmpty { Text("No recent transfers.").foregroundStyle(.secondary) }
                ForEach(model.payments) { payment in
                    NavigationLink { PaymentDetail(model: model, original: payment) } label: { PaymentRow(payment: payment, identityID: model.identity?.id) }
                }
            } header: { Text("Recent transfers") } footer: { Text("Up to 100 recent transfers. Incoming transfers appear after completion. Pull to refresh pending outcomes.") }
        }
        .navigationTitle("Transfers").refreshable { await model.refresh() }
        .toolbar { Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("Refresh transfers").disabled(model.busy) }
        .confirmationDialog("Confirm transfer", isPresented: $confirm, titleVisibility: .visible) {
            if let wallet = selected, let recipient = model.recipient, let minor = Money.parse(amount) {
                Button("Send \(Money.format(minor, currency: wallet.currency)) to \(recipient.displayName)") {
                    Task { await model.send(wallet: wallet, amount: amount) }
                }
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Check \(model.recipient?.displayName ?? "") · \(model.recipient?.phoneNumber ?? ""). The transfer is complete only when its status is COMPLETED.")
        }
    }
}

struct PaymentRow: View {
    let payment: Payment
    let identityID: String?
    private var incoming: Bool { payment.recipientId == identityID }
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: incoming ? "arrow.down.left.circle.fill" : "arrow.up.right.circle.fill").font(.title2).foregroundStyle(.tint).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(incoming ? "Received" : "To \(payment.recipientPhone)").font(.headline)
                Text(Money.format(payment.amountMinor, currency: payment.currency)).monospacedDigit()
                Text(payment.status).font(.caption.weight(.semibold)).foregroundStyle(payment.status == "REJECTED" ? Color.red : Color.secondary)
            }
        }.padding(.vertical, 5).accessibilityElement(children: .combine)
    }
}
struct PaymentDetail: View {
    @ObservedObject var model: AppModel
    let original: Payment
    private var payment: Payment { model.paymentDetails[original.id] ?? original }
    var body: some View {
        List {
            Section { PaymentRow(payment: payment, identityID: model.identity?.id) }
            Section("Status") {
                Text(payment.status == "COMPLETED" ? "Confirmed by the ledger." : payment.status == "REJECTED" ? "No transfer was completed." : "Pending confirmation. Return to history and refresh for the latest status.")
                if let reason = payment.rejectionReason { Text(reason).foregroundStyle(.secondary) }
            }
            Section("Details") {
                LabeledContent("Created", value: payment.createdAt)
                LabeledContent("Updated", value: payment.updatedAt)
                Text("Reference").font(.caption).foregroundStyle(.secondary)
                Text(payment.id).font(.footnote.monospaced()).textSelection(.enabled)
            }
        }.navigationTitle("Transfer details")
        .task { await model.loadPayment(original.id) }
        .refreshable { await model.loadPayment(original.id) }
    }
}

struct NotificationsView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        List {
            if model.notifications.isEmpty { ContentUnavailableView("You're all caught up", systemImage: "bell", description: Text("Transfer updates appear here. Refresh to check for new activity.")) }
            ForEach(model.notifications) { item in
                VStack(alignment: .leading, spacing: 8) {
                    Label(item.type == "PAYMENT_RECEIVED" ? "Money received" : item.type == "PAYMENT_COMPLETED" ? "Transfer completed" : item.type == "PAYMENT_REJECTED" ? "Transfer rejected" : "Transfer update", systemImage: item.readAt == nil ? "circle.fill" : "checkmark.circle")
                        .font(.headline)
                    Text(Money.format(item.amountMinor, currency: item.currency)).monospacedDigit()
                    Text(item.createdAt).font(.caption).foregroundStyle(.secondary)
                    if let payment = model.payments.first(where: { $0.id == item.paymentId }) {
                        NavigationLink("View transfer") { PaymentDetail(model: model, original: payment) }
                    }
                    if item.readAt == nil { Button("Mark as read") { Task { await model.readNotification(item) } }.disabled(model.busy) }
                }.padding(.vertical, 8)
            }
            Section { Text("In-app updates refresh when you open ArMoney, return to it, or refresh manually. Device push notifications are not enabled.").font(.footnote).foregroundStyle(.secondary) }
        }.navigationTitle("Notifications").refreshable { await model.refresh() }
        .toolbar { Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel("Refresh notifications").disabled(model.busy) }
    }
}

struct ProfileView: View {
    @ObservedObject var model: AppModel
    @State private var name = ""
    @State private var phone = ""
    var body: some View {
        Form {
            Section(model.needsProfile ? "Complete your profile" : "Your profile") {
                TextField("Display name", text: $name).textContentType(.name).accessibilityIdentifier("displayName")
                Button(model.needsProfile ? "Create profile" : "Save name") { Task { await model.saveProfile(name) } }.disabled(model.busy || name.isEmpty)
            }
            Section {
                TextField("International phone number", text: $phone).keyboardType(.phonePad).textContentType(.telephoneNumber)
                if let profile = model.profile {
                    Label(profile.phoneVerified ? "Verified" : "Not verified", systemImage: profile.phoneVerified ? "checkmark.seal.fill" : "exclamationmark.circle")
                    if let number = profile.phoneNumber { Text("Saved: \(number)").font(.footnote).foregroundStyle(.secondary) }
                }
                Button("Save phone number") { Task { await model.savePhone(phone) } }.disabled(model.busy || !Money.validPhone(phone) || model.profile == nil)
            } header: { Text("Receive money by phone") } footer: {
                Text("Saving does not verify ownership. An operator must verify your number after checking it outside the app. No SMS is sent. Changing your number removes its verified status.")
            }
            Section("Account") {
                if let identity = model.identity {
                    LabeledContent("Email", value: identity.email ?? "Not provided by your sign-in provider")
                    Text(identity.id).font(.footnote.monospaced()).textSelection(.enabled)
                }
                Button("Sign out", role: .destructive) { Task { await model.logout() } }.disabled(model.busy)
                Text("Any unconfirmed transfer stays protected on this device and can be retried after signing into the same account.").font(.footnote).foregroundStyle(.secondary)
            }
        }.navigationTitle("Profile").refreshable { await model.refresh() }
        .onAppear { name = model.profile?.displayName ?? ""; phone = model.profile?.phoneNumber ?? "" }
        .onChange(of: model.profile?.displayName) { _, value in name = value ?? "" }
        .onChange(of: model.profile?.phoneNumber) { _, value in phone = value ?? "" }
    }
}
