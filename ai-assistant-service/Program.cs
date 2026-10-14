using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ArMoney.Assistant.Adapters;
using ArMoney.Assistant.Application;
using ArMoney.Assistant.Domain;
using Npgsql;

static string Env(string name) => Environment.GetEnvironmentVariable(name) ?? "";
static decimal Price(string name) => decimal.TryParse(Env(name), System.Globalization.NumberStyles.Number, System.Globalization.CultureInfo.InvariantCulture, out var value) ? value : 0;
if(args.Length==1 && args[0]=="health")
{
    try { using var probe=new HttpClient {Timeout=TimeSpan.FromSeconds(3)}; using var response=await probe.GetAsync("http://127.0.0.1:8080/health/ready"); Environment.ExitCode=response.IsSuccessStatusCode?0:1; }
    catch { Environment.ExitCode=1; } return;
}
if (args.Length > 0 && args[0] == "operator")
{
    await using var operatorSource = NpgsqlDataSource.Create(Env("ASSISTANT_INGESTION_DATABASE_URL"));
    var ingestion = new OperatorIngestion(operatorSource); using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(2));
    try
    {
        if (args.Length == 2 && args[1] == "migrate") await ingestion.Migrate(timeout.Token);
        else if (args.Length == 3 && args[1] == "stage") await ingestion.Stage(args[2],timeout.Token);
        else if (args.Length == 3 && args[1] == "embed")
        {
            using var embeddingClient=new HttpClient(new SocketsHttpHandler { AllowAutoRedirect=false }) { Timeout=TimeSpan.FromSeconds(25) };
            await ingestion.EmbedIndex(args[2],new OpenAiEmbeddings(embeddingClient,Env("OPENAI_API_KEY"),Price("OPENAI_EMBEDDING_USD_PER_MILLION"),Price("ASSISTANT_EVAL_MAX_USD")),new TurnBudget(Price("ASSISTANT_EVAL_MAX_USD")),timeout.Token);
        }
        else if (args.Length == 3 && args[1] is "activate" or "rollback") await ingestion.Activate(args[2],timeout.Token,Env("ASSISTANT_RETRIEVAL_MODE"));
        else throw new InvalidDataException("Usage: operator migrate|stage manifest|activate index|rollback index");
    }
    catch { Console.Error.WriteLine("Operator action failed; no document or credential details are logged."); Environment.ExitCode=1; }
    return;
}
var builder=WebApplication.CreateBuilder(args);
builder.Logging.ClearProviders(); // no automatic exception/request/body or Npgsql tracing logs
builder.WebHost.ConfigureKestrel(k => k.Limits.MaxRequestBodySize=4096);
var json=new JsonSerializerOptions { PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower };
var forwardKey=Env("GATEWAY_ASSISTANT_KEY"); var callbackKey=Env("ASSISTANT_GATEWAY_KEY"); var gateway=Env("GATEWAY_URL");
var config=new ProviderConfiguration(Env("OPENAI_API_KEY"),Env("OPENAI_MODEL"),Env("OPENAI_PRICE_MODEL"),Price("OPENAI_INPUT_USD_PER_MILLION"),Price("OPENAI_OUTPUT_USD_PER_MILLION"),Price("ASSISTANT_MAX_REQUEST_USD"));
await using var data=NpgsqlDataSource.Create(Env("ASSISTANT_DATABASE_URL"));
using var gatewayClient=new HttpClient(new SocketsHttpHandler { AllowAutoRedirect=false }) { Timeout=TimeSpan.FromSeconds(10) };
using var providerClient=new HttpClient(new SocketsHttpHandler { AllowAutoRedirect=false }) { Timeout=TimeSpan.FromSeconds(25) };
var embeddings=Env("ASSISTANT_RETRIEVAL_MODE")=="hybrid" ? new OpenAiEmbeddings(providerClient,Env("OPENAI_API_KEY"),Price("OPENAI_EMBEDDING_USD_PER_MILLION"),config.MaxCost) : null;
var index=new PostgresPolicyIndex(data,embeddings); var provider=new OpenAiProvider(providerClient,config);
var turn=new AssistantTurn(new GatewayBankReads(gatewayClient,gateway,callbackKey),index,provider);
var permits=new SemaphoreSlim(4,4); var app=builder.Build();
bool ConfigReady() => forwardKey.Length>=32 && callbackKey.Length>=32 && forwardKey!=callbackKey && Uri.TryCreate(gateway,UriKind.Absolute,out var uri) && uri.Scheme is "http" or "https" && config.Valid && (embeddings is null || Price("OPENAI_EMBEDDING_USD_PER_MILLION")>0);
app.MapGet("/health/live",()=>Results.Json(new {status="live"}));
app.MapGet("/health/ready",async (CancellationToken token)=>
{
    try { var ready=ConfigReady() && await index.Ready(token); return Results.Json(new {status=ready?"ready":"unavailable",provider_evaluation="not_verified"},statusCode:ready?200:503); }
    catch { return Results.Json(new {status="unavailable",provider_evaluation="not_verified"},statusCode:503); }
});
app.MapPost("/v1/assistant/messages",async (HttpContext context)=>
{
    static IResult Error(int status,string error)=>Results.Json(new {error},statusCode:status);
    context.Response.Headers.CacheControl="no-store";
    var keyValues=context.Request.Headers["X-Gateway-Assistant-Key"];var handleValues=context.Request.Headers["X-Assistant-Delegation"];
    var supplied=keyValues.ToString(); var delegation=handleValues.ToString();
    if (keyValues.Count!=1 || handleValues.Count!=1 || forwardKey.Length<32 || supplied.Length!=forwardKey.Length || !CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(supplied),Encoding.UTF8.GetBytes(forwardKey)) || !System.Text.RegularExpressions.Regex.IsMatch(delegation,"^[A-Za-z0-9_-]{43}$") || context.Request.Headers.ContainsKey("Authorization")) return Error(401,"invalid_credentials");
    if (!ConfigReady()) return Error(503,"service_unavailable");
    if (!await permits.WaitAsync(0,context.RequestAborted)) return Error(429,"server_busy");
    using var deadline=CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted); deadline.CancelAfter(TimeSpan.FromSeconds(30));
    try
    {
        if (context.Request.ContentType?.Split(';')[0].Trim()!= "application/json") return Error(400,"invalid_request");
        using var content=new StreamContent(context.Request.Body); byte[] bytes;
        try { bytes=await BoundedBody.Read(content,4096,deadline.Token); } catch (UnavailableException) { return Error(413,"request_too_large"); }
        JsonDocument doc;
        try {doc=JsonDocument.Parse(bytes);} catch(JsonException) {return Error(400,"invalid_request");}
        using var parsedRequest=doc; var root=doc.RootElement;
        if (root.ValueKind!=JsonValueKind.Object || root.EnumerateObject().Count()!=1 || !root.TryGetProperty("message",out var message) || message.ValueKind!=JsonValueKind.String) return Error(400,"invalid_request");
        var answer=await turn.Execute(message.GetString()!,delegation,deadline.Token); var response=JsonSerializer.SerializeToUtf8Bytes(answer,json);
        if (response.Length>32768) return Error(503,"service_unavailable");
        return Results.Bytes(response,"application/json");
    }
    catch (UnauthorizedException) { return Error(401,"invalid_credentials"); }
    catch (InvalidInputException) { return Error(400,"invalid_request"); }
    catch (BadHttpRequestException e) when(e.StatusCode==413) { return Error(413,"request_too_large"); }
    catch { return Error(503,"service_unavailable"); }
    finally { permits.Release(); }
});
await app.RunAsync();
public partial class Program { }
