package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.LiveUserRevision
import spock.lang.Specification

class LiveUserRevisionSpec extends Specification {
    def 'current request exact correlation has no diagnostic clock authority'(){
        given:
        def operation=UUID.randomUUID();def owner=UUID.randomUUID();def migration=UUID.randomUUID();def decision=UUID.randomUUID()
        def request=LiveUserRevision.current('auth',operation,owner,migration,decision,1,2,3)
        def response=new LinkedHashMap(request)
        response.putAll([status:'CURRENT',current:[profile_id:UUID.randomUUID().toString(),shard_id:'primary',baseline_generation:0L,contact_mutation_revision:0L,projection_present:false,projection_revision:null,phone_contact_version:null,verification_epoch:null],observed_at_us:0L])
        expect:
        LiveUserRevision.currentResponse(request,response).current.projection_present==false
        when:
        response.auth_epoch=4L
        LiveUserRevision.currentResponse(request,response)
        then:
        thrown(IllegalArgumentException)
    }
    def 'strict codec rejects null projection mismatches extra fields and noncanonical integers'(){
        when:
        LiveUserRevision.user(input)
        then:
        thrown(IllegalArgumentException)
        where:
        input << [
            [profile_id:UUID.randomUUID().toString(),shard_id:'primary',baseline_generation:0L,contact_mutation_revision:0L,projection_present:false,projection_revision:1L,phone_contact_version:null,verification_epoch:null],
            [profile_id:UUID.randomUUID().toString(),shard_id:'primary',baseline_generation:0.0,contact_mutation_revision:0L,projection_present:false,projection_revision:null,phone_contact_version:null,verification_epoch:null]
        ]
    }
}
