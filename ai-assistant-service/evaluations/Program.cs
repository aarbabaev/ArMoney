using System.Text.Json;
using ArMoney.Assistant.Adapters;
using ArMoney.Assistant.Application;
using ArMoney.Assistant.Domain;
using Npgsql;
using ArMoney.Assistant.Evaluation;
static string Env(string key)=>Environment.GetEnvironmentVariable(key)??"";
static decimal Amount(string key)=>decimal.TryParse(Env(key),System.Globalization.NumberStyles.Number,System.Globalization.CultureInfo.InvariantCulture,out var amount)?amount:0;
var config=new ProviderConfiguration(Env("OPENAI_API_KEY"),Env("OPENAI_MODEL"),Env("OPENAI_PRICE_MODEL"),Amount("OPENAI_INPUT_USD_PER_MILLION"),Amount("OPENAI_OUTPUT_USD_PER_MILLION"),Amount("ASSISTANT_MAX_REQUEST_USD"));
var fixtures=JsonSerializer.Deserialize<Fixture[]>(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory,"fixtures.v1.json")),new JsonSerializerOptions{PropertyNameCaseInsensitive=true})!;
if(!config.Valid || Amount("ASSISTANT_EVAL_MAX_USD")<fixtures.Length*config.MaxCost || Amount("OPENAI_EMBEDDING_USD_PER_MILLION")<=0) {Console.Error.WriteLine("Evaluation blocked: model/key/pinned prices or synthetic evaluation ceiling unavailable.");Environment.ExitCode=2;return;}
await using var db=NpgsqlDataSource.Create(Env("ASSISTANT_DATABASE_URL"));
await using var activeIndexCommand=db.CreateCommand("SELECT index_version FROM active_index WHERE singleton=true");
var activeIndexVersion=await activeIndexCommand.ExecuteScalarAsync() as string;
if(string.IsNullOrEmpty(activeIndexVersion)){Console.Error.WriteLine("Evaluation blocked: no active policy index.");Environment.ExitCode=2;return;}
using var http=new HttpClient(new SocketsHttpHandler{AllowAutoRedirect=false}){Timeout=TimeSpan.FromSeconds(25)};
var provider=new OpenAiProvider(http,config);var lexical=new PostgresPolicyIndex(db);var hybrid=new PostgresPolicyIndex(db,new OpenAiEmbeddings(http,Env("OPENAI_API_KEY"),Amount("OPENAI_EMBEDDING_USD_PER_MILLION"),config.MaxCost));
var reports=new List<object>();var results=new List<SampleResult>();var correctIntents=0;
var timer=System.Diagnostics.Stopwatch.StartNew();
foreach(var fixture in fixtures)
{
 using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(30));var budget=new TurnBudget(config.MaxCost);
 var sample=new SampleResult(fixture.Answerable,false,false,false,false,false,false);
 try
 {
  budget.Step();var plan=await provider.Plan(fixture.Question,budget,deadline.Token);var correct=plan.Specialist.ToString()==fixture.Specialist;sample=sample with {CorrectChoice=correct};
  var correctIntent=plan.Intent.ToString()==fixture.Intent;if(correctIntent)correctIntents++;
  IReadOnlyList<Chunk> lex=[],vec=[];PolicyOutput? output=null;Answer? answer=null;
  if(fixture.Specialist=="Policy" && plan.Specialist==Specialist.Policy && plan.Intent!=PolicyIntent.UnsupportedPolicy)
  {
   lex=await lexical.Search(plan.Query,budget,deadline.Token);vec=await hybrid.Search(plan.Query,budget,deadline.Token);
   if(fixture.Answerable)sample=sample with {LexicalHit=lex.Take(5).Any(c=>c.Section.Contains(fixture.Section!,StringComparison.OrdinalIgnoreCase)),HybridHit=vec.Take(5).Any(c=>c.Section.Contains(fixture.Section!,StringComparison.OrdinalIgnoreCase))};
   var relevant=vec.Where(c=>AssistantTurn.RelevantSection(plan.Intent,c.Section)).ToArray();
   if(relevant.Length>0){sample=sample with {CitationAttempted=true};budget.Step();output=await provider.Answer(AssistantTurn.PolicyQuestion(plan.Intent),relevant,budget,deadline.Token);answer=AssistantTurn.ValidatePolicy(output,relevant,plan.Intent);sample=sample with {CitationValid=true};}
   else answer=AssistantTurn.Insufficient("No retrieved policy evidence.");
  }
  else if(plan.Specialist==Specialist.Policy)answer=AssistantTurn.Insufficient("There is insufficient demonstration policy evidence for this policy category.");
  if(fixture.Specialist=="Policy" && !fixture.Answerable && answer?.Outcome!="insufficient_evidence")sample=sample with {Failed=true};
  reports.Add(new {fixture.Id,fixture.Question,expected_specialist=fixture.Specialist,actual_specialist=plan.Specialist.ToString(),correct_choice=correct,expected_intent=fixture.Intent,correct_intent=correctIntent,policy_intent=plan.Intent.ToString(),configured_intent_question=AssistantTurn.PolicyQuestion(plan.Intent),policy_query=plan.Query,lexical_ids=lex.Take(5).Select(AssistantTurn.Id),hybrid_ids=vec.Take(5).Select(AssistantTurn.Id),answer,refinement_requested=output?.Refine,semantic_grade="requires_independent_claim_review"});
 }
 catch {sample=sample with {Failed=true};reports.Add(new {fixture.Id,result="failed_closed",semantic_grade="not_evaluated"});}
 results.Add(sample);
}
var metrics=EvaluationMetrics.Aggregate(results);
var intentAccuracy=(decimal)correctIntents/fixtures.Length;var machineGate=metrics.MachineGatePassed && intentAccuracy>=0.95m;
Console.WriteLine(JsonSerializer.Serialize(new {
 evaluation_version="synthetic-v1-intent-extractive-extension",prompt_version="router-policy-v2-typed-intent-extractive",chunker_version="heading-1000-overlap100-v1",model=config.Model,embedding_model=OpenAiEmbeddings.Model,dimensions=OpenAiEmbeddings.Dimensions,
 index_version=activeIndexVersion,sample_count=fixtures.Length,latency_ms=timer.ElapsedMilliseconds,cost_upper_bound_usd=fixtures.Length*config.MaxCost,
 correct_tool_choice=metrics.CorrectToolChoice,recall_denominator=metrics.Answerable,lexical_recall_at_5=metrics.LexicalRecallAt5,hybrid_recall_at_5=metrics.HybridRecallAt5,
 machine_citation_validity=metrics.CitationValidity,correct_policy_intent=intentAccuracy,failed_closed_samples=results.Count(s=>s.Failed),machine_gate=machineGate?"passed":"failed",semantic_supported_claim_rate="not_graded",release_gate="pending independent semantic grading and security/QA acceptance",samples=reports
},new JsonSerializerOptions{WriteIndented=true,PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower}));
if(!machineGate)Environment.ExitCode=1;
sealed record Fixture(string Id,string Question,string Specialist,string? Section,bool Answerable,string Intent="UnsupportedPolicy");
