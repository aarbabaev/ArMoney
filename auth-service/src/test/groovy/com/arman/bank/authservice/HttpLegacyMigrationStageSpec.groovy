package com.arman.bank.authservice

import com.arman.bank.authservice.domain.LegacyMigrationOtp as O
import com.arman.bank.authservice.infrastructure.HttpLegacyMigrationStage
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.time.*
import java.util.concurrent.*

/** Synthetic HTTP counterpart/hash tests ONLY. No genuine account/provider proof or User PG acceptance. */
class HttpLegacyMigrationStageSpec extends Specification {
 static final String READ='synthetic-existing-user-read-key-01'
 static final String STAGE='synthetic-dedicated-user-stage-001'
 def json=new ObjectMapper()
 HttpServer server
 ExecutorService threads
 HttpLegacyMigrationStage adapter
 def requests=new CopyOnWriteArrayList<Map>()
 UUID profileId=UUID.randomUUID()
 Map profilePatch=[:]
 Closure ackPatch={ Map ack -> ack }
 int getStatus=200
 long getDelay=0
 O.Seed seed
 def setup() {
  def now=Instant.now()
  seed=new O.Seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
   '+971501234567',7L,'a'*64,1L,now,now.plusSeconds(900),now.plusSeconds(75))
  server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
  threads=Executors.newFixedThreadPool(2);server.executor=threads
  server.createContext('/') { exchange ->
   try {
    def raw=exchange.requestBody.bytes
    requests.add([method:exchange.requestMethod,path:exchange.requestURI.path,
     read:exchange.requestHeaders.getFirst('X-Service-Key'),owner:exchange.requestHeaders.getFirst('X-Identity-Id'),
     stage:exchange.requestHeaders.getFirst('X-ArMoney-Migration-Stage-Key'),body:raw])
    Object response
    int status=200
    if(exchange.requestURI.path=='/v1/users/me') {
     if(getDelay) Thread.sleep(getDelay)
     status=getStatus
     response=[id:profileId.toString(),identity_id:seed.identityId().toString(),display_name:'Synthetic existing',
      phone_number:seed.phone(),phone_verified:false]+profilePatch
    } else if(exchange.requestURI.path=='/v1/internal/migrations/legacy/user-stage') {
     def request=json.readValue(raw,Map)
     def ack=[protocol_version:1,migration_id:request.migration_id,stage_operation_id:request.stage_operation_id,
      identity_id:request.identity_id,profile_id:request.profile_id,baseline_generation:17L,contact_mutation_revision:0L,
      projection_present:false,projection_revision:null,phone_contact_version:null,verification_epoch:null,
      email_contact_version:request.email_contact_version,email_digest:request.email_digest,baseline_hash:'b'*64,
      authorization_deadline:request.authorization_deadline,payload_hash:request.payload_hash]
     ack.stage_ack_hash=O.hash('armoney-user-migration-stage-ack-v1',O.ACK[0..-2],ack)
     response=ackPatch.call(ack)
    } else { status=405;response=[error:'synthetic-unexpected-path'] }
    byte[] body=response instanceof String?response.bytes:json.writeValueAsBytes(response)
    exchange.responseHeaders.set('Content-Type','application/json')
    exchange.sendResponseHeaders(status,body.length)
    exchange.responseBody.write(body)
   } finally { exchange.close() }
  }
  server.start()
  adapter=new HttpLegacyMigrationStage(new URI("http://127.0.0.1:"+server.address.port),READ,STAGE)
 }
 def cleanup() { adapter?.close();server?.stop(0);threads?.shutdownNow();threads?.awaitTermination(3,TimeUnit.SECONDS) }
 def 'existing owner GET then exact dedicated stage preserves distinct profile ID original timestamps and full null baseline'() {
  when: def exchange=adapter.stage(seed)
  then:
  requests*.method==['GET','POST']
  requests*.path==['/v1/users/me','/v1/internal/migrations/legacy/user-stage']
  requests[0].read==READ && requests[0].owner==seed.identityId().toString() && requests[0].stage==null
  requests[0].body.length==0
  requests[1].read==null && requests[1].owner==null && requests[1].stage==STAGE
  exchange.request().profile_id==profileId.toString()
  exchange.request().profile_id!=seed.identityId().toString()
  exchange.request().created_at==seed.createdAt().toEpochMilli()
  exchange.request().authorization_deadline==seed.intentDeadline().toEpochMilli()
  exchange.acknowledgement().baseline_generation==17
  !exchange.acknowledgement().projection_present
  exchange.acknowledgement().phone_contact_version==null
  O.validateStage(seed,exchange)==null
  !adapter.toString().contains(READ)
 }
 def 'missing existing profile or changed owner phone and malformed profile never stage or provision'() {
  given: profilePatch=patch;getStatus=status
  when: adapter.stage(seed)
  then:
  thrown(O.Failure)
  requests.size()==1 && requests[0].method=='GET'
  where:
  status | patch
  404 | [:]
  200 | [identity_id:UUID.randomUUID().toString()]
  200 | [phone_number:'+971521234567']
  200 | [id:null]
  200 | [phone_verified:'false']
  200 | [unexpected:1]
 }
 def 'corrupted ACK hash rebound owner projection null misuse duplicate and exponent tokens fail closed'() {
  given:
  ackPatch={ Map ack -> transform.call(ack) }
  when: adapter.stage(seed)
  then: thrown(O.Failure)
  where:
  transform << [
   { a -> a+[stage_ack_hash:'0'*64] },
   { a -> a+[identity_id:UUID.randomUUID().toString()] },
   { a -> a+[phone_contact_version:1] },
   { a -> '{"protocol_version":1,"protocol_version":1}' },
   { a -> '{"protocol_version":1e0}' },
   { a -> '{"protocol_version":9223372036854775808}' },
   { a -> 'x'*12289 }
  ]
 }
 def 'expired original account bound denies before GET although original intent remains valid'() {
  given:
  def original=Instant.now().minusSeconds(121)
  seed=new O.Seed(seed.migrationId(),seed.stageOperationId(),seed.commandId(),seed.decisionCommandId(),seed.identityId(),
   seed.phone(),seed.emailVersion(),seed.emailDigest(),seed.policyVersion(),original,original.plusSeconds(900),original.plusSeconds(75))
  when: adapter.stage(seed)
  then:
  thrown(O.Failure)
  requests.empty
 }
 def 'slow GET consumes original shared budget and cannot gain fresh POST timeout'() {
  given:
  getDelay=700
  adapter.close()
  def now=Instant.now()
  seed=new O.Seed(seed.migrationId(),seed.stageOperationId(),seed.commandId(),seed.decisionCommandId(),seed.identityId(),
   seed.phone(),seed.emailVersion(),seed.emailDigest(),seed.policyVersion(),now.minusSeconds(10),now.plusSeconds(300),now.plusMillis(400))
  adapter=new HttpLegacyMigrationStage(new URI("http://127.0.0.1:"+server.address.port),READ,STAGE)
  when: adapter.stage(seed)
  then:
  thrown(O.Failure)
  requests.every { it.method=='GET' }
 }
 def 'fixed origin redirect response and role aliases do not stage'() {
  when: new HttpLegacyMigrationStage(new URI('http://127.0.0.1/untrusted'),READ,STAGE)
  then: thrown(IllegalArgumentException)
  when: new HttpLegacyMigrationStage(new URI('http://127.0.0.1'),READ,READ)
  then: thrown(IllegalArgumentException)
  when: new HttpLegacyMigrationStage(new URI('http://127.0.0.1'),READ,Base64.encoder.encodeToString(READ.bytes))
  then: thrown(IllegalArgumentException)
  when:
  getStatus=302;adapter.stage(seed)
  then:
  thrown(O.Failure)
  requests.size()==1
 }
}
