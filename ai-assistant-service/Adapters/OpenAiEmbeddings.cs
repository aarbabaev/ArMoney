using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using ArMoney.Assistant.Domain;
using ArMoney.Assistant.Application;
namespace ArMoney.Assistant.Adapters;
public sealed class OpenAiEmbeddings(HttpClient client, string key, decimal pricePerMillion, decimal maxCost)
{
    public const string Model = "text-embedding-3-small";
    public const int Dimensions = 1536;
    public async Task<string> Embed(string text,TurnBudget budget, CancellationToken token)
    {
        var bytes=System.Text.Encoding.UTF8.GetByteCount(text);
        if (key.Length == 0 || pricePerMillion<=0 || maxCost<=0 || bytes>24000 || bytes*pricePerMillion/1000000m>maxCost) throw new BudgetException();
        budget.Reserve(Math.Max(bytes,1)*pricePerMillion/1000000m);
        using var request=new HttpRequestMessage(HttpMethod.Post,"https://api.openai.com/v1/embeddings");
        request.Headers.Authorization=new AuthenticationHeaderValue("Bearer",key);
        request.Content=JsonContent.Create(new {model=Model,input=text,dimensions=Dimensions,encoding_format="float"});
        using var response=await client.SendAsync(request,HttpCompletionOption.ResponseHeadersRead,token);
        if (!response.IsSuccessStatusCode) throw new UnavailableException();
        using var doc=JsonDocument.Parse(await BoundedBody.Read(response.Content,131072,token));
        var entries=doc.RootElement.GetProperty("data");
        if (entries.GetArrayLength()!=1 || doc.RootElement.GetProperty("model").GetString()!=Model) throw new UnavailableException();
        var values=entries[0].GetProperty("embedding").EnumerateArray().Select(x=>x.GetSingle()).ToArray();
        if(values.Length!=Dimensions || values.Any(v=>!float.IsFinite(v)) || values.All(v=>v==0)) throw new UnavailableException();
        return "["+string.Join(",",values.Select(v=>v.ToString("R",System.Globalization.CultureInfo.InvariantCulture)))+"]";
    }
}
