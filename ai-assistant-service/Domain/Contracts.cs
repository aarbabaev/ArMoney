namespace ArMoney.Assistant.Domain;

public sealed record Wallet(string WalletId, string Currency, long BalanceMinor);
public sealed record Transaction(string Id, DateTimeOffset CreatedAt, string Currency, long AmountMinor, string Direction, string Status);
public sealed record Citation(string DocumentId, string Version, string Section, string Excerpt);
public sealed record Provenance(string Source, DateTimeOffset FetchedAt, string Scope);
public sealed record Facts(IReadOnlyList<Wallet>? Wallets, Transaction? LastTransaction);
public sealed record Answer(string Reply, string Outcome, IReadOnlyList<Citation> Citations, Facts Facts, IReadOnlyList<Provenance> Provenance);
public sealed record Chunk(string DocumentId, string Version, string Section, string Text, string Provenance);
public enum Specialist { Policy, Balances, LastTransaction, Unsupported }
public enum PolicyIntent { CurrencySupport, Conversion, FilsRatio, BalanceAuthority, PaymentCompletion, ConcurrentSpending, SenderVisibility, RecipientVisibility, HistoryOrder, ConversationStorage, ProviderRetention, UnsupportedPolicy }
public sealed record Handoff(Specialist Specialist, string Query, PolicyIntent Intent = PolicyIntent.UnsupportedPolicy);
public sealed record PolicyOutput(string Reply, string Outcome, string[] CitationIds, string? Refine);
public sealed class UnavailableException : Exception { }
public sealed class UnauthorizedException : Exception { }
public sealed class InvalidInputException : Exception { }
public sealed class BudgetException : Exception { }
