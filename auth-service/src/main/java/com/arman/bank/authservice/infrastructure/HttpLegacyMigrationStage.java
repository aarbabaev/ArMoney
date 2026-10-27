package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.LegacyMigrationOtpStore.StagePort;
import com.arman.bank.authservice.domain.LegacyMigrationOtp;
import com.arman.bank.authservice.domain.LegacyMigrationOtp.*;
import com.arman.bank.authservice.domain.LegacyMigrationOtp.Error;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Resolves only an existing owner profile, then obtains the distinct authenticated U2 stage ACK.
 * A transport success is neither account/provider proof nor banking authorization.
 */
public final class HttpLegacyMigrationStage implements StagePort,AutoCloseable {
 private static final int LIMIT=12288;
 private static final ObjectMapper JSON=new ObjectMapper()
  .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
  .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private static final Set<String> PROFILE_FIELDS=Set.of("id","identity_id","display_name","phone_number","phone_verified");
 private final URI profileEndpoint,stageEndpoint;
 private final String readKey,stageKey;
 private final HttpClient http;
 private final Clock clock;
 private final Semaphore admission=new Semaphore(16);
 private volatile boolean closed;

 public HttpLegacyMigrationStage(URI origin,String readKey,String stageKey) {
  this(origin,readKey,stageKey,Clock.systemUTC());
 }
 public HttpLegacyMigrationStage(URI origin,String readKey,String stageKey,Clock clock) {
  if(origin==null||!Set.of("http","https").contains(origin.getScheme())||origin.getHost()==null
    ||origin.getUserInfo()!=null||origin.getQuery()!=null||origin.getFragment()!=null
    ||!(origin.getPath().isEmpty()||origin.getPath().equals("/"))) throw new IllegalArgumentException("Fixed User origin required");
  key(readKey); key(stageKey);
  if(!Collections.disjoint(aliases(readKey),aliases(stageKey))) throw new IllegalArgumentException("Distinct migration stage credential required");
  this.readKey=readKey; this.stageKey=stageKey; this.clock=Objects.requireNonNull(clock);
  profileEndpoint=origin.resolve("/v1/users/me"); stageEndpoint=origin.resolve("/v1/internal/migrations/legacy/user-stage");
  http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 }
 private static void key(String value) { if(value==null||!value.matches("[!-~]{32,256}")) throw new IllegalArgumentException("Invalid migration transport credential"); }
 private static Set<String> aliases(String value) {
  var set=new HashSet<String>(); set.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.US_ASCII)));
  for(var decoder:List.of(Base64.getDecoder(),Base64.getUrlDecoder())) try { set.add(Base64.getEncoder().encodeToString(decoder.decode(value))); } catch(IllegalArgumentException ignored) { }
  return set;
 }
 @Override public StageExchange stage(Seed seed) {
  validateSeed(seed);
  if(closed||!admission.tryAcquire()) throw new Failure(Error.UNAVAILABLE);
  try {
   // This wall-clock check only bounds transport. The Auth store must still recheck its PRIMARY
   // original deadline after attachStage; no remote response extends admitted authorization.
   Instant originalBound=seed.accountDeadline().isBefore(seed.intentDeadline())?seed.accountDeadline():seed.intentDeadline();
   Instant now=clock.instant();
   if(now.isBefore(seed.createdAt())) throw new Failure(Error.UNAVAILABLE);
   long remaining=Duration.between(now,originalBound).toNanos();
   if(remaining<=0) throw new Failure(Error.CONFLICT);
   long budget=Math.min(remaining,TimeUnit.SECONDS.toNanos(4));
   long started=System.nanoTime();
   var profile=call(HttpRequest.newBuilder(profileEndpoint)
     .header("X-Service-Key",readKey).header("X-Identity-Id",seed.identityId().toString()).GET(),started,budget,originalBound,seed.createdAt());
   if(!profile.keySet().equals(PROFILE_FIELDS)) throw new Failure(Error.UNAVAILABLE);
   UUID owner=LegacyMigrationOtp.uuid(profile.get("identity_id")),profileId=LegacyMigrationOtp.uuid(profile.get("id"));
   if(!owner.equals(seed.identityId())||!seed.phone().equals(profile.get("phone_number"))) throw new Failure(Error.CONFLICT);
   // Distinct fields are validated independently. Historical equality is not assumed or synthesized.
   if(!(profile.get("display_name") instanceof String name)||name.isBlank()||name.codePointCount(0,name.length())>100
     ||name.codePoints().anyMatch(Character::isISOControl)||!(profile.get("phone_verified") instanceof Boolean)) throw new Failure(Error.UNAVAILABLE);
   var request=new LinkedHashMap<String,Object>();
   request.put("protocol_version",1L);request.put("migration_id",seed.migrationId().toString());request.put("stage_operation_id",seed.stageOperationId().toString());
   request.put("command_id",seed.commandId().toString());request.put("decision_command_id",seed.decisionCommandId().toString());
   request.put("identity_id",seed.identityId().toString());request.put("profile_id",profileId.toString());request.put("canonical_phone",seed.phone());
   request.put("email_contact_version",seed.emailVersion());request.put("email_digest",seed.emailDigest());request.put("policy_version",seed.policyVersion());
   request.put("created_at",seed.createdAt().toEpochMilli());request.put("authorization_deadline",seed.intentDeadline().toEpochMilli());
   request.put("payload_hash",LegacyMigrationOtp.hash("armoney-user-migration-stage-v1",LegacyMigrationOtp.REQUEST.subList(0,LegacyMigrationOtp.REQUEST.size()-1),request));
   byte[] body=JSON.writeValueAsBytes(request);
   var ack=call(HttpRequest.newBuilder(stageEndpoint).header("Content-Type","application/json")
     .header("X-ArMoney-Migration-Stage-Key",stageKey).POST(HttpRequest.BodyPublishers.ofByteArray(body)),started,budget,originalBound,seed.createdAt());
   var exchange=new StageExchange(request,ack);
   LegacyMigrationOtp.validateStage(seed,exchange);
   if(elapsed(started)>=budget||!clock.instant().isBefore(originalBound)) throw new Failure(Error.UNAVAILABLE);
   return exchange;
  } catch(Failure e) { throw e; }
  catch(Exception e) { throw new Failure(Error.UNAVAILABLE); }
  finally { admission.release(); }
 }
 private static void validateSeed(Seed s) {
  if(s==null) throw new Failure(Error.BAD_INPUT);
  for(UUID id:Arrays.asList(s.migrationId(),s.stageOperationId(),s.commandId(),s.decisionCommandId(),s.identityId()))
   if(id==null||id.equals(new UUID(0,0))) throw new Failure(Error.BAD_INPUT);
  if(s.phone()==null||!s.phone().matches("\\+9715[024568][0-9]{7}")||s.emailVersion()<1||s.policyVersion()<1||s.createdAt()==null||s.intentDeadline()==null||s.accountDeadline()==null) throw new Failure(Error.BAD_INPUT);
  LegacyMigrationOtp.digest(s.emailDigest());
  try {
   long start=s.createdAt().toEpochMilli(),end=s.intentDeadline().toEpochMilli();
   if(start<0||end<=start||Math.subtractExact(end,start)>900000) throw new Failure(Error.BAD_INPUT);
  } catch(ArithmeticException e) { throw new Failure(Error.BAD_INPUT); }
 }
 private static long elapsed(long start) { long elapsed=System.nanoTime()-start; if(elapsed<0) throw new Failure(Error.UNAVAILABLE); return elapsed; }
 private Map<String,Object> call(HttpRequest.Builder builder,long started,long budget,Instant originalBound,Instant originalStart) {
  Instant now=clock.instant();
  if(now.isBefore(originalStart)) throw new Failure(Error.UNAVAILABLE);
  long remaining=Math.min(budget-elapsed(started),Duration.between(now,originalBound).toNanos());
  if(remaining<=0||closed) throw new Failure(Error.UNAVAILABLE);
  CompletableFuture<HttpResponse<byte[]>> pending=null;
  try {
   pending=http.sendAsync(builder.timeout(Duration.ofNanos(remaining)).build(),ignored->new BoundedBody());
   long wait=Math.min(budget-elapsed(started),Duration.between(clock.instant(),originalBound).toNanos());
   if(wait<=0) throw new Failure(Error.UNAVAILABLE);
   var response=pending.get(wait,TimeUnit.NANOSECONDS);
   if(response.statusCode()==409||response.statusCode()==423) throw new Failure(Error.CONFLICT);
   if(response.statusCode()==429) throw new Failure(Error.RATE_LIMITED);
   if(response.statusCode()!=200||response.headers().allValues("Content-Type").size()!=1
     ||!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json")) throw new Failure(Error.UNAVAILABLE);
   return parse(response.body());
  } catch(Failure e) { throw e; }
  catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(Error.UNAVAILABLE); }
  catch(Exception e) { throw new Failure(Error.UNAVAILABLE); }
  finally { if(pending!=null&&!pending.isDone()) pending.cancel(true); }
 }
 private static Map<String,Object> parse(byte[] bytes) {
  if(bytes.length>LIMIT) throw new Failure(Error.UNAVAILABLE);
  try(var parser=JSON.getFactory().createParser(bytes)) {
   while(parser.nextToken()!=null) if(parser.currentToken().isNumeric()) {
    if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT||!parser.getText().matches("0|[1-9][0-9]*")) throw new Failure(Error.UNAVAILABLE);
    parser.getLongValue();
   }
   Map<String,Object> result=JSON.readValue(bytes,new TypeReference<LinkedHashMap<String,Object>>(){});
   if(result==null) throw new Failure(Error.UNAVAILABLE);
   return result;
  } catch(Exception e) { throw new Failure(Error.UNAVAILABLE); }
 }
 /** Cancels body demand before retaining a byte beyond the fixed bound. */
 private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final CompletableFuture<byte[]> result=new CompletableFuture<>();
  private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  private Flow.Subscription subscription;
  @Override public CompletionStage<byte[]> getBody() { return result; }
  @Override public void onSubscribe(Flow.Subscription s) { if(subscription!=null) { s.cancel(); return; } subscription=s; s.request(1); }
  @Override public void onNext(List<ByteBuffer> items) {
   try {
    for(ByteBuffer item:items) {
     if(item.remaining()>LIMIT-bytes.size()) { subscription.cancel(); result.completeExceptionally(new IllegalStateException("User response bound")); return; }
     byte[] part=new byte[item.remaining()]; item.get(part); bytes.writeBytes(part);
    }
    subscription.request(1);
   } catch(RuntimeException e) { subscription.cancel(); result.completeExceptionally(new IllegalStateException("User response unavailable")); }
  }
  @Override public void onError(Throwable ignored) { result.completeExceptionally(new IllegalStateException("User response unavailable")); }
  @Override public void onComplete() { result.complete(bytes.toByteArray()); }
 }
 @Override public void close() {
  closed=true; http.shutdownNow();
  try { http.awaitTermination(Duration.ofSeconds(2)); }
  catch(InterruptedException e) { Thread.currentThread().interrupt(); }
 }
 @Override public String toString() { return "HttpLegacyMigrationStage[redacted]"; }
}
