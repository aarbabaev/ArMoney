package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.LegacyMigrationOtp
import com.arman.bank.authservice.domain.LegacyMigrationFinalization as F
import spock.lang.Specification

/** Synthetic wire vectors only; no positive account/final authority. */
class MigrationFinalAckSpec extends Specification {
    static Map vector(){
        def journal=[migration_id:UUID.randomUUID(),decision_id:UUID.randomUUID(),stage_operation_id:UUID.randomUUID(),publication_command_id:UUID.randomUUID(),identity_id:UUID.randomUUID(),baseline_hash:'a'*64,stage_ack_hash:'b'*64,email_contact_version:1L,email_digest:'e'*64,email_receipt_id:UUID.randomUUID(),email_receipt_generation:1L,phone_receipt_id:UUID.randomUUID(),phone_receipt_generation:1L,receipt_snapshot_hash:'c'*64,payload_hash:'d'*64]
        def stage=[profile_id:UUID.randomUUID().toString(),baseline_generation:1L,contact_mutation_revision:0L]
        ['migration_id','stage_operation_id','identity_id','payload_hash','baseline_hash','stage_ack_hash','email_contact_version','email_digest'].each{k->stage[k]=journal[k] instanceof UUID?journal[k].toString():journal[k]}
        def ack=new LinkedHashMap()
        PostgresMigrationFinalAckStore.FINAL.each{k->ack[k]=(k=='command_id'?journal.publication_command_id:(journal.containsKey(k)?journal[k]:stage[k]))}
        ack.protocol_version=1L;ack.publication_revision=1L
        ack.replaceAll{k,v->v instanceof UUID?v.toString():v}
        ack.final_ack_hash=LegacyMigrationOtp.hash('armoney-user-migration-final-ack-v1',PostgresMigrationFinalAckStore.FINAL[0..-2],ack)
        [journal:journal,stage:stage,ack:ack]
    }
    def 'strict exact twenty field ACK preserves candidate namespaces and immutable original owner pair'(){
        given:def v=vector()
        when:def decoded=PostgresMigrationFinalAckStore.validateAck(v.ack,v.journal,v.stage)
        then:decoded.keySet().toList()==PostgresMigrationFinalAckStore.FINAL
        decoded.contact_mutation_revision==0L
        decoded.publication_revision==1L
        when:decoded.clear()
        then:thrown(UnsupportedOperationException)
    }
    def 'foreign or malformed ACK cannot become final evidence even with recomputed User hash'(){
        given:
        def v=vector();v.ack[field]=value
        v.ack.final_ack_hash=LegacyMigrationOtp.hash('armoney-user-migration-final-ack-v1',PostgresMigrationFinalAckStore.FINAL[0..-2],v.ack)
        when:PostgresMigrationFinalAckStore.validateAck(v.ack,v.journal,v.stage)
        then:thrown(F.Failure)
        where:
        field | value
        'identity_id' | '00000000-0000-0000-0000-000000000001'
        'command_id' | '00000000-0000-0000-0000-000000000002'
        'profile_id' | '00000000-0000-0000-0000-000000000003'
        'baseline_generation' | 2L
        'publication_revision' | 2L
        'protocol_version' | true
        'contact_mutation_revision' | -1L
        'email_contact_version' | 1.0d
        'phone_receipt_generation' | 0L
        'stage_ack_hash' | ('e'*64)
        'receipt_snapshot_hash' | ('f'*64)
    }
    def 'extra fields or tampered User hash are rejected'(){
        given:def v=vector();v.ack[field]=value
        when:PostgresMigrationFinalAckStore.validateAck(v.ack,v.journal,v.stage)
        then:thrown(F.Failure)
        where:
        field | value
        'extra' | 'untrusted-input-never-authority'
        'final_ack_hash' | ('0'*64)
    }
    def 'COMPLETED is closed technical read data and leaves original receipt hash unchanged'(){
        given:
        def fields=LegacyMigrationFinalizationSpec.evidence()
        fields.receipt_snapshot_hash=F.receiptHash(fields)
        def originalHash=fields.receipt_snapshot_hash
        fields.state='COMPLETED'
        expect:
        new F.Evidence(fields).fields().receipt_snapshot_hash==originalHash
        new F.Decision([decision_id:fields.decision_id,migration_id:fields.migration_id,stage_operation_id:fields.stage_operation_id,payload_hash:fields.payload_hash,state:'COMPLETED']).fields().state=='COMPLETED'
        F.STATE=='PUBLICATION_AUTHORIZED'
    }
    def 'ACK provenance frozen order uses original retained key and microsecond timestamp after rotation'(){
        given:
        def v=vector();v.journal.journal_hash='f'*64;v.journal.retention_key_id='old'
        byte[] old=([73]*32) as byte[];byte[] current=([74]*32) as byte[]
        def crypto=new LegacyMigrationManagedProofCrypto('current',[old:old,current:current])
        String encoded=groovy.json.JsonOutput.toJson(v.ack)
        def at=java.time.Instant.parse('2026-10-18T01:02:03.123456789Z')
        def fields=['armoney-legacy-migration-final-ack-provenance-v1','1',v.journal.journal_hash,v.journal.decision_id.toString(),v.journal.identity_id.toString(),v.journal.migration_id.toString(),v.journal.stage_operation_id.toString(),v.journal.publication_command_id.toString(),v.journal.payload_hash,v.journal.stage_ack_hash,v.journal.baseline_hash,v.journal.receipt_snapshot_hash,v.ack.final_ack_hash,'1',encoded,'2026-10-18T01:02:03.123456Z']
        def mac=javax.crypto.Mac.getInstance('HmacSHA256');mac.init(new javax.crypto.spec.SecretKeySpec(old,'HmacSHA256'))
        String expected=HexFormat.of().formatHex(mac.doFinal(LegacyMigrationOtp.tuple(fields as String[])))
        def method=PostgresMigrationFinalAckStore.getDeclaredMethod('ackHash',LegacyMigrationManagedProofCrypto,Map,Map,String,java.time.Instant);method.accessible=true
        expect:method.invoke(null,crypto,v.journal,v.ack,encoded,at)==expected
        when:
        mac.init(new javax.crypto.spec.SecretKeySpec(current,'HmacSHA256'))
        then:HexFormat.of().formatHex(mac.doFinal(LegacyMigrationOtp.tuple(fields as String[])))!=expected
    }
}
