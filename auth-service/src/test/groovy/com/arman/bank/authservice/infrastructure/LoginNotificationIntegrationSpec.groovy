package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.application.NotificationDelivery.Result
import com.arman.bank.authservice.domain.IdentityVerification.Flow
import com.arman.bank.authservice.domain.LoginProof as L
import spock.lang.Specification
import java.time.*
import java.util.concurrent.*

/** Genuine confirmed C2 + actual managed session transaction in owned PostgreSQL.
 * Delivery and optional names are synthetic ports, never provider/mailbox acceptance. */
class LoginNotificationIntegrationSpec extends Specification {
    ManagedBankingIntegrationSpec fixture
    ManagedBankingService service
    PostgresLoginNotificationOutbox outbox
    def setup(){
        fixture=new ManagedBankingIntegrationSpec()
        fixture.grantsEnabled=true
        fixture.flow=new Flow(fixture.ISSUER,'new-subject','armoney-web','root','tab',Base64.urlEncoder.withoutPadding().encodeToString(([3]*32) as byte[]))
        fixture.setup()
        def store=new PostgresManagedBankingStore(fixture.database,fixture.bankingKeys(),{issuer,client->issuer==fixture.ISSUER&&client=='armoney-web'} as ManagedBankingService.PairScope,fixture.WRITER,null,null,true)
        service=new ManagedBankingService(true,store,{binding,start->fixture.committedFacts(binding)} as CodeConfirmationObservations,{issuer,client->issuer==fixture.ISSUER&&client=='armoney-web'} as ManagedBankingService.PairScope)
        outbox=new PostgresLoginNotificationOutbox(fixture.database,fixture.bankingKeys(),null)
    }
    def cleanup(){fixture?.cleanup()}
    def login(){def input=fixture.confirmed();[input:input,session:service.exchange('synthetic.notice.access',fixture.principal(input))]}
    def notice(){fixture.row('login_notification_outbox')}
    long count(String table){fixture.count(table)}
    void technicalRetryDue(){fixture.execute("update login_notification_outbox set next_attempt_at=clock_timestamp()-interval '1 second' where state='PENDING'")}
    def 'successful new managed login commits one encrypted intent and validation or replay creates none'(){
        given:def original=login()
        expect:count('login_notification_outbox')==1
        notice().state=='PENDING'
        notice().ciphertext!=null
        fixture.database.transaction{sql->!sql.fetchOne('select row_to_json(n)::text from login_notification_outbox n').get(0,String).contains(fixture.EMAIL)}
        when:service.validate('Bearer '+original.session.accessToken())
        then:count('login_notification_outbox')==1
        when:service.exchange('different.raw.same.event',fixture.principal(original.input))
        then:thrown(L.Failure);count('login_notification_outbox')==1
    }
    def 'default constructor and failed authentication never capture a notice'(){
        given:def input=fixture.confirmed()
        when:fixture.consumer().exchange('default.off',fixture.principal(input))
        then:count('login_notification_outbox')==0
        when:service.exchange('wrong.session',fixture.principal(input,'wrong-session'))
        then:thrown(L.Failure);count('login_notification_outbox')==0
    }
    def 'notice insert failure rolls back session event consumption and accepted completion together'(){
        given:def input=fixture.confirmed()
        fixture.execute("create function qa_notice_reject() returns trigger language plpgsql as 'begin raise exception ''synthetic notice rollback''; end'")
        fixture.execute('create trigger qa_notice_reject before insert on login_notification_outbox for each row execute function qa_notice_reject()')
        when:service.exchange('notice.rollback',fixture.principal(input))
        then:thrown(RuntimeException)
        count('managed_banking_sessions')==0
        count('managed_banking_event_consumptions')==0
        count('managed_banking_completions')==0
        count('login_notification_outbox')==0
    }
    def 'concurrent one event exchange has one session consumption and notification winner'(){
        given:def input=fixture.confirmed();def start=new CountDownLatch(1);def pool=Executors.newFixedThreadPool(2)
        when:
        def futures=(1..2).collect{i->pool.submit({start.await(3,TimeUnit.SECONDS);try{service.exchange('notice.race.'+i,fixture.principal(input));true}catch(L.Failure|AuthFailure rejected){false}} as Callable<Boolean>)}
        start.countDown();def outcomes=futures.collect{it.get(8,TimeUnit.SECONDS)}
        then:outcomes.count(true)==1
        count('managed_banking_sessions')==1;count('managed_banking_event_consumptions')==1;count('login_notification_outbox')==1
        cleanup:pool?.shutdownNow();pool?.awaitTermination(3,TimeUnit.SECONDS)
    }
    def 'claim concurrency and expired technical lease reject stale acknowledgements after store reconstruction'(){
        given:login();def pool=Executors.newFixedThreadPool(2)
        when:def claims=(1..2).collect{pool.submit({outbox.claim()} as Callable)}.collect{it.get(5,TimeUnit.SECONDS)}
        then:claims.count{it.present}==1
        when:
        def original=claims.find{it.present}.get()
        // Technical lease expiry only, not authentication evidence or fabricated delivery.
        fixture.execute("update login_notification_outbox set leased_until=clock_timestamp()-interval '1 second' where id=?",original.id())
        def restarted=new PostgresLoginNotificationOutbox(fixture.database,fixture.bankingKeys(),null)
        def next=restarted.claim().orElseThrow()
        then:next.id()==original.id();next.lease()!=original.lease();next.attempts()==2
        !restarted.finish(original,Result.ACCEPTED)
        when:def frozen=restarted.prepareSend(next,'Owner Label').orElseThrow();restarted.finish(frozen,Result.ACCEPTED)
        then:notice().state=='SENT';notice().ciphertext==null;count('managed_banking_sessions')==1
        cleanup:pool?.shutdownNow();pool?.awaitTermination(3,TimeUnit.SECONDS)
    }
    def 'SMTP failure never revokes login and retry reuses original frozen optional name'(){
        given:def original=login();int profiles=0;def names=[];int sends=0
        def worker=new LoginNotificationWorker(outbox,{owner->profiles++;'Original Label'} as LoginNotificationProfiles,{claim->names<<claim.displayName();sends++;sends==1?Result.TRANSIENT_FAILURE:Result.ACCEPTED} as LoginNotificationWorker.Sender)
        when:worker.tick()
        then:notice().state=='PENDING';count('managed_banking_sessions')==1;count('managed_banking_event_consumptions')==1
        when:technicalRetryDue();worker.tick()
        then:profiles==1;names==['Original Label','Original Label'];notice().state=='SENT';notice().ciphertext==null
        count('managed_banking_sessions')==1
        cleanup:worker?.close()
    }
    def 'revoked session or suspended authority retires without send or retarget'(){
        given:def original=login();def claimed=outbox.claim().orElseThrow()
        if(damage=='logout')service.logout('Bearer '+original.session.accessToken())
        else fixture.execute("update verification_authorities set state='SUSPENDED',verification_epoch=verification_epoch+1,revision=revision+1")
        when:def prepared=outbox.prepareSend(claimed,'Unsent Name')
        then:prepared.empty;notice().state=='SKIPPED';notice().ciphertext==null
        where:damage<<['logout','authority']
    }
    def 'REC-LOGIN-NOTICE-COVERAGE-01 supported contact replacement retires before claim or between claim and SMTP'(){
        given:
        login()
        int profileCalls=0,sends=0
        def replaceContact={
            fixture.flow=new Flow(fixture.flow.issuer(),fixture.flow.subject(),fixture.flow.clientId(),'replacement-root','replacement-tab',Base64.urlEncoder.withoutPadding().encodeToString(([4]*32) as byte[]))
            def replacement=fixture.enroll('replacement@example.test')
            assert replacement.error()==null
            def actual=fixture.database.transaction{sql->sql.fetchOne('select c.canonical_value,c.version from verification_authorities a join verification_candidates c on c.id=a.email_candidate_id').intoMap()}
            assert actual.canonical_value=='replacement@example.test'
            assert actual.version==2
        }
        def worker=new LoginNotificationWorker(outbox,{owner->profileCalls++;if(timing=='after-claim')replaceContact();'Unsent Name'} as LoginNotificationProfiles,{claim->sends++;Result.ACCEPTED} as LoginNotificationWorker.Sender)
        if(timing=='before-claim')replaceContact()
        when:worker.tick()
        then:sends==0;notice().state=='SKIPPED';notice().ciphertext==null
        profileCalls==(timing=='before-claim'?0:1)
        count('login_notification_outbox')==1
        cleanup:worker?.close()
        where:timing<<['before-claim','after-claim']
    }
    def 'unknown original encryption key cannot send and never falls back to current plaintext'(){
        given:login()
        def wrongKeys=new ManagedBankingKeys('different',[different:Base64.encoder.encodeToString(([99]*32) as byte[])])
        def unknown=new PostgresLoginNotificationOutbox(fixture.database,wrongKeys,null)
        when:def claim=unknown.claim()
        then:claim.empty;notice().state=='SKIPPED';notice().ciphertext==null
    }
    def 'five finite attempts erase payload while keeping dedup tombstone'(){
        given:login()
        when:
        (1..5).each{int i->def claim=outbox.claim().orElseThrow();def prepared=outbox.prepareSend(claim,null).orElseThrow();assert outbox.finish(prepared,Result.TRANSIENT_FAILURE);if(i<5)technicalRetryDue()}
        then:notice().state=='DEAD';notice().attempts==5;notice().ciphertext==null;count('login_notification_outbox')==1
        outbox.claim().empty
    }
    def 'expired negative fixture erases ciphertext and cannot issue a claim'(){
        given:login()
        // Negative expiry probe only: age a genuine row under isolated tampering setup;
        // restore immutable guard before observing actual DB-clock expiry. No authority is fabricated.
        fixture.execute('alter table login_notification_outbox disable trigger login_notification_immutable')
        fixture.execute("with aged as (select clock_timestamp()-interval '25 hours' as created) update login_notification_outbox set created_at=aged.created,expires_at=aged.created+interval '24 hours',tombstone_until=aged.created+interval '7 days' from aged")
        fixture.execute('alter table login_notification_outbox enable trigger login_notification_immutable')
        when:def claim=outbox.claim()
        then:claim.empty;notice().state=='EXPIRED';notice().ciphertext==null
    }
}
