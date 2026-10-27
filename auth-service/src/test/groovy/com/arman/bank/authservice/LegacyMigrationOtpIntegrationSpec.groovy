package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.LegacyMigrationOtp as O
import com.arman.bank.authservice.domain.Verification as V
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*

/** Real PG negative dependency/shared-budget tests. No synthetic positive User ACK/provider receipt/OTP consumption. */
class LegacyMigrationOtpIntegrationSpec extends Specification {
    PostgreSQLContainer postgres
    Database database
    LegacyMigrationCrypto accountCrypto
    LegacyMigrationOtpCrypto otpCrypto
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start();database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        accountCrypto=new LegacyMigrationCrypto('account',[account:([71]*32) as byte[]],([72]*32) as byte[])
        otpCrypto=new LegacyMigrationOtpCrypto('otp',[otp:([83]*32) as byte[]],[otp:([84]*32) as byte[]])
    }
    def cleanup(){database?.close();postgres?.stop()}
    long count(String table){database.transaction{sql->sql.fetchOne('select count(*) from '+table).get(0,Long)}}
    boolean available(){database.transaction{sql->PostgresVerificationProjectionStore.lockControls(sql);PostgresOtpIssuanceBudget.available(sql,sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant())}}
    def localAdmission(){
        def auth=new AuthService(new PostgresAuthStore(database),new Argon2Passwords(),Clock.systemUTC(),{id,phone->} as RegistrationProfiles)
        auth.register('legacy@example.test','synthetic long migration password','+971501234567')
        auth.beginLegacyMigration('legacy@example.test','synthetic long migration password',UUID.randomUUID(),new PostgresLegacyMigrationStore(database,accountCrypto,true))
    }
    def registration(String subject,String phone){
        def crypto=new VerificationCrypto('registration',[registration:([11]*32) as byte[]],[registration:([22]*32) as byte[]])
        def service=new VerificationService(new PostgresVerificationStore(database,crypto),['https://issuer.example/realm'] as Set)
        def binding=new V.Binding('https://issuer.example/realm',subject,'server-session')
        def enrolled=service.enroll(new V.EnrollmentCommand(UUID.randomUUID(),binding,phone,subject+'@example.test'))
        assert enrolled.error()==null
        [service:service,binding:binding,enrollment:enrolled.enrollmentId()]
    }
    def 'missing genuine current target and User stage adapters cannot create any stage OTP outbox or receipt'(){
        given:def admitted=localAdmission()
        def store=new PostgresLegacyMigrationOtpStore(database,accountCrypto,otpCrypto,'migration-auth',true,MigrationBindingSqlPort.unavailable())
        def service=new LegacyMigrationOtpService(store)
        when:service.stage(admitted.migrationId(),admitted.ownerCapability())
        then:def error=thrown(O.Failure);error.error()==O.Error.UNAVAILABLE
        ['legacy_migration_otp_stages','legacy_migration_otp_stage_acks','legacy_migration_otp_challenges','legacy_migration_otp_outbox','legacy_migration_otp_receipts'].every{count(it)==0}
        count('managed_registration_principals')==0
        when:service.issue(admitted.migrationId(),admitted.ownerCapability(),UUID.randomUUID(),O.Purpose.EMAIL)
        then:thrown(O.Failure);count('auth_otp_issuance_reservations')==0
    }
    def 'default off and forbidden recovery allocate nothing'(){
        given:def admitted=localAdmission();def service=new LegacyMigrationOtpService(new PostgresLegacyMigrationOtpStore(database,accountCrypto,otpCrypto,'migration-auth'))
        when:service.issue(admitted.migrationId(),admitted.ownerCapability(),UUID.randomUUID(),O.Purpose.PHONE)
        then:thrown(O.Failure);count('legacy_migration_otp_challenges')==0
        when:service.allocateRecovery(admitted.migrationId())
        then:thrown(O.Failure);count('legacy_migration_otp_stages')==0
    }
    def 'actual registration writer shares hourly reservations without reservation plus challenge double count'(){
        given:
        // Abuse-budget metadata only; these rows cannot authenticate anyone or create receipts.
        database.transaction{sql->sql.execute("insert into auth_otp_issuance_reservations select gen_random_uuid(),'MIGRATION',clock_timestamp() from generate_series(1,998)")}
        def first=registration('first-registration','+971541234567')
        when:def email=first.service.issue(new V.ChallengeCommand(UUID.randomUUID(),first.enrollment,first.binding,V.Purpose.REGISTRATION_EMAIL,1))
        then:email.error()==null;count('auth_otp_issuance_reservations')==999;count('verification_challenges')==1;available()
        when:def phone=first.service.issue(new V.ChallengeCommand(UUID.randomUUID(),first.enrollment,first.binding,V.Purpose.REGISTRATION_PHONE,1))
        then:phone.error()==null;count('auth_otp_issuance_reservations')==1000;count('verification_challenges')==2;!available()
        when:def second=registration('second-registration','+971561234567')
        second.service.issue(new V.ChallengeCommand(UUID.randomUUID(),second.enrollment,second.binding,V.Purpose.REGISTRATION_EMAIL,1))
        then:
        def denied=thrown(V.Failure)
        denied.error()==V.Error.RATE_LIMITED
        count('auth_otp_issuance_reservations')==1000
        count('verification_challenges')==2;count('verification_receipts')==0;count('legacy_migration_otp_receipts')==0
    }
    def 'lifetime reservation cap cannot be reset by deleting retained reservation metadata'(){
        given:database.transaction{sql->sql.execute("insert into auth_otp_issuance_reservations select gen_random_uuid(),'MIGRATION',clock_timestamp()-interval '2 hours' from generate_series(1,100000)")}
        expect:!available()
        when:database.transaction{sql->sql.execute('delete from auth_otp_issuance_reservations')}
        then:thrown(RuntimeException);count('auth_otp_issuance_reservations')==100000;!available()
    }
    def 'final original delivery lease deadline rolls back delayed transaction work while account and OTP bounds remain live'(){
        given:
        // Technical transaction probe only: no migration stage, ACK, challenge, receipt or provider-binding seed.
        database.transaction{sql->sql.execute('create table migration_finish_rollback_probe(id integer primary key, accepted boolean not null)');sql.execute('insert into migration_finish_rollback_probe values(1,false)')}
        Instant originalLeaseUntil
        Instant accountBound
        Instant otpBound
        when:
        database.transaction{sql->
            def started=sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant()
            originalLeaseUntil=started.plusMillis(100)
            accountBound=started.plusSeconds(120)
            otpBound=started.plusSeconds(300)
            sql.execute('update migration_finish_rollback_probe set accepted=true where id=1')
            sql.execute('select pg_sleep(0.2)')
            PostgresLegacyMigrationOtpStore.requireOriginalDeliveryLease(sql,originalLeaseUntil)
            null
        }
        then:
        def failure=thrown(O.Failure)
        failure.error()==O.Error.CONFLICT
        def result=database.transaction{sql->
            def now=sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant()
            [accepted:sql.fetchOne('select accepted from migration_finish_rollback_probe where id=1').get(0,Boolean),leaseExpired:!now.isBefore(originalLeaseUntil),accountLive:now.isBefore(accountBound),otpLive:now.isBefore(otpBound)]
        }
        !result.accepted
        result.leaseExpired
        result.accountLive
        result.otpLive
        count('legacy_migration_otp_receipts')==0
        count('legacy_migration_otp_outbox')==0
    }
}
