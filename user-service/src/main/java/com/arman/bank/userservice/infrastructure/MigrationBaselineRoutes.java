package com.arman.bank.userservice.infrastructure;
import com.arman.bank.userservice.application.MigrationBaselineStore;
import com.arman.bank.userservice.domain.MigrationBaseline;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
/** Private purpose-specific HTTP port, explicitly excluded from ordinary identity authentication. */
public final class MigrationBaselineRoutes {
 public static final String PREFIX="/v1/internal/migrations/legacy/";
 public static final String STAGE_HEADER="X-ArMoney-Migration-Stage-Key",PUBLICATION_HEADER="X-ArMoney-Migration-Publication-Key";
 private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 private final MigrationBaselineConfiguration configuration;
 private final MigrationBaselineStore store;
 private final Semaphore concurrency=new Semaphore(16);
 private final AtomicInteger budget=new AtomicInteger();
 private volatile long window=System.nanoTime();
 public MigrationBaselineRoutes(MigrationBaselineStore store,MigrationBaselineConfiguration configuration) { this.store=Objects.requireNonNull(store); this.configuration=Objects.requireNonNull(configuration); }
 public void configure(JavalinConfig config) {
  if(!configuration.enabled()) return;
  for(String action:List.of("user-stage","user-publish","user-publication-result","user-resolve"))
   config.routes.post(PREFIX+action,ctx->handle(ctx,action));
 }
 public static Map<String,Object> parse(byte[] bytes) {
  if(bytes.length>12288) throw new IllegalArgumentException();
  // Streaming parser rejects floating/exponent tokens and out-of-range integers before mapping.
  try(var parser=JSON.getFactory().createParser(bytes)) {
   while(parser.nextToken()!=null) if(parser.currentToken().isNumeric()) {
    if(!parser.currentToken().equals(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)
      ||!parser.getText().matches("0|[1-9][0-9]*")) throw new IllegalArgumentException();
    parser.getLongValue();
   }
   return JSON.readValue(bytes,new TypeReference<LinkedHashMap<String,Object>>(){});
  } catch(Exception e) { throw new IllegalArgumentException("Malformed migration command"); }
 }
 private synchronized boolean admit() {
  long now=System.nanoTime(); if(now-window>=60_000_000_000L) { window=now; budget.set(0); }
  return budget.incrementAndGet()<=120;
 }
 private String authenticate(Context ctx,boolean stage) {
  String header=stage?STAGE_HEADER:PUBLICATION_HEADER;
  if(Collections.list(ctx.req().getHeaders(header)).size()!=1) throw new MigrationBaselineStore.Rejected(401);
  String supplied=ctx.header(header); if(supplied==null||!supplied.matches("[!-~]{32,256}")) throw new MigrationBaselineStore.Rejected(401);
  String matched=null; byte[] input=supplied.getBytes(StandardCharsets.US_ASCII);
  for(var e:(stage?configuration.stageWriters():configuration.publicationWriters()).entrySet())
   for(String key:e.getValue()) if(MessageDigest.isEqual(input,key.getBytes(StandardCharsets.US_ASCII))) matched=e.getKey();
  if(matched==null) throw new MigrationBaselineStore.Rejected(401); return matched;
 }
 private void handle(Context ctx,String action) {
  boolean acquired=false; ctx.header("Cache-Control","no-store");
  try {
   boolean stage=action.equals("user-stage"); String writer=authenticate(ctx,stage);
   acquired=concurrency.tryAcquire(); if(!acquired||!admit()) throw new MigrationBaselineStore.Rejected(429);
   if(ctx.contentType()==null||!ctx.contentType().split(";",2)[0].strip().equalsIgnoreCase("application/json")) throw new IllegalArgumentException();
   var body=parse(ctx.req().getInputStream().readNBytes(12289));
   var c=stage?MigrationBaseline.stage(body):MigrationBaseline.tuple(body);
   var result=switch(action) { case "user-stage"->store.stage(writer,c); case "user-publish"->store.publish(writer,c); case "user-publication-result"->store.result(writer,c); default->store.resolve(writer,c); };
   reply(ctx,200,result);
  } catch(MigrationBaselineStore.Rejected e) { if(e.status()==429) ctx.header("Retry-After","60"); reply(ctx,e.status(),Map.of("error",e.status()==503?"unavailable":"rejected")); }
  catch(IllegalArgumentException|ArithmeticException e) { reply(ctx,400,Map.of("error","malformed")); }
  catch(Exception e) { reply(ctx,503,Map.of("error","unavailable")); }
  finally { if(acquired) concurrency.release(); }
 }
 private static void reply(Context ctx,int status,Object body) {
  try { byte[] bytes=JSON.writeValueAsBytes(body); if(bytes.length>12288) throw new IllegalStateException(); ctx.status(status).contentType("application/json").result(bytes); }
  catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Migration serialization unavailable"); }
 }
}
