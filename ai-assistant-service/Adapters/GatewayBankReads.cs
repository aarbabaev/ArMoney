using System.Net;
using System.Text.Json;
using ArMoney.Assistant.Application;
using ArMoney.Assistant.Domain;
namespace ArMoney.Assistant.Adapters;
public sealed class GatewayBankReads(HttpClient client, string gatewayUrl, string key) : IBankReads
{
    private async Task<JsonDocument> Get(string path, string delegation, CancellationToken token)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(new Uri(gatewayUrl), path));
        request.Headers.Add("X-Assistant-Gateway-Key", key);
        request.Headers.Add("X-Assistant-Delegation", delegation);
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
        if (response.StatusCode == HttpStatusCode.Unauthorized || response.StatusCode == HttpStatusCode.Forbidden) throw new UnauthorizedException();
        if (!response.IsSuccessStatusCode) throw new UnavailableException();
        var bytes = await BoundedBody.Read(response.Content, 65536, token);
        return JsonDocument.Parse(bytes);
    }
    public async Task<IReadOnlyList<string>> WalletIds(string delegation, CancellationToken token)
    {
        using var doc = await Get("/v1/internal/assistant/wallets", delegation, token);
        var ids = doc.RootElement.GetProperty("wallets").EnumerateArray().Select(w => w.GetProperty("id").GetString() ?? throw new UnavailableException()).ToArray();
        if (ids.Length > 10 || ids.Any(id => !Guid.TryParse(id, out _))) throw new UnavailableException();
        return ids;
    }
    public async Task<Wallet> Balance(string delegation, string walletId, CancellationToken token)
    {
        if (!Guid.TryParse(walletId, out _)) throw new UnavailableException();
        using var doc = await Get($"/v1/internal/assistant/wallets/{walletId}/balance", delegation, token);
        var root = doc.RootElement;
        if (root.GetProperty("wallet_id").GetString() != walletId || root.GetProperty("currency").GetString() != "AED") throw new UnavailableException();
        var balance = root.GetProperty("balance_minor").GetInt64();
        if (balance < 0) throw new UnavailableException();
        return new(walletId, "AED", balance);
    }
    public async Task<IReadOnlyList<Transaction>> History(string delegation, CancellationToken token)
    {
        using var doc = await Get("/v1/internal/assistant/payments/page?limit=50", delegation, token);
        var items = doc.RootElement.GetProperty("payments").EnumerateArray().Select(p => new Transaction(
            p.GetProperty("id").GetString()!, p.GetProperty("created_at").GetDateTimeOffset(), p.GetProperty("currency").GetString()!,
            p.GetProperty("amount_minor").GetInt64(), p.GetProperty("direction").GetString()!, p.GetProperty("status").GetString()!)).ToArray();
        if (items.Length > 50 || items.Any(p => !Guid.TryParse(p.Id, out _) || p.Currency != "AED" || p.AmountMinor <= 0 || p.Direction is not ("incoming" or "outgoing") || p.Status is not ("PENDING" or "COMPLETED" or "REJECTED") || p.Direction == "incoming" && p.Status != "COMPLETED")) throw new UnavailableException();
        return items;
    }
}
public static class BoundedBody
{
    public static async Task<byte[]> Read(HttpContent content, int max, CancellationToken token)
    {
        await using var stream = await content.ReadAsStreamAsync(token);
        using var output = new MemoryStream(); var buffer = new byte[4096]; int read;
        while ((read = await stream.ReadAsync(buffer, token)) != 0)
        { if (output.Length + read > max) throw new UnavailableException(); await output.WriteAsync(buffer.AsMemory(0, read), token); }
        return output.ToArray();
    }
}
