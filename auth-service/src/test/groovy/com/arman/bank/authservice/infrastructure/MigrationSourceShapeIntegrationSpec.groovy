package com.arman.bank.authservice.infrastructure

import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification
import java.sql.DriverManager
import java.sql.SQLException

/** Malformed provenance only: no positive authority, receipt, ACK or session is seeded. */
class MigrationSourceShapeIntegrationSpec extends Specification {
    @Shared PostgreSQLContainer postgres=new PostgreSQLContainer('postgres:17.6-alpine')
    def setupSpec(){postgres.start();Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations('classpath:db/migration').load().migrate()}
    def cleanupSpec(){postgres.stop()}

    def 'every migration source shape rejects missing and foreign decision without SQL UNKNOWN permission'(){
        given:
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        def expression=sql.fetchOne('select pg_get_constraintdef(oid) from pg_constraint where conname=?',table+'_source_shape').get(0,String)
        assert expression.startsWith('CHECK (')
        def predicate=expression.substring(6)
        def selected=UUID.randomUUID()
        def decision=missing?null:UUID.randomUUID()
        when:
        def result=sql.fetchOne('''select '''+predicate+''' from (select 'LEGACY_MIGRATION_DUAL_CONTACT'::varchar as eligibility_source,
            cast(? as uuid) as eligibility_source_id,cast(? as uuid) as migration_decision_id,1::bigint as classification_generation,
            1::bigint as source_revision,decode(repeat('00',32),'hex') as source_stamp,
            1::bigint as migration_email_receipt_generation,1::bigint as migration_phone_receipt_generation,
            null::bigint as migration_verified_contact_generation) candidate''',selected,decision).get(0,Boolean)
        then:
        result==Boolean.FALSE
        cleanup:
        connection.close()
        where:
        [table,missing] << [['sso_login_events','managed_banking_operations','managed_banking_sessions','sso_code_confirmation_anchors','sso_code_confirmation_results'],[true,false]].combinations()
    }
    def 'malformed native event INSERT is rejected by its source check before it can persist'(){
        given:
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def statement=connection.prepareStatement('''insert into sso_login_events(issuer,event_id,binding_hash,authentication_hash,identity_id,identity_digest,password_authenticated_at,verified_email,recipient_digest,email_contact_version,phone_contact_version,verification_epoch,projection_revision,recipient_expires_at,created_at,retained_until,
            eligibility_source,eligibility_source_id,classification_generation,source_revision,source_stamp,migration_decision_id,migration_email_receipt_generation,migration_phone_receipt_generation)
            values('https://issuer.invalid',?,decode(repeat('00',32),'hex'),decode(repeat('00',32),'hex'),null,decode(repeat('00',32),'hex'),current_timestamp,'synthetic@example.invalid',decode(repeat('00',32),'hex'),null,null,1,null,current_timestamp+interval '24 hours',current_timestamp,current_timestamp+interval '7 days',
            'LEGACY_MIGRATION_DUAL_CONTACT',?,1,1,decode(repeat('00',32),'hex'),?,1,1)''')
        statement.setObject(1,UUID.randomUUID());statement.setObject(2,UUID.randomUUID());statement.setObject(3,missing?null:UUID.randomUUID())
        when:
        statement.executeUpdate()
        then:
        def failure=thrown(SQLException)
        failure.SQLState=='23514'
        cleanup:
        statement?.close();connection?.close()
        where:
        missing << [true,false]
    }
}
