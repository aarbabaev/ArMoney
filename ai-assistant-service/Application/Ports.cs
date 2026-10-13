using ArMoney.Assistant.Domain;
namespace ArMoney.Assistant.Application;
public interface IBankReads
{
    Task<IReadOnlyList<string>> WalletIds(string delegation, CancellationToken token);
    Task<Wallet> Balance(string delegation, string walletId, CancellationToken token);
    Task<IReadOnlyList<Transaction>> History(string delegation, CancellationToken token);
}
public interface IPolicyIndex
{
    Task<IReadOnlyList<Chunk>> Search(string query, TurnBudget budget, CancellationToken token);
    Task<bool> Ready(CancellationToken token);
}
public interface IProvider
{
    bool Configured { get; }
    decimal CostLimit { get; }
    Task<Handoff> Plan(string question, TurnBudget budget, CancellationToken token);
    Task<PolicyOutput> Answer(string question, IReadOnlyList<Chunk> chunks, TurnBudget budget, CancellationToken token);
}
