using ArMoney.Assistant.Application;
using ArMoney.Assistant.Adapters;
using ArMoney.Assistant.Domain;
using Npgsql;
using System.Net;
using System.Text;
using ArMoney.Assistant.Evaluation;

var passed=0;
void Check(bool condition,string name) { if(!condition) throw new Exception(name); passed++; }
async Task Throws<T>(Func<Task> action,string name) where T:Exception
{ try {await action();} catch(T) {passed++;return;} throw new Exception(name); }
await Throws<InvalidInputException>(()=>Task.Run(()=>AssistantTurn.ValidateMessage(new string('a',2001))),"character bound");
await Throws<InvalidInputException>(()=>Task.Run(()=>AssistantTurn.ValidateMessage(string.Concat(Enumerable.Repeat("😀",2000)))),"UTF8 byte bound");
Check(AssistantTurn.Format(12345)=="123.45","integer fils formatting");
var metric=EvaluationMetrics.Aggregate([new(true,true,true,true,true,true,false),new(true,false,false,false,false,false,false),new(true,false,false,false,true,false,true)]);
Check(metric.Answerable==3 && metric.HybridRecallAt5==1m/3m && metric.CorrectToolChoice==1m/3m && metric.CitationValidity==0.5m && !metric.MachineGatePassed,"wrong routing/failures remain recall denominator and fail numeric gates");
await Throws<BudgetException>(()=>Task.Run(()=>{var b=new TurnBudget();for(var i=0;i<5;i++)b.Step();}),"step limit");
await Throws<BudgetException>(()=>Task.Run(()=>{var b=new TurnBudget();for(var i=0;i<3;i++)b.Refine();}),"refinement limit");
await Throws<BudgetException>(()=>Task.Run(()=>{var b=new TurnBudget(0.02m);b.Reserve(0.015m);b.Reserve(0.01m);}),"cumulative cost");
await Throws<BudgetException>(()=>Task.Run(()=>{var b=new TurnBudget();b.Output(500);b.Output(501);}),"total output bound");
await Throws<UnavailableException>(()=>Task.Run(()=>AssistantTurn.SanitizeQuery("https://evil.example")),"no URL retrieval");
await Throws<UnavailableException>(()=>Task.Run(()=>AssistantTurn.SanitizeQuery("owner 123456")),"no identity query");
Check(AssistantTurn.SanitizeQuery("John Smith") == "unsupported policy" && AssistantTurn.SanitizeQuery("John Smith currency") == "currency AED fils conversion","only trusted facets embedded");
var chunks=new[]{new Chunk("demo","v1","Demonstration ArMoney policy: currency / chunk 1","AED uses integer fils.","synthetic demo")};
await Throws<UnavailableException>(()=>Task.Run(()=>AssistantTurn.ValidatePolicy(new("invented","answered",["other/v1/section"],null),chunks)),"forged citation");
Check(AssistantTurn.ValidatePolicy(new("claim","answered",[],null),chunks).Outcome=="insufficient_evidence","missing citation");
Check(AssistantTurn.ValidatePolicy(new("Demonstration AED rule","answered",[AssistantTurn.Id(chunks[0])],null),chunks).Citations.Count==1,"valid citation");
var forgedProse=AssistantTurn.ValidatePolicy(new("ArMoney supports USD and your payment is COMPLETED.","answered",[AssistantTurn.Id(chunks[0])],null),chunks,PolicyIntent.CurrencySupport);
Check(!forgedProse.Reply.Contains("USD") && !forgedProse.Reply.Contains("COMPLETED") && forgedProse.Reply.Contains("AED uses integer fils."),"genuine citation cannot legitimize unsupported generated prose");
await Throws<UnavailableException>(()=>Task.Run(()=>AssistantTurn.ValidatePolicy(new("Your payment is completed.","answered",[AssistantTurn.Id(chunks[0])],null),chunks,PolicyIntent.PaymentCompletion)),"currency citation cannot answer payment completion intent");
var bank=new FakeBank(); var provider=new FakeProvider(Specialist.Balances); var index=new FakeIndex(chunks);
var assistant=new AssistantTurn(bank,index,provider);
var result=await assistant.Execute("balance","owner-a",CancellationToken.None);
Check(result.Facts.Wallets![0].BalanceMinor==12345 && !provider.AnswerCalled,"bank numbers bypass model");
Check(bank.Handles.All(h=>h=="owner-a") && bank.AuthCalls==2,"request-local handle");
bank.RevokeFinal=true;
await Throws<UnauthorizedException>(()=>assistant.Execute("balance","owner-a",CancellationToken.None),"inflight revoke");
bank=new FakeBank();provider=new FakeProvider(Specialist.LastTransaction); assistant=new AssistantTurn(bank,index,provider);
result=await assistant.Execute("last transaction","owner-b",CancellationToken.None);
Check(result.Outcome=="empty_history" && result.Facts.LastTransaction is null,"empty history");
bank.Items=[new("b",DateTimeOffset.Parse("2026-10-13T01:00:00Z"),"AED",100,"outgoing","PENDING"),new("a",DateTimeOffset.Parse("2026-10-13T01:00:00Z"),"AED",200,"incoming","COMPLETED")];
result=await assistant.Execute("last transaction","owner-b",CancellationToken.None);
Check(result.Facts.LastTransaction!.Id=="b" && result.Facts.LastTransaction.Status=="PENDING","creation tie order actual status");
foreach(var filtered in new[]{"last completed transaction","last settled transaction","monthly history","last transaction for wallet","последняя завершённая транзакция"})
{
 result=await assistant.Execute(filtered,"owner-b",CancellationToken.None);Check(result.Outcome=="insufficient_evidence" && result.Facts.LastTransaction is null,"filtered history cannot masquerade as default visible result");
}
provider=new FakeProvider(Specialist.Policy){Loop=true};assistant=new AssistantTurn(new FakeBank(),index,provider);
await Throws<BudgetException>(()=>assistant.Execute("policy","owner-a",CancellationToken.None),"model loop capped");
provider=new FakeProvider(Specialist.Policy){Outage=true};assistant=new AssistantTurn(new FakeBank(),index,provider);
await Throws<UnavailableException>(()=>assistant.Execute("policy","owner-a",CancellationToken.None),"provider outage not success");
provider=new FakeProvider(Specialist.Policy){Intent=PolicyIntent.UnsupportedPolicy};assistant=new AssistantTurn(new FakeBank(),index,provider);
result=await assistant.Execute("What interest rate is paid?","owner-a",CancellationToken.None);
Check(result.Outcome=="insufficient_evidence" && result.Citations.Count==0 && !provider.AnswerCalled,"unsupported intent never becomes unrelated policy quote");
provider=new FakeProvider(Specialist.Policy){Intent=PolicyIntent.CurrencySupport};assistant=new AssistantTurn(new FakeBank(),index,provider);result=await assistant.Execute("Does ArMoney support USD?","owner-a",CancellationToken.None);
Check(provider.AnswerQuestion=="Which currency does ArMoney support?" && result.Reply.Contains("AED uses integer fils."),"typed question preserves currency-support intent without personal query text");
var parsed=OperatorIngestion.Chunk("# Demonstration\n"+new string('a',2300));
Check(parsed.Count==3 && parsed.All(c=>c.Text.Length<=1000) && parsed.Select(c=>c.Section).Distinct().Count()==3,"bounded unique chunks");
var containment=Path.Combine(Path.GetTempPath(),"assistant-path-"+Guid.NewGuid());Directory.CreateDirectory(containment);
try
{
 var inside=Path.Combine(containment,"policies");var outside=Path.Combine(containment,"outside");Directory.CreateDirectory(inside);Directory.CreateDirectory(outside);Directory.CreateDirectory(Path.Combine(inside,"nested"));
 var manifest=Path.Combine(inside,"manifest.json");await File.WriteAllTextAsync(manifest,"{}");await File.WriteAllTextAsync(Path.Combine(outside,"private.txt"),"synthetic outside content");await File.WriteAllTextAsync(Path.Combine(inside,"nested","regular.md"),"synthetic policy");
 Check(OperatorIngestion.ResolveDocumentPath(manifest,"nested/regular.md")==Path.Combine(inside,"nested","regular.md"),"regular nested file allowed");
 await Throws<InvalidDataException>(()=>Task.Run(()=>OperatorIngestion.ResolveDocumentPath(manifest,"../outside/private.txt")),"parent traversal blocked before DB");
 await Throws<InvalidDataException>(()=>Task.Run(()=>OperatorIngestion.ResolveDocumentPath(manifest,Path.Combine(outside,"private.txt"))),"absolute path blocked before DB");
 if(!OperatingSystem.IsWindows())
 {
  Directory.CreateSymbolicLink(Path.Combine(inside,"linked"),outside);File.CreateSymbolicLink(Path.Combine(inside,"direct.txt"),Path.Combine(outside,"private.txt"));File.CreateSymbolicLink(Path.Combine(inside,"linked-manifest.json"),manifest);
  await Throws<InvalidDataException>(()=>Task.Run(()=>OperatorIngestion.ResolveDocumentPath(manifest,"linked/private.txt")),"ancestor symlink blocked before DB");
  await Throws<InvalidDataException>(()=>Task.Run(()=>OperatorIngestion.ResolveDocumentPath(manifest,"direct.txt")),"direct symlink blocked before DB");
  await Throws<InvalidDataException>(()=>Task.Run(()=>OperatorIngestion.ResolveDocumentPath(Path.Combine(inside,"linked-manifest.json"),"nested/regular.md")),"manifest symlink blocked before DB");
 }
}
finally{Directory.Delete(containment,true);}
var transport=new TestHandler("{\"wallets\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"currency\":\"AED\",\"status\":\"ACTIVE\",\"provisioning_status\":\"READY\"}]}");
using(var http=new HttpClient(transport))
{
 var adapter=new GatewayBankReads(http,"http://gateway:8080","direction-key"); await adapter.WalletIds("opaque-handle",CancellationToken.None);
 Check(transport.Path=="/v1/internal/assistant/wallets" && transport.Delegation=="opaque-handle" && !transport.Bearer,"fixed auth callback");
 transport.Status=HttpStatusCode.Unauthorized;
 await Throws<UnauthorizedException>(()=>adapter.WalletIds("opaque-handle",CancellationToken.None),"invalid delegated auth");
}
// Isolated PostgreSQL is mandatory for persistence evidence. Absence is a failing check.
var connection=Environment.GetEnvironmentVariable("ASSISTANT_TEST_DATABASE_URL");
if(string.IsNullOrWhiteSpace(connection)) throw new Exception("ASSISTANT_TEST_DATABASE_URL required: disposable pgvector PostgreSQL; persistence tests not skipped");
await using(var source=NpgsqlDataSource.Create(connection))
{
 var op=new OperatorIngestion(source);await op.Migrate(CancellationToken.None);await op.Migrate(CancellationToken.None);
 var folder=Path.Combine(Path.GetTempPath(),"assistant-synthetic-"+Guid.NewGuid());Directory.CreateDirectory(folder);
 try
 {
  var manifest=new DocumentManifest("synthetic-demo","v1","synthetic test",DateTimeOffset.Parse("2020-01-01T00:00:00Z"),null,null,"synthetic-v1","policy.md");
  await File.WriteAllTextAsync(Path.Combine(folder,"policy.md"),"# Demonstration AED\nAED uses integer fils.");
  var path=Path.Combine(folder,"manifest.json");await File.WriteAllTextAsync(path,System.Text.Json.JsonSerializer.Serialize(manifest));
  await op.Stage(path,CancellationToken.None);await op.Stage(path,CancellationToken.None);await op.Activate("synthetic-v1",CancellationToken.None,"lexical");
  var publishedExtra=manifest with {DocumentId="synthetic-active-extra"};
  await File.WriteAllTextAsync(path,System.Text.Json.JsonSerializer.Serialize(publishedExtra));
  await Throws<InvalidDataException>(()=>op.Stage(path,CancellationToken.None),"active index rejects new document");
  await File.WriteAllTextAsync(path,System.Text.Json.JsonSerializer.Serialize(manifest));await op.Stage(path,CancellationToken.None);Check(true,"identical published reingestion is safe");
  var pg=new PostgresPolicyIndex(source);Check(await pg.Ready(CancellationToken.None),"active readiness");Check((await pg.Search("AED",new TurnBudget(),CancellationToken.None)).Count==1,"real fulltext retrieval");
  await File.WriteAllTextAsync(Path.Combine(folder,"policy.md"),"Changed payload");await Throws<InvalidDataException>(()=>op.Stage(path,CancellationToken.None),"digest conflict");
  await Throws<InvalidDataException>(()=>op.Activate("missing",CancellationToken.None,"lexical"),"unknown activation");Check((await pg.Search("AED",new TurnBudget(),CancellationToken.None)).Count==1,"failed activation preserves active index");
  manifest=manifest with {Version="v2",IndexVersion="synthetic-v2",Supersedes="v1"};
  await File.WriteAllTextAsync(Path.Combine(folder,"policy.md"),"# Demonstration revised\nRevised currency policy supports AED.");await File.WriteAllTextAsync(path,System.Text.Json.JsonSerializer.Serialize(manifest));await op.Stage(path,CancellationToken.None);await op.Activate("synthetic-v2",CancellationToken.None,"lexical");
  Check((await pg.Search("revised",new TurnBudget(),CancellationToken.None)).Count==1,"activate new version");await op.Activate("synthetic-v1",CancellationToken.None,"lexical");Check((await pg.Search("revised",new TurnBudget(),CancellationToken.None)).Count==0,"rollback keeps prior immutable corpus");
  var retiredExtra=manifest with {DocumentId="synthetic-retired-extra"};await File.WriteAllTextAsync(path,System.Text.Json.JsonSerializer.Serialize(retiredExtra));await Throws<InvalidDataException>(()=>op.Stage(path,CancellationToken.None),"previously published inactive index rejects new document");
  await File.WriteAllTextAsync(path,System.Text.Json.JsonSerializer.Serialize(manifest));await op.Stage(path,CancellationToken.None);Check(true,"identical retired reingestion is safe");
  manifest=manifest with {Version="expired",IndexVersion="synthetic-expired",EffectiveFrom=DateTimeOffset.Parse("2010-01-01T00:00:00Z"),EffectiveTo=DateTimeOffset.Parse("2011-01-01T00:00:00Z")};await File.WriteAllTextAsync(path,System.Text.Json.JsonSerializer.Serialize(manifest));await op.Stage(path,CancellationToken.None);await op.Activate("synthetic-expired",CancellationToken.None,"lexical");Check(!await pg.Ready(CancellationToken.None) && (await pg.Search("AED",new TurnBudget(),CancellationToken.None)).Count==0,"expired documents excluded");await op.Activate("synthetic-v1",CancellationToken.None,"lexical");
  await using var roleCheck=source.CreateCommand("SELECT has_table_privilege('assistant_reader','policy_documents','SELECT') AND NOT has_table_privilege('assistant_reader','policy_documents','INSERT') AND NOT has_table_privilege('assistant_reader','policy_documents','UPDATE')");Check((bool)(await roleCheck.ExecuteScalarAsync()??false),"runtime role is read-only");
  using var raceDeadline=new CancellationTokenSource(TimeSpan.FromSeconds(15));var raceToken=raceDeadline.Token;
  var raceId=Guid.NewGuid().ToString("N");var raceIndex="synthetic-race-"+raceId;
  var raceManifest=new DocumentManifest("race-base-"+raceId,"v1","synthetic race",DateTimeOffset.Parse("2020-01-01T00:00:00Z"),null,null,raceIndex,"policy.md");
  await File.WriteAllTextAsync(path,System.Text.Json.JsonSerializer.Serialize(raceManifest),raceToken);await op.Stage(path,raceToken);
  await Throws<InvalidDataException>(()=>op.Activate(raceIndex,raceToken,"hybrid"),"incomplete hybrid index rejected before activation");
  await using(var active=source.CreateCommand("SELECT index_version FROM active_index WHERE singleton=true"))Check((string)(await active.ExecuteScalarAsync(raceToken)??"")=="synthetic-v1","failed hybrid activation keeps healthy prior index");
  await Throws<InvalidDataException>(()=>op.Activate(raceIndex,raceToken,""),"activation mode is explicit fail closed");
  var extraPath=Path.Combine(folder,"race-extra.json");await File.WriteAllTextAsync(extraPath,System.Text.Json.JsonSerializer.Serialize(raceManifest with {DocumentId="race-extra-"+raceId}),raceToken);
  await using var activationSource=NpgsqlDataSource.Create(new NpgsqlConnectionStringBuilder(connection){ApplicationName="assistant-race-activate"}.ConnectionString);
  await using var stageSource=NpgsqlDataSource.Create(new NpgsqlConnectionStringBuilder(connection){ApplicationName="assistant-race-stage"}.ConnectionString);
  await using var locker=await source.OpenConnectionAsync(raceToken);await using var lockTransaction=await locker.BeginTransactionAsync(raceToken);
  await using(var held=new NpgsqlCommand("SELECT pg_advisory_xact_lock(hashtextextended(@index,791021))",locker,lockTransaction)){held.Parameters.AddWithValue("index",raceIndex);await held.ExecuteNonQueryAsync(raceToken);}
  async Task WaitForLock(string application)
  {
   for(var attempt=0;attempt<100;attempt++)
   {
    await using var waiting=source.CreateCommand("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=@application AND wait_event='advisory')");waiting.Parameters.AddWithValue("application",application);
    if((bool)(await waiting.ExecuteScalarAsync(raceToken)??false))return;await Task.Delay(25,raceToken);
   }
   throw new Exception("Race participant did not reach advisory lock");
  }
  var activateRace=new OperatorIngestion(activationSource).Activate(raceIndex,raceToken,"lexical");await WaitForLock("assistant-race-activate");
  var stageRace=new OperatorIngestion(stageSource).Stage(extraPath,raceToken);await WaitForLock("assistant-race-stage");
  await lockTransaction.CommitAsync(raceToken);await activateRace;await Throws<InvalidDataException>(()=>stageRace,"activation/staging race rejects post-publication mutation");
  var publishedTransport=new TestHandler("{}");using(var publishedHttp=new HttpClient(publishedTransport))
  {
   await Throws<InvalidDataException>(()=>op.EmbedIndex(raceIndex,new OpenAiEmbeddings(publishedHttp,"synthetic-not-a-key",0.02m,1m),new TurnBudget(1m),raceToken),"published index embedding rejected before provider calls");
   Check(publishedTransport.Path is null,"published embed performs no external call");
  }
  await using(var count=source.CreateCommand("SELECT count(*) FROM policy_documents WHERE index_version=@index")){count.Parameters.AddWithValue("index",raceIndex);Check(Convert.ToInt64(await count.ExecuteScalarAsync(raceToken))==1,"raced index has only prepublication document");}
  await op.Activate("synthetic-v1",raceToken,"lexical");
 }
 finally {Directory.Delete(folder,true);}
}
// Run actual private HTTP adapter with synthetic credentials and an unreachable callback origin.
// Authentication/shape tests cannot reach a provider and never use the owner's key.
var listener=new System.Net.Sockets.TcpListener(IPAddress.Loopback,0);listener.Start();var port=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
var location=AppContext.BaseDirectory;var serviceAssembly=typeof(AssistantTurn).Assembly.Location;
var start=new System.Diagnostics.ProcessStartInfo("dotnet") {UseShellExecute=false,RedirectStandardOutput=true,RedirectStandardError=true,CreateNoWindow=true};
foreach(var arg in new[]{"exec","--runtimeconfig",Path.Combine(location,"Assistant.Tests.runtimeconfig.json"),"--depsfile",Path.Combine(location,"Assistant.Tests.deps.json"),serviceAssembly,"--urls",$"http://127.0.0.1:{port}"})start.ArgumentList.Add(arg);
start.Environment["ASSISTANT_DATABASE_URL"]=connection;
start.Environment["GATEWAY_URL"]="http://127.0.0.1:1";start.Environment["GATEWAY_ASSISTANT_KEY"]=new string('g',32);start.Environment["ASSISTANT_GATEWAY_KEY"]=new string('a',32);
start.Environment["OPENAI_API_KEY"]="synthetic-not-a-key";start.Environment["OPENAI_MODEL"]="synthetic-model";start.Environment["OPENAI_PRICE_MODEL"]="synthetic-model";start.Environment["OPENAI_INPUT_USD_PER_MILLION"]="0.4";start.Environment["OPENAI_OUTPUT_USD_PER_MILLION"]="1.6";start.Environment["ASSISTANT_MAX_REQUEST_USD"]="0.02";start.Environment["ASSISTANT_RETRIEVAL_MODE"]="lexical";
using(var process=System.Diagnostics.Process.Start(start)??throw new Exception("HTTP fixture failed to start"))
{
 _=process.StandardOutput.ReadToEndAsync();_=process.StandardError.ReadToEndAsync();
 try
 {
  using var client=new HttpClient {BaseAddress=new Uri($"http://127.0.0.1:{port}"),Timeout=TimeSpan.FromSeconds(3)};bool live=false;
  for(var i=0;i<100 && !live;i++){try {using var health=await client.GetAsync("/health/live");live=health.IsSuccessStatusCode;}catch(HttpRequestException){}await Task.Delay(50);}
  Check(live,"HTTP liveness");
  async Task<HttpResponseMessage> Send(string body,bool credential=true,bool duplicate=false,bool bearer=false,string? key=null,string path="/v1/assistant/messages")
  {
   using var request=new HttpRequestMessage(HttpMethod.Post,path){Content=new StringContent(body,Encoding.UTF8,"application/json")};
   if(credential){request.Headers.TryAddWithoutValidation("X-Gateway-Assistant-Key",key??new string('g',32));request.Headers.TryAddWithoutValidation("X-Assistant-Delegation",new string('a',43));}
   if(duplicate)request.Headers.TryAddWithoutValidation("X-Gateway-Assistant-Key",new string('g',32));if(bearer)request.Headers.Authorization=new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer","synthetic-original-bearer");
   return await client.SendAsync(request);
  }
  using(var r=await Send("{}",false)){Check(r.StatusCode==HttpStatusCode.Unauthorized && r.Headers.CacheControl?.NoStore==true,"missing auth no-store");}
  using(var r=await Send("{}",key:new string('x',32))){Check(r.StatusCode==HttpStatusCode.Unauthorized,"forged gateway key");}
  using(var r=await Send("{}",duplicate:true)){Check(r.StatusCode==HttpStatusCode.Unauthorized,"duplicate auth rejected");}
  using(var r=await Send("{}",bearer:true)){Check(r.StatusCode==HttpStatusCode.Unauthorized,"original bearer rejected");}
  using(var r=await Send("{broken")){Check(r.StatusCode==HttpStatusCode.BadRequest,"malformed JSON");}
  using(var r=await Send("{\"message\":\"x\",\"owner_id\":\"forged\"}")){Check(r.StatusCode==HttpStatusCode.BadRequest,"client identity forbidden");}
  using(var r=await Send("{\"message\":\"\"}")){Check(r.StatusCode==HttpStatusCode.BadRequest,"empty question");}
  using(var r=await Send("{\"message\":\""+new string('a',5000)+"\"}")){Check(r.StatusCode==HttpStatusCode.RequestEntityTooLarge,"wire body bound");}
  using(var r=await Send("{\"message\":\"What is my balance?\"}")){Check(r.StatusCode==HttpStatusCode.ServiceUnavailable,"auth dependency outage never fake facts");}
  using(var r=await Send("{}",path:"/v1/assistant/ingest")){Check(r.StatusCode==HttpStatusCode.NotFound,"no public ingestion");}
 }
 finally {if(!process.HasExited)process.Kill(true);await process.WaitForExitAsync();}
}
Console.WriteLine($"PASS {passed} deterministic and isolated PostgreSQL assertions; no actual model evaluation.");

sealed class FakeBank:IBankReads
{
 public List<string> Handles=[];public int AuthCalls;public bool RevokeFinal;public IReadOnlyList<Transaction> Items=[];
 public Task<IReadOnlyList<string>> WalletIds(string d,CancellationToken t){Handles.Add(d);AuthCalls++;if(RevokeFinal && AuthCalls%2==0)throw new UnauthorizedException();return Task.FromResult<IReadOnlyList<string>>(["11111111-1111-1111-1111-111111111111"]);}
 public Task<Wallet> Balance(string d,string id,CancellationToken t){Handles.Add(d);return Task.FromResult(new Wallet(id,"AED",12345));}
 public Task<IReadOnlyList<Transaction>> History(string d,CancellationToken t){Handles.Add(d);return Task.FromResult(Items);}
}
sealed class FakeIndex(IReadOnlyList<Chunk> chunks):IPolicyIndex
{public Task<bool> Ready(CancellationToken t)=>Task.FromResult(true);public Task<IReadOnlyList<Chunk>> Search(string q,TurnBudget b,CancellationToken t)=>Task.FromResult(chunks);}
sealed class FakeProvider(Specialist route):IProvider
{
 public bool Configured=>true;public decimal CostLimit=>0.02m;public bool AnswerCalled,Loop,Outage;public PolicyIntent Intent=PolicyIntent.CurrencySupport;public string? AnswerQuestion;
 public Task<Handoff> Plan(string q,TurnBudget b,CancellationToken t)=>Task.FromResult(new Handoff(route,route==Specialist.Policy?AssistantTurn.PolicyQuery(Intent):"",route==Specialist.Policy?Intent:PolicyIntent.UnsupportedPolicy));
 public Task<PolicyOutput> Answer(string q,IReadOnlyList<Chunk> c,TurnBudget b,CancellationToken t){AnswerCalled=true;AnswerQuestion=q;if(Outage)throw new UnavailableException();return Task.FromResult(new PolicyOutput("Demonstration AED uses integer fils.","answered",[AssistantTurn.Id(c[0])],Loop?"AED policy":null));}
}
sealed class TestHandler(string body):HttpMessageHandler
{
 public HttpStatusCode Status=HttpStatusCode.OK;public string? Path,Delegation;public bool Bearer;
 protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage r,CancellationToken t){Path=r.RequestUri!.PathAndQuery;Delegation=r.Headers.GetValues("X-Assistant-Delegation").Single();Bearer=r.Headers.Authorization is not null;return Task.FromResult(new HttpResponseMessage(Status){Content=new StringContent(body,Encoding.UTF8,"application/json")});}
}
