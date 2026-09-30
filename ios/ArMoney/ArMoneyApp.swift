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
    @State private var name = ""
    @State private var currency = "EUR"
    var body: some View {
        NavigationStack {
            Group {
                if model.session == nil { signIn }
                else { account }
            }
            .navigationTitle("ArMoney")
            .safeAreaInset(edge: .bottom) {
                if let message = model.message {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(message).font(.callout).accessibilityIdentifier("statusMessage")
                        if model.session != nil {
                            Button("Retry") { Task { await model.refresh() } }.disabled(model.busy)
                        }
                    }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.regularMaterial)
                }
            }
            .overlay { if model.busy { ProgressView("Please wait…").padding(24).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16)) } }
        }
    }
    private var signIn: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "building.columns.fill").font(.system(size: 54)).foregroundStyle(.tint).accessibilityHidden(true)
                Text("Your money,\nyour next chapter.").font(.largeTitle.bold())
                Text("Sign in securely to manage your profile and currency wallets.").foregroundStyle(.secondary)
                Button { Task { await model.login() } } label: {
                    Text("Sign in with ArMoney").frame(maxWidth: .infinity).padding(.vertical, 8)
                }.buttonStyle(.borderedProminent).disabled(model.busy).accessibilityIdentifier("signIn")
                Text("Sign-in opens your browser. Existing browser sessions can be reused.").font(.footnote).foregroundStyle(.secondary)
            }.padding(28).frame(maxWidth: 560, alignment: .leading)
        }
    }
    private var account: some View {
        List {
            Section(model.needsProfile ? "Welcome — complete your profile" : "Profile") {
                if let profile = model.profile { Text(profile.displayName).font(.title2.bold()) }
                TextField("Display name", text: $name).textContentType(.name).accessibilityIdentifier("displayName")
                Button(model.needsProfile ? "Create profile" : "Save name") { Task { await model.saveProfile(name) } }
                    .disabled(model.busy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            Section {
                if model.wallets.isEmpty { Text("No wallets yet. Open a currency wallet below.").foregroundStyle(.secondary) }
                ForEach(model.wallets) { wallet in
                    HStack(alignment: .top) {
                        Image(systemName: "wallet.bifold").foregroundStyle(.tint).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 5) {
                            Text(wallet.currency).font(.headline)
                            Text(wallet.status == "CLOSED" ? "Closed" : wallet.provisioningStatus == "READY" ? "Ready" : "Pending — setting up your wallet")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                    }.padding(.vertical, 6)
                }
            } header: { Text("Your wallets") } footer: {
                Text("Ready confirms wallet setup. Balances and transfers are not available in this version. Pull to refresh pending wallets.")
            }
            Section("Open a wallet") {
                Picker("Currency", selection: $currency) {
                    ForEach(["EUR", "USD", "GBP"], id: \.self) { Text($0).tag($0) }
                }
                Button("Open \(currency) wallet") { Task { await model.createWallet(currency) } }.disabled(model.busy)
            }
            Section {
                Button("Sign out", role: .destructive) { Task { await model.logout() } }.disabled(model.busy)
            }
        }
        .refreshable { await model.refresh() }
        .onChange(of: model.profile?.displayName) { _, value in name = value ?? "" }
        .onAppear { name = model.profile?.displayName ?? "" }
    }
}
