package com.arman.bank.userservice
import com.arman.bank.userservice.domain.*
import com.arman.bank.userservice.infrastructure.*
import spock.lang.Specification

class MigrationBaselineSpec extends Specification {
 static Map stage(UUID owner=UUID.randomUUID(),UUID profile=UUID.randomUUID()) {
  long now=System.currentTimeMillis()
  def m=[protocol_version:1,migration_id:UUID.randomUUID().toString(),stage_operation_id:UUID.randomUUID().toString(),
   command_id:UUID.randomUUID().toString(),decision_command_id:UUID.randomUUID().toString(),identity_id:owner.toString(),
   profile_id:profile.toString(),canonical_phone:'+971501234567',email_contact_version:7L,email_digest:'a'*64,
   policy_version:1,created_at:now,authorization_deadline:now+900000]
  m.payload_hash=MigrationBaseline.hash('armoney-user-migration-stage-v1',MigrationBaseline.STAGE[0..-2],m)
  m
 }
 def 'stage hashes every immutable field and redacts diagnostics'() {
  given: def m=stage()
  expect:
  MigrationBaseline.stage(m).text('payload_hash')==m.payload_hash
  !MigrationBaseline.stage(m).toString().contains(m.canonical_phone)
  when: MigrationBaseline.stage(m+[email_contact_version:8L])
  then: thrown(IllegalArgumentException)
 }
 def 'zero UUID unknown field extra deadline and incorrect integer tokens reject'() {
  expect:
  [stage()+[identity_id:new UUID(0,0).toString()],stage()+[extra:1],
   stage()+[protocol_version:1.0],stage()+[authorization_deadline:Long.MAX_VALUE]].every {
   try { MigrationBaseline.stage(it); false } catch(IllegalArgumentException|ArithmeticException ignored) { true }
  }
 }
 def 'strict HTTP parser rejects exponent duplicate trailing and overflow tokens'() {
  expect:
  ['{"n":1e0}','{"n":1,"n":2}','{"n":9223372036854775808}','{"n":1} {}','{"n":-0}'].every {
   try { MigrationBaselineRoutes.parse(it.bytes); false } catch(IllegalArgumentException ignored) { true }
  }
 }
 def 'configuration is off by default and same writer rotates with distinct purpose keys'() {
  expect: !MigrationBaselineConfiguration.from([:]).enabled()
  when:
  def c=new MigrationBaselineConfiguration(true,['auth':['a'*32,'b'*32]],['auth':['c'*32]],new URI('http://auth-primary:8080'),'d'*32)
  then: c.stageWriters().auth.size()==2
  !c.toString().contains('a'*32)
  when: new MigrationBaselineConfiguration(true,['auth':['a'*32]],['auth':['a'*32]],new URI('http://auth-primary:8080'),'d'*32)
  then: thrown(IllegalArgumentException)
 }
}
