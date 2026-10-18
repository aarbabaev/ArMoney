package com.arman.bank.authservice

import com.arman.bank.authservice.domain.LegacyMigrationOtp as O
import com.arman.bank.authservice.application.*
import spock.lang.Specification
import java.time.Instant

/** Structural U2 hash tests only. These maps are NOT authenticated User ACK or provider evidence. */
class LegacyMigrationOtpSpec extends Specification {
    static def seed(){new O.Seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),'+971501234567',1L,'a'*64,1L,Instant.EPOCH,Instant.EPOCH.plusSeconds(900),Instant.EPOCH.plusSeconds(75))}
    static def exchange(O.Seed s,boolean present=false){
        def request=[protocol_version:1L,migration_id:s.migrationId().toString(),stage_operation_id:s.stageOperationId().toString(),command_id:s.commandId().toString(),decision_command_id:s.decisionCommandId().toString(),identity_id:s.identityId().toString(),profile_id:UUID.randomUUID().toString(),canonical_phone:s.phone(),email_contact_version:s.emailVersion(),email_digest:s.emailDigest(),policy_version:s.policyVersion(),created_at:s.createdAt().toEpochMilli(),authorization_deadline:s.intentDeadline().toEpochMilli()]
        request.payload_hash=O.hash('armoney-user-migration-stage-v1',O.REQUEST[0..-2],request)
        def ack=[protocol_version:1L,migration_id:request.migration_id,stage_operation_id:request.stage_operation_id,identity_id:request.identity_id,profile_id:request.profile_id,baseline_generation:1L,contact_mutation_revision:0L,projection_present:present,projection_revision:present?2L:null,phone_contact_version:present?3L:null,verification_epoch:present?4L:null,email_contact_version:s.emailVersion(),email_digest:s.emailDigest(),baseline_hash:'b'*64,authorization_deadline:request.authorization_deadline,payload_hash:request.payload_hash]
        ack.stage_ack_hash=O.hash('armoney-user-migration-stage-ack-v1',O.ACK[0..-2],ack)
        new O.StageExchange(request,ack)
    }
    def 'absent projection preserves null triple and zero counter while present keeps actual positive versions'(){
        given:def s=seed()
        expect:O.validateStage(s,exchange(s,present))==null
        where:present << [false,true]
    }
    def 'each changed bound stage ACK field fails even when attacker recomputes public hash'(){
        given:def s=seed();def original=exchange(s);def ack=new LinkedHashMap(original.acknowledgement())
        ack[field]=value;ack.stage_ack_hash=O.hash('armoney-user-migration-stage-ack-v1',O.ACK[0..-2],ack)
        when:O.validateStage(s,new O.StageExchange(original.request(),ack))
        then:thrown(O.Failure)
        where:
        field | value
        'identity_id' | UUID.randomUUID().toString()
        'email_contact_version' | 2L
        'email_digest' | 'c'*64
        'contact_mutation_revision' | -1L
        'baseline_generation' | 0L
        'projection_revision' | 1L
        'projection_present' | true
    }
    def 'recovery allocation and missing genuine User stage adapter are unavailable without store effects'(){
        given:def store=Mock(LegacyMigrationOtpStore);def service=new LegacyMigrationOtpService(store)
        when:service.allocateRecovery(UUID.randomUUID())
        then:def failure=thrown(O.Failure);failure.error()==O.Error.UNAVAILABLE;0 * store._
        when:LegacyMigrationOtpStore.unavailableStage().stage(seed())
        then:thrown(O.Failure)
    }
    def 'stage seed preserves shorter original account deadline separately from intent and rejects invalid bounds'(){
        given:def original=seed()
        expect:
        original.createdAt()==Instant.EPOCH
        original.intentDeadline()==Instant.EPOCH.plusSeconds(900)
        original.accountDeadline()==Instant.EPOCH.plusSeconds(75)
        when:new O.Seed(original.migrationId(),original.stageOperationId(),original.commandId(),original.decisionCommandId(),original.identityId(),original.phone(),original.emailVersion(),original.emailDigest(),original.policyVersion(),original.createdAt(),original.intentDeadline(),deadline)
        then:thrown(O.Failure)
        where:deadline << [null,Instant.EPOCH,Instant.EPOCH.minusSeconds(1),Instant.EPOCH.plusSeconds(121)]
    }
}
