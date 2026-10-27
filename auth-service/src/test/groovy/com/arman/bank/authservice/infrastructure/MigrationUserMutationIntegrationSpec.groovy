package com.arman.bank.authservice.infrastructure

import com.arman.bank.runtime.Database
import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.domain.VerificationProjection as P
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.sun.net.httpserver.HttpServer
import java.time.*
import java.util.concurrent.*
import org.jooq.exception.DataAccessException
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification

/** Real V21 custody plus genuine Auth registration OTP issue/transport/consume classification.
 * Provider observations and delivery receivers are explicitly synthetic; no genuine Keycloak/SMTP/SMS carrier
 * or User mutation/current protocol acceptance is claimed. Direct metadata fixtures test constraints only. */
class MigrationUserMutationIntegrationSpec extends Specification {
    PostgreSQLContainer pg
    Database database
    UUID identityId
    def setup(){pg=new PostgreSQLContainer('postgres:17.6-alpine');pg.start();database=new Database(pg.jdbcUrl,pg.username,pg.password);identityId=UUID.randomUUID();database.transaction{sql->sql.execute('insert into identities(id) values(?)',identityId)}}
    def cleanup(){database?.close();pg?.stop()}
    def 'NOT_MIG custody requires exact null source shape and remains immutable against update and delete'(){
        given:
        def operation=UUID.randomUUID()
        database.transaction{sql->sql.execute("insert into auth_user_mutation_receipts(writer_id,operation_id,identity_id,request_hash,canonical_request,outcome,response_json,recorded_at) values('user',?,?,?,'{}','NOT_MIG','{}',clock_timestamp())",operation,identityId,'a'*64)}
        when:
        database.transaction{sql->sql.execute(statement,operation)}
        then:
        thrown(DataAccessException)
        database.transaction{sql->sql.fetchOne('select count(*) from auth_user_mutation_receipts where operation_id=?',operation).get(0,Long)}==1L
        where:
        statement << [
            'update auth_user_mutation_receipts set response_json=response_json where operation_id=?',
            "update auth_user_mutation_receipts set outcome='SUSPENDED' where operation_id=?",
            'delete from auth_user_mutation_receipts where operation_id=?'
        ]
    }
    def 'MIG receipt cannot exploit nullable check UNKNOWN to omit decision or authority provenance'(){
        when:
        database.transaction{sql->sql.execute("insert into auth_user_mutation_receipts(writer_id,operation_id,identity_id,request_hash,canonical_request,outcome,response_json,recorded_at,source_kind) values('user',?,?,?,'{}','SUSPENDED','{}',clock_timestamp(),'MIG')",UUID.randomUUID(),identityId,'a'*64)}
        then:
        thrown(DataAccessException)
        database.transaction{sql->sql.fetchOne('select count(*) from auth_user_mutation_receipts').get(0,Long)}==0L
    }
    def 'full retained custody cap rejects a new operation without fabricating classification or mutation'(){
        given:
        database.transaction{sql->sql.execute("""insert into auth_user_mutation_receipts(writer_id,operation_id,identity_id,request_hash,canonical_request,outcome,response_json,recorded_at)
            select 'user',gen_random_uuid(),? ,repeat('a',64),'{}','NOT_MIG','{}',clock_timestamp() from generate_series(1,100000)""",identityId)}
        def projection=new PostgresVerificationProjectionStore(database,new com.arman.bank.authservice.domain.VerificationProjection.Policy(['projection'] as Set,100000,100000),new byte[32],{sql->null})
        def crypto=new LegacyMigrationManagedProofCrypto('one',[one:new byte[32]])
        def store=new PostgresMigrationUserMutationStore(database,projection,'projection',crypto)
        def request=com.arman.bank.authservice.domain.LiveUserRevision.suspension('user',UUID.randomUUID(),identityId,null,null,[expected_version:0L,version:1L,phone_contact_version:1L,verification_epoch:1L,projection_payload_hash:'a'*64])
        when:
        store.suspend('user',request,System.nanoTime())
        then:
        def failure=thrown(PostgresMigrationUserMutationStore.Rejected)
        failure.status()==503
        database.transaction{sql->sql.fetchOne('select count(*) from auth_user_mutation_receipts').get(0,Long)}==100000L
        database.transaction{sql->sql.fetchOne('select count(*) from migration_banking_authorities').get(0,Long)}==0L
    }

    def 'genuine initial registration command before profile returns immutable NOT_MIG only after both consumed OTPs'(){
        given:
        String writer='registration-fixture'
        def flow=new Flow('https://issuer.example/sso/realms/armoney','subject-'+UUID.randomUUID(),'armoney-web','root','tab',Base64.urlEncoder.withoutPadding().encodeToString(([3]*32) as byte[]))
        String phone='+971501234567'
        String email='registration@example.test'
        def policy=Policy.defaults([flow.issuer()] as Set,[flow.clientId()] as Set,[writer] as Set)
        def crypto=new VerificationCrypto('test',[test:([11]*32) as byte[]],[test:([22]*32) as byte[]])
        def reader=new PostgresIdentityVerificationStore(database,crypto,([41]*32) as byte[],policy,null)
        def projection=new PostgresVerificationProjectionStore(database,P.Policy.defaults([writer] as Set),([42]*32) as byte[],
            {sql->{UUID id,Instant now->reader.projectionAuthorityInTransaction(sql,id,now)} as VerificationProjectionStore.AuthorityPort})
        def authority=new PostgresIdentityVerificationStore(database,crypto,([41]*32) as byte[],policy,projection,writer,true)
        def verification=new IdentityVerificationService(authority,{Flow expected,Instant start->new Observation(expected,phone,email,phone,true,true,start.plusSeconds(600),start)} as IdentityVerificationStore.Observations,policy)
        def inbox=new LinkedBlockingQueue<String>()
        def sms=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        sms.createContext('/api/messages'){exchange->
            try{
                assert exchange.requestHeaders.getFirst('Authorization')=='Bearer '+'synthetic-sms-key-for-registration-fixture'
                def message=new JsonSlurper().parse(exchange.requestBody)
                assert message.purpose=='REGISTRATION_PHONE'
                inbox.add(message.text as String)
                byte[] ack=JsonOutput.toJson([id:UUID.randomUUID().toString(),createdAt:Instant.now().epochSecond,replay:false]).bytes
                exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(201,ack.length);exchange.responseBody.write(ack)
            }finally{exchange.close()}
        }
        sms.start()
        def transport=new VerificationDeliveryTransport({String recipient,String subject,String text,String html->inbox.add(text);NotificationDelivery.Result.ACCEPTED} as VerificationDeliveryTransport.MailSender,URI.create('http://127.0.0.1:'+sms.address.port),'synthetic-sms-key-for-registration-fixture',true)
        def worker=new VerificationDeliveryWorker(new PostgresVerificationStore(database,crypto),crypto,transport,Clock.systemUTC())
        def enrollment=verification.enroll(writer,new Enrollment(UUID.randomUUID(),flow,phone,email))
        assert enrollment.error()==null
        [Purpose.REGISTRATION_EMAIL,Purpose.REGISTRATION_PHONE].each{purpose->
            def challenge=verification.issue(writer,new Issue(UUID.randomUUID(),enrollment.enrollmentId(),flow,purpose,1))
            assert challenge.error()==null
            worker.tick()
            String text=inbox.poll(3,TimeUnit.SECONDS)
            assert text!=null
            def matcher=(text=~/registration verification code is ([0-9]{6})\./)
            assert matcher.find()
            def receipt=verification.consume(writer,new Consume(UUID.randomUUID(),enrollment.enrollmentId(),challenge.challengeId(),flow,purpose,1,matcher.group(1)))
            assert receipt.error()==null
            assert receipt.receiptId()!=null
        }
        def command=projection.claim(writer).orElseThrow().command()
        def store=new PostgresMigrationUserMutationStore(database,projection,writer,new LegacyMigrationManagedProofCrypto('one',[one:([19]*32) as byte[]]))
        def input=com.arman.bank.authservice.domain.LiveUserRevision.suspension('user',command.operationId(),command.identityId(),null,null,[expected_version:command.expectedVersion() as Long,version:command.version() as Long,phone_contact_version:command.phoneContactVersion() as Long,verification_epoch:command.verificationEpoch(),projection_payload_hash:command.payloadHash()])
        when:
        def result=store.suspend('user',input,System.nanoTime())
        def replay=store.suspend('user',input,System.nanoTime())
        then:
        result.outcome=='NOT_MIG'
        result.binding==null
        replay==result
        database.transaction{sql->sql.fetchOne('select count(*) from auth_user_mutation_receipts').get(0,Long)}==1L
        database.transaction{sql->sql.fetchOne('select count(*) from managed_registration_principals where identity_id=?',command.identityId()).get(0,Long)}==1L
        database.transaction{sql->sql.fetchOne('select count(*) from migration_banking_authorities').get(0,Long)}==0L
        cleanup:
        worker?.close();sms?.stop(0)
    }

}
