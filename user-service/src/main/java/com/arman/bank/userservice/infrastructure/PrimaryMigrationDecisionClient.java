package com.arman.bank.userservice.infrastructure;
import com.arman.bank.userservice.application.*;
import com.arman.bank.userservice.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
/** Fixed configured PRIMARY peer. Redirects and ordinary session credentials are forbidden. */
public final class PrimaryMigrationDecisionClient implements MigrationDecisionPort,MigrationPublicationEvidencePort,AutoCloseable {
 private final MigrationBaselineConfiguration configuration;
 private final HttpClient http;
 private final Semaphore calls=new Semaphore(8);
 public PrimaryMigrationDecisionClient(MigrationBaselineConfiguration configuration) {
  if(!configuration.enabled()) throw new IllegalArgumentException("Migration disabled");
  this.configuration=configuration; http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 }
 private Map<String,Object> call(String route,Map<String,Object> body) {
  if(!calls.tryAcquire()) throw new MigrationBaselineStore.Rejected(503);
  CompletableFuture<HttpResponse<byte[]>> future=null;
  try {
   var request=HttpRequest.newBuilder(configuration.authPrimary().resolve(MigrationBaselineRoutes.PREFIX+route))
    .timeout(Duration.ofSeconds(3)).header("Content-Type","application/json").header("X-ArMoney-Migration-Decision-Key",configuration.decisionKey())
    .POST(HttpRequest.BodyPublishers.ofByteArray(new ObjectMapper().writeValueAsBytes(body))).build();
   future=http.sendAsync(request,info->HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofInputStream(),stream->{
    try(stream) { byte[] bytes=stream.readNBytes(12289); if(bytes.length>12288) throw new IllegalStateException(); return bytes; }
    catch(java.io.IOException e) { throw new IllegalStateException("Migration peer unavailable"); }
   }));
   var response=future.get(4,TimeUnit.SECONDS);
   if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json")) throw new IllegalStateException();
   return MigrationBaselineRoutes.parse(response.body());
  } catch(Exception e) { if(e instanceof InterruptedException) Thread.currentThread().interrupt(); if(future!=null) future.cancel(true); throw new MigrationBaselineStore.Rejected(503); }
  finally { calls.release(); }
 }
 @Override public Map<String,Object> decision(MigrationBaseline.Value stage) {
  return call("decision",Map.of("migration_id",stage.text("migration_id"),"stage_operation_id",stage.text("stage_operation_id"),"command_id",stage.text("decision_command_id"),"payload_hash",stage.text("payload_hash")));
 }
 @Override public MigrationPublicationEvidence evidence(MigrationBaseline.Value stage,MigrationBaseline.Value command) {
  return new MigrationPublicationEvidence(call("publication-evidence",Map.of("migration_id",stage.text("migration_id"),"decision_id",command.text("decision_id"),"stage_operation_id",stage.text("stage_operation_id"),"command_id",stage.text("decision_command_id"),"payload_hash",stage.text("payload_hash"))));
 }
 @Override public void close() {
  http.shutdownNow();
  try { http.awaitTermination(Duration.ofSeconds(2)); }
  catch(InterruptedException e) { Thread.currentThread().interrupt(); }
 }
}
