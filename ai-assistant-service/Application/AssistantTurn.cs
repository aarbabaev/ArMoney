using System.Text;
using ArMoney.Assistant.Domain;
namespace ArMoney.Assistant.Application;

public sealed class TurnBudget(decimal costLimit = 0.02m)
{
    private int steps, refinements;
    private decimal reservedCost;
    private int usedOutput;
    public int RemainingOutput => 1000-usedOutput;
    public void Step() { if (++steps > 4) throw new BudgetException(); }
    public void Refine() { if (++refinements > 2) throw new BudgetException(); }
    public void Reserve(decimal upperCost) { if (upperCost <=0 || costLimit<=0 || reservedCost+upperCost>costLimit) throw new BudgetException(); reservedCost+=upperCost; }
    public void Output(int tokens) { if(tokens<0 || tokens>RemainingOutput) throw new BudgetException(); usedOutput+=tokens; }
}

public sealed class AssistantTurn(IBankReads bank, IPolicyIndex index, IProvider provider)
{
    public static void ValidateMessage(string message)
    {
        if (string.IsNullOrWhiteSpace(message) || message.EnumerateRunes().Count() > 2000 || Encoding.UTF8.GetByteCount(message) > 4096)
            throw new InvalidInputException();
    }
    // Routing is a constrained application decision. Documents and model output cannot add tools.
    public static Handoff Route(string message)
    {
        var q = message.ToLowerInvariant();
        if (new[] { "transfer money", "send money", "execute", "shell", "http://", "https://", "select *", "investment" }.Any(q.Contains))
            return new(Specialist.Unsupported, message);
        if (new[] { "balance", "баланс" }.Any(q.Contains)) return new(Specialist.Balances, message);
        if (new[] { "last transaction", "latest transaction", "последн", "history" }.Any(q.Contains)) return new(Specialist.LastTransaction, message);
        return new(Specialist.Policy, message);
    }
    public async Task<Answer> Execute(string message, string delegation, CancellationToken token)
    {
        ValidateMessage(message);
        var budget = new TurnBudget(provider.CostLimit); budget.Step();
        var ids = await bank.WalletIds(delegation, token); // authenticate even a policy-only turn
        if (ids.Count > 10 || ids.Distinct().Count() != ids.Count) throw new UnavailableException();
        var handoff = await provider.Plan(message,budget,token);
        if(!Enum.IsDefined(handoff.Specialist) || !Enum.IsDefined(handoff.Intent)) throw new UnavailableException();
        Answer answer;
        if (handoff.Specialist == Specialist.Balances)
        {
            if(ids.Count>6) throw new UnavailableException();
            var wallets = new List<Wallet>();
            foreach (var id in ids) wallets.Add(await bank.Balance(delegation, id, token));
            var reply = wallets.Count == 0 ? "No wallets are available." : string.Join("\n", wallets.Select(w => $"Wallet {w.WalletId}: {Format(w.BalanceMinor)} AED."));
            answer = new(reply, "answered", [], new(wallets, null), [new("wallet-service", DateTimeOffset.UtcNow, "authenticated owner's wallets")]);
        }
        else if (handoff.Specialist == Specialist.LastTransaction)
        {
            if(UnsupportedHistoryFilter(message))
                answer=Insufficient("I can show only the most recent visible transaction by creation time, regardless of completion. Completed-only, date-range and wallet-filtered searches are not supported.");
            else
            {
                var items = await bank.History(delegation, token);
                var last = items.OrderByDescending(x => x.CreatedAt).ThenByDescending(x => x.Id, StringComparer.Ordinal).FirstOrDefault();
                answer = last is null
                    ? new("No visible transactions were found.", "empty_history", [], new(null, null), [new("payment-service", DateTimeOffset.UtcNow, "authenticated owner's visible history")])
                    : new($"Most recent visible transaction by creation time (regardless of completion): {Format(last.AmountMinor)} AED, {last.Direction}, {last.Status}, created {last.CreatedAt:O}.", "answered", [], new(null, last), [new("payment-service", DateTimeOffset.UtcNow, "first visible transaction by created_at DESC, id DESC")]);
            }
        }
        else if (handoff.Specialist == Specialist.Unsupported)
            answer = Insufficient("I can explain demonstration policies and read your balances or latest transaction. I cannot execute financial actions.");
        else
        {
            if(handoff.Intent==PolicyIntent.UnsupportedPolicy)
            {
                await bank.WalletIds(delegation,token);
                return Insufficient("There is insufficient demonstration policy evidence for this policy category.");
            }
            var query = handoff.Query;
            while (true)
            {
                IReadOnlyList<Chunk> chunks = (await index.Search(query, budget, token)).Where(c=>RelevantSection(handoff.Intent,c.Section)).ToArray();
                if (chunks.Count == 0) { answer = Insufficient("There is insufficient policy evidence to answer this question."); break; }
                if(chunks.GroupBy(c=>c.DocumentId).Any(g=>g.Select(c=>c.Version).Distinct().Count()>1))
                {
                    answer=ValidatePolicy(new("The active demonstration policy evidence contains conflicting versions. I cannot select a definitive rule.","conflicting_evidence",chunks.Select(Id).ToArray(),null),chunks);break;
                }
                budget.Step();
                var output = await provider.Answer(PolicyQuestion(handoff.Intent), chunks, budget, token);
                if (output.Refine is not null)
                {
                    budget.Refine(); query = SanitizeQuery(output.Refine); continue;
                }
                answer = ValidatePolicy(output, chunks,handoff.Intent);
                break;
            }
        }
        await bank.WalletIds(delegation, token); // revocation during the turn must fail closed
        return answer;
    }
    public static string Format(long minor) => (minor / 100m).ToString("0.00", System.Globalization.CultureInfo.InvariantCulture);
    public static Answer Insufficient(string reply) => new(reply, "insufficient_evidence", [], new(null, null), []);
    public static Answer ValidatePolicy(PolicyOutput output, IReadOnlyList<Chunk> chunks,PolicyIntent? intent=null)
    {
        if (output.Reply.Length > 8000 || output.CitationIds.Length > 6 || output.CitationIds.Distinct().Count() != output.CitationIds.Length)
            throw new UnavailableException();
        if (output.Outcome is not ("answered" or "insufficient_evidence" or "conflicting_evidence")) throw new UnavailableException();
        var selected = new List<Citation>();
        foreach (var id in output.CitationIds)
        {
            var c = chunks.SingleOrDefault(c => Id(c) == id) ?? throw new UnavailableException();
            if(intent is not null && !RelevantSection(intent.Value,c.Section))throw new UnavailableException();
            selected.Add(new(c.DocumentId, c.Version, c.Section, c.Text[..Math.Min(1000, c.Text.Length)]));
        }
        if (output.Outcome == "answered" && selected.Count == 0) return Insufficient("There is insufficient policy evidence to answer this question.");
        var reply=output.Outcome switch {
            "insufficient_evidence"=>"There is insufficient demonstration policy evidence to answer this question.",
            "conflicting_evidence"=>"The retrieved demonstration policy evidence conflicts. I cannot select a definitive rule.",
            _=>"Demonstration ArMoney policy excerpts (not legal or production bank policies):\n\n"+string.Join("\n\n",selected.Select(c=>$"{c.Section}\n{c.Excerpt}"))
        };
        return new(reply, output.Outcome, selected, new(null, null), [new("policy-index", DateTimeOffset.UtcNow, "active effective demonstration corpus")]);
    }
    public static string Id(Chunk c) => $"{c.DocumentId}/{c.Version}/{c.Section}";
    public static string PolicyQuestion(PolicyIntent intent)=>intent switch {
        PolicyIntent.CurrencySupport=>"Which currency does ArMoney support?",
        PolicyIntent.Conversion=>"Does ArMoney convert currencies?",
        PolicyIntent.FilsRatio=>"How many fils equal one AED?",
        PolicyIntent.BalanceAuthority=>"Who owns balance correctness?",
        PolicyIntent.PaymentCompletion=>"When is a payment completed?",
        PolicyIntent.ConcurrentSpending=>"How does concurrent spending work?",
        PolicyIntent.SenderVisibility=>"Which payment statuses can senders see?",
        PolicyIntent.RecipientVisibility=>"Which transactions can recipients see?",
        PolicyIntent.HistoryOrder=>"How are visible transactions ordered?",
        PolicyIntent.ConversationStorage=>"Are assistant conversations stored by ArMoney?",
        PolicyIntent.ProviderRetention=>"Does store false guarantee zero provider retention?",
        _=>"Unsupported demonstration policy category"
    };
    public static string PolicyQuery(PolicyIntent intent)=>intent switch {
        PolicyIntent.CurrencySupport or PolicyIntent.Conversion or PolicyIntent.FilsRatio=>"currency AED fils conversion",
        PolicyIntent.BalanceAuthority or PolicyIntent.PaymentCompletion or PolicyIntent.ConcurrentSpending=>"balance ledger transfers concurrent completed",
        PolicyIntent.SenderVisibility or PolicyIntent.RecipientVisibility or PolicyIntent.HistoryOrder=>"transaction visibility history order sender recipient",
        PolicyIntent.ConversationStorage or PolicyIntent.ProviderRetention=>"privacy retention conversations store",
        _=>"unsupported policy"
    };
    public static bool RelevantSection(PolicyIntent intent,string section)
    {
        var expected=intent switch {
            PolicyIntent.CurrencySupport or PolicyIntent.Conversion or PolicyIntent.FilsRatio=>"currency",
            PolicyIntent.BalanceAuthority or PolicyIntent.PaymentCompletion or PolicyIntent.ConcurrentSpending=>"balances and transfers",
            PolicyIntent.SenderVisibility or PolicyIntent.RecipientVisibility or PolicyIntent.HistoryOrder=>"transaction visibility",
            PolicyIntent.ConversationStorage or PolicyIntent.ProviderRetention=>"privacy",
            _=>null
        };
        return expected is not null && section.Contains(expected,StringComparison.OrdinalIgnoreCase);
    }
    public static bool UnsupportedHistoryFilter(string message)
    {
        var q=message.ToLowerInvariant();
        return q.Any(char.IsDigit) || new[]{"completed","settled","successful","rejected","pending","month","week","yesterday","today","between","since","before","after","wallet","заверш","выполн","успеш","отклон","ожидан","месяц","недел","вчера","сегодня","кошел","после","период"}.Any(q.Contains);
    }
    public static string SanitizeQuery(string query)
    {
        if(query.Length is 0 or >200 || query.Any(c=>!char.IsLetter(c) && !char.IsWhiteSpace(c) && c!='-')) throw new UnavailableException();
        var q=query.ToLowerInvariant();
        // Never embed arbitrary model-produced names/text. Map only to finite trusted policy facets.
        if(new[]{"privacy","retention","conversation","store","приват","хран","конфиден"}.Any(q.Contains))return "privacy retention conversations store";
        if(new[]{"currency","aed","fils","conversion","валют","дирхам","филс"}.Any(q.Contains))return "currency AED fils conversion";
        if(new[]{"visibility","history","order","sender","recipient","transaction","истори","транзак","получател","отправител"}.Any(q.Contains))return "transaction visibility history order sender recipient";
        if(new[]{"balance","fund","concurr","ledger","баланс","средств","кошел","паралл"}.Any(q.Contains))return "balance ledger funds concurrency";
        if(new[]{"transfer","payment","status","completed","перевод","платеж","платёж","статус","заверш"}.Any(q.Contains))return "transfer payment ledger status completed";
        return "unsupported policy";
    }
}
