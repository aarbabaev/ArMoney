package com.arman.bank.userservice
import com.arman.bank.userservice.domain.*
import com.arman.bank.userservice.infrastructure.LiveUserCurrentRoutes
import spock.lang.Specification

class LiveUserRevisionSpec extends Specification {
    static final String WRITER='synthetic-mutation'
    static VerificationProjection command(UUID owner=UUID.randomUUID(),int expected=0,String phone='+971501234567',int contact=1,long epoch=1,UUID operation=UUID.randomUUID()) {
        new VerificationProjection(operation,owner,expected,expected+1,phone,contact,epoch,VerificationProjection.hash(operation,owner,expected,expected+1,phone,contact,epoch))
    }
    static Map currentRequest(UUID owner,Map baseline) {
        def m=[protocol_version:1L,operation_id:UUID.randomUUID().toString(),identity_id:owner.toString(),migration_id:baseline.migration_id,
            decision_id:baseline.decision_id,classification_generation:1L,auth_epoch:1L,auth_source_revision:1L]
        m.payload_hash=LiveUserRevision.hash('current-request',LiveUserRevision.withWriter(WRITER,m))
        m
    }
    static Map negativeClassification(Map request) {
        // Synthetic port fixture only, never genuine Auth classification acceptance.
        [protocol_version:1L,operation_id:request.operation_id,identity_id:request.identity_id,payload_hash:request.payload_hash,outcome:'NOT_MIG',binding:null,recorded_at_us:1L]
    }
    def 'canonical hash is independent from JSON order and distinguishes null from object'(){
        given:def c=command();def request=LiveUserRevision.suspension(WRITER,c.operationId(),c.identityId(),null,null,LiveUserRevision.projection(c))
        def reversed=new LinkedHashMap(request.entrySet().toList().reverse().collectEntries{[(it.key):it.value]})
        when:def parsed=LiveUserRevision.suspensionRequest(WRITER,LiveUserCurrentRoutes.parse(LiveUserCurrentRoutes.encode(reversed)))
        then:parsed==request
        LiveUserRevision.hash('test',[before:null])!=LiveUserRevision.hash('test',[before:[:]])
        LiveUserRevision.hash('test',[before:false])!=LiveUserRevision.hash('test',[before:0L])
        when:parsed.put('before',[:])
        then:thrown(UnsupportedOperationException)
    }
    def 'strict numeric token parser rejects fraction exponent duplicate trailing and overflow'(){
        when:LiveUserCurrentRoutes.parse(value.bytes)
        then:thrown(IllegalArgumentException)
        where:value << ['{"n":1.0}','{"n":1e0}','{"n":true,"n":false}','{"n":1} {}','{"n":9223372036854775808}','{"n":-0}']
    }
    def 'null bootstrap cannot receive suspended or malformed source outcome'(){
        given:def c=command();def r=LiveUserRevision.suspension(WRITER,c.operationId(),c.identityId(),null,null,LiveUserRevision.projection(c))
        expect:LiveUserRevision.suspensionResponse(r,negativeClassification(r)).outcome=='NOT_MIG'
        when:LiveUserRevision.suspensionResponse(r,negativeClassification(r)+[outcome:'SUSPENDED'])
        then:thrown(IllegalArgumentException)
        when:LiveUserRevision.suspensionResponse(r,negativeClassification(r)+[identity_id:UUID.randomUUID().toString()])
        then:thrown(IllegalArgumentException)
    }
    def 'absent projection requires an entirely null triple and nonnegative counters'(){
        given:def u=[profile_id:UUID.randomUUID().toString(),shard_id:'primary',baseline_generation:0L,contact_mutation_revision:0L,projection_present:false,projection_revision:null,phone_contact_version:null,verification_epoch:null]
        expect:LiveUserRevision.user(u)==u
        when:LiveUserRevision.user(u+[phone_contact_version:1L])
        then:thrown(IllegalArgumentException)
        when:LiveUserRevision.user(u+[baseline_generation:-1L])
        then:thrown(IllegalArgumentException)
    }
}
