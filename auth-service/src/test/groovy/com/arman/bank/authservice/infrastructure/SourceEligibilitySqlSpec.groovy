package com.arman.bank.authservice.infrastructure

import org.jooq.Record
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import spock.lang.Specification
import java.time.Instant
import java.time.OffsetDateTime

/** Pure codec vectors, not database or final authority acceptance. */
class SourceEligibilitySqlSpec extends Specification {
    def 'fixed migration vector includes all opposite source NONE slots and absent projection triple'() {
        expect:
        HexFormat.of().formatHex(stamp(authority())) == '281221bb89916ec3b436780c05783a73b1bd955cfc697c30a5fbb7c0142cb3a5'
    }
    def 'submicrosecond timestamp changes do not reinterpret canonical source bytes'() {
        expect:
        Arrays.equals(stamp(authority(),Instant.parse('2026-10-18T00:00:00.123456001Z')),stamp(authority(),Instant.parse('2026-10-18T00:00:00.123456999Z')))
    }
    def 'security successor changes current stamp while retained initial provenance is outside codec'() {
        given:
        def initial=authority()
        def suspended=authority()+[state:'SUSPENDED',verification_epoch:2L,source_revision:2L]
        expect:
        !Arrays.equals(stamp(initial),stamp(suspended))
        Arrays.equals(stamp(initial),stamp(initial+[immutable_provenance_hash:new byte[32]]))
    }
    def 'source id and owner namespaces cannot share a stamp at identical numeric epochs'() {
        expect:
        !Arrays.equals(stamp(authority()),stamp(authority()+[decision_id:id(99)]))
        !Arrays.equals(stamp(authority()),stamp(authority()+[identity_id:id(99)]))
    }
    def 'UTC driver offset and Instant normalize to identical microsecond value'() {
        expect:
        Arrays.equals(stamp(authority(),Instant.parse('2026-10-18T00:00:00.123456Z')),stamp(authority(),OffsetDateTime.parse('2026-10-18T04:00:00.123456+04:00')))
    }
    private static byte[] stamp(Map authority,Object created=Instant.parse('2026-10-18T00:00:00.123456Z')) {
        SourceEligibilitySql.stamp(row(authority),[policy_version:1L,payload_hash:'e'*64],row([created_at:created]),receipt(10),receipt(20),null)
    }
    private static Record receipt(int n){row([candidate_id:id(n+1),challenge_id:id(n+2),id:id(n+3),receipt_generation:1L])}
    private static UUID id(int n){UUID.fromString(String.format('00000000-0000-0000-0000-%012d',n))}
    private static Record row(Map values) {
        Field[] fields=values.keySet().collect{DSL.field(DSL.name((String)it),Object)} as Field[]
        Record record=DSL.using(SQLDialect.POSTGRES).newRecord(fields)
        record.fromMap(values)
        record
    }
    private static Map authority(){[
        identity_id:id(1),decision_id:id(2),migration_id:id(3),issuer:'https://issuer.invalid/realms/test',subject:'synthetic-subject',
        classification_generation:1L,source_revision:1L,verification_epoch:1L,state:'ACTIVE',canonical_email:'synthetic@example.invalid',canonical_phone:'+971501234567',email_source:'CREDENTIAL_EMAIL',
        original_owner_generation:0L,post_cutover_owner_generation:1L,original_mode_generation:0L,current_mode_generation:0L,association_generation:0L,
        email_baseline_generation:1L,email_baseline_digest:'a'*64,candidate_id:null,candidate_version:null,candidate_digest:null,contact_transition_hash:null,verified_contact_generation:null,
        user_baseline_generation:1L,user_contact_mutation_revision:0L,projection_present:false,user_projection_revision:null,user_phone_contact_version:null,user_verification_epoch:null,
        stage_operation_id:id(4),stage_ack_hash:'b'*64,final_ack_hash:'c'*64,publication_revision:1L,receipt_snapshot_hash:'d'*64,policy_version:1L
    ]}
}
