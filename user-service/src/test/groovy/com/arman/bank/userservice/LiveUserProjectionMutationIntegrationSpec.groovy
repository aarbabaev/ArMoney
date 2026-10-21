package com.arman.bank.userservice
import com.arman.bank.userservice.application.*
import com.arman.bank.userservice.domain.*
import com.arman.bank.userservice.infrastructure.*
import com.arman.bank.runtime.Database
import spock.lang.Specification
import java.util.concurrent.*

/** User protocol transactions with explicitly synthetic Auth outcomes. Actual
 * classification/source suspension must be independently integrated with Auth. */
class LiveUserProjectionMutationIntegrationSpec extends Specification {
    LiveUserFixture f
    def setup(){f=new LiveUserFixture()}
    def cleanup(){f?.close()}
    def store(Closure response){new PostgresVerificationProjectionStore(f.database,response as UserMutationSuspensionPort,LiveUserRevisionSpec.WRITER)}
    def 'bootstrap journals original null request and exact committed replay does not call Auth again'(){
        given:def c=LiveUserRevisionSpec.command();int calls=0
        def projections=store{writer,r,start->calls++;assert r.before==null&&r.baseline==null;LiveUserRevisionSpec.negativeClassification(r)}
        when:def ack=projections.commit('auth',c)
        def replay=projections.commit('auth',c)
        then:ack==c.ack() && replay==ack && calls==1
        f.count('live_user_projection_commands')==1 && f.count('verification_projection_current')==1
    }
    def 'lost Auth response restart retains original null request and new partial rows fail final absence CAS'(){
        given:def c=LiveUserRevisionSpec.command();Map original
        def failed=store{writer,r,start->original=r;throw new LiveUserCurrentStore.Rejected(503)}
        when:failed.commit('auth',c)
        then:thrown(VerificationProjectionStore.Rejected)
        f.count('live_user_projection_commands')==1 && f.count('verification_projection_current')==0
        when:f.profiles.registerPhone(c.identityId(),c.canonicalPhone())
        def recovered=store{writer,r,start->assert r==original;LiveUserRevisionSpec.negativeClassification(r)}
        recovered.commit('auth',c)
        then:thrown(VerificationProjectionStore.Rejected)
        f.count('verification_projection_current')==0
    }
    def 'partial bootstrap and unknown Auth cannot mutate or silently classify'(){
        given:def c=LiveUserRevisionSpec.command();int calls=0
        f.profiles.registerPhone(c.identityId(),c.canonicalPhone())
        def projections=store{writer,r,start->calls++;LiveUserRevisionSpec.negativeClassification(r)}
        when:projections.commit('auth',c)
        then:thrown(VerificationProjectionStore.Rejected)
        calls==0 && f.count('live_user_projection_commands')==0
        when:
        def fresh=LiveUserRevisionSpec.command(UUID.randomUUID(),0,'+971521234567')
        store{writer,r,start->LiveUserRevisionSpec.negativeClassification(r)+[outcome:'UNKNOWN']}.commit('auth',fresh)
        then:thrown(VerificationProjectionStore.Rejected)
        f.count('verification_projection_current')==0
    }
    def 'released baseline requires exact suspension binding and concurrent tuple change prevents commit'(){
        given:def s=f.released();def c=LiveUserRevisionSpec.command(s.owner)
        def projections=store{writer,r,start->
            assert r.before!=null&&r.baseline==s.baseline
            // Technical revision fault after authenticated-port return, not fake financial success.
            f.database.transaction{sql->sql.execute('update migration_contact_counters set revision=revision+1 where identity_id=?',s.owner)}
            def response=LiveUserRevisionSpec.negativeClassification(r)
            response.outcome='SUSPENDED';response.binding=[source_kind:'MIG',migration_id:s.baseline.migration_id,decision_id:s.baseline.decision_id,classification_generation:1L,previous_epoch:1L,previous_source_revision:1L,resulting_epoch:2L,resulting_source_revision:2L]
            response
        }
        when:projections.commit('auth',c)
        then:thrown(VerificationProjectionStore.Rejected)
        f.count('verification_projection_current')==0
    }
    def 'journal cannot revive an explicitly aged technical projection tombstone'(){
        given:def c=LiveUserRevisionSpec.command();int calls=0
        def p=store{writer,r,start->calls++;LiveUserRevisionSpec.negativeClassification(r)}
        when:store{writer,r,start->throw new LiveUserCurrentStore.Rejected(503)}.commit('auth',c)
        then:thrown(VerificationProjectionStore.Rejected)
        when:
        // Negative-only expired technical history, like the existing V6 cleanup
        // fixture. Never update immutable history or seed an Auth/OTP authority.
        f.database.transaction{sql->sql.execute("insert into verification_projection_tombstones values(?,?,?,clock_timestamp()-interval '1 second')",'auth',c.operationId(),c.payloadHash())}
        p.commit('auth',c)
        then:def rejection=thrown(VerificationProjectionStore.Rejected);rejection.status()==410
        calls==0 && f.count('verification_projection_current')==0
    }
    def 'original preflight survives pool restart and exact command recovery without rebasing'(){
        given:def c=LiveUserRevisionSpec.command();Map original
        when:store{writer,r,start->original=r;throw new LiveUserCurrentStore.Rejected(503)}.commit('auth',c)
        then:thrown(VerificationProjectionStore.Rejected)
        when:
        f.database.close();f.database=new Database(f.postgres.jdbcUrl,f.postgres.username,f.postgres.password)
        def recovered=store{writer,r,start->assert r==original;LiveUserRevisionSpec.negativeClassification(r)}
        def ack=recovered.commit('auth',c)
        then:ack==c.ack()
        f.count('live_user_projection_commands')==1
        when:recovered.commit('auth',LiveUserRevisionSpec.command(c.identityId(),0,'+971521234567',1,1,c.operationId()))
        then:def conflict=thrown(VerificationProjectionStore.Rejected);conflict.status()==409
    }
    def 'four occupied connections still allow the outside-transaction peer to borrow the fifth'(){
        given:def pool=Executors.newFixedThreadPool(4)
        def occupied=new CountDownLatch(4),release=new CountDownLatch(1)
        def holders=(1..4).collect{pool.submit({->f.database.withSession{session->occupied.countDown();if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError('Fixture release missing');null}} as Callable)}
        assert occupied.await(5,TimeUnit.SECONDS)
        def c=LiveUserRevisionSpec.command()
        def projections=store{writer,r,start->
            assert f.database.transaction{sql->sql.fetchOne('select 1').get(0,Integer)}==1
            LiveUserRevisionSpec.negativeClassification(r)
        }
        when:def ack=projections.commit('auth',c)
        then:ack==c.ack()
        cleanup:release?.countDown();holders?.each{it.get(5,TimeUnit.SECONDS)};pool?.shutdownNow();assert pool==null||pool.awaitTermination(5,TimeUnit.SECONDS)
    }
    def 'global cap is atomic and permits exact existing journal recovery without new allocation'(){
        given:def c=LiveUserRevisionSpec.command()
        when:store{writer,r,start->throw new LiveUserCurrentStore.Rejected(503)}.commit('auth',c)
        then:thrown(VerificationProjectionStore.Rejected)
        when:
        // Technical capacity rows only; never Auth/OTP/financial authority fixtures.
        f.database.transaction{sql->sql.execute("""insert into live_user_projection_commands
          select 'capacity-fixture',gen_random_uuid(),gen_random_uuid(),'{}'::jsonb,'{}'::jsonb,repeat('0',64),0
          from generate_series(1,99998)""")}
        def gate=new CountDownLatch(1),pool=Executors.newFixedThreadPool(2)
        def jobs=(0..1).collect{n->pool.submit({->gate.await(3,TimeUnit.SECONDS);try{store{writer,r,start->throw new LiveUserCurrentStore.Rejected(503)}.commit('auth',LiveUserRevisionSpec.command(UUID.randomUUID(),0,n==0?'+971521234567':'+971541234567'));0}catch(VerificationProjectionStore.Rejected rejected){rejected.status()}} as Callable)}
        gate.countDown();def results=jobs.collect{it.get(8,TimeUnit.SECONDS)}
        then:results==[503,503]
        f.count('live_user_projection_commands')==100000
        when:def recovered=store{writer,r,start->LiveUserRevisionSpec.negativeClassification(r)}.commit('auth',c)
        then:recovered==c.ack() && f.count('live_user_projection_commands')==100000
        cleanup:pool?.shutdownNow();assert pool==null||pool.awaitTermination(5,TimeUnit.SECONDS)
    }
    def 'already committed legacy projection replays before journal and never calls peer'(){
        given:def c=LiveUserRevisionSpec.command()
        def original=new PostgresVerificationProjectionStore(f.database).commit('auth',c)
        int calls=0
        when:def replay=store{writer,r,start->calls++;throw new LiveUserCurrentStore.Rejected(503)}.commit('auth',c)
        then:replay==original && calls==0 && f.count('live_user_projection_commands')==0
    }
    def 'late final write rolls back every business write while retaining committed preflight'(){
        given:def c=LiveUserRevisionSpec.command()
        f.database.transaction{sql->
            sql.execute('''create function live_user_test_delay() returns trigger language plpgsql as $$
                begin perform pg_sleep(4.2); return new; end; $$''')
            sql.execute('create trigger live_user_test_delay after insert on verification_projection_current for each row execute function live_user_test_delay()')
        }
        when:store{writer,r,start->LiveUserRevisionSpec.negativeClassification(r)}.commit('auth',c)
        then:def failed=thrown(VerificationProjectionStore.Rejected);failed.status()==503
        f.count('live_user_projection_commands')==1
        f.count('registration_phone_claims')==0
        f.count('verification_projection_current')==0
        f.count('verification_projection_operations')==0
    }
    def 'stale journal NOT MIG cannot cross newly staged migration fence or call Auth again'(){
        given:def c=LiveUserRevisionSpec.command();int calls=0
        def p=store{writer,r,start->
            calls++
            f.profiles.registerPhone(c.identityId(),c.canonicalPhone())
            def profile=f.profiles.save(new Profile(UUID.randomUUID(),c.identityId(),'Synthetic staged owner'))
            f.migrations.stage('auth',MigrationBaseline.stage(MigrationBaselineSpec.stage(c.identityId(),profile.id())))
            LiveUserRevisionSpec.negativeClassification(r)
        }
        when:p.commit('auth',c)
        then:thrown(VerificationProjectionStore.Rejected)
        when:p.commit('auth',c)
        then:thrown(VerificationProjectionStore.Rejected)
        calls==1 && f.count('verification_projection_current')==0
    }
    def 'time spent receiving body remains charged through preflight and Auth invocation'(){
        given:def c=LiveUserRevisionSpec.command();int calls=0
        long original=System.nanoTime()-3_000_000_000L
        def p=store{writer,r,start->
            calls++;assert start==original
            Thread.sleep(1200) // A synthetic peer exceeding the invocation's remaining second.
            LiveUserRevisionSpec.negativeClassification(r)
        }
        when:p.commit('auth',c,original)
        then:def rejected=thrown(VerificationProjectionStore.Rejected);rejected.status()==503
        calls==1 && f.count('live_user_projection_commands')==1
        f.count('verification_projection_current')==0 && f.count('registration_phone_claims')==0
        when:p.commit('auth',LiveUserRevisionSpec.command(UUID.randomUUID(),0,'+971521234567'),System.nanoTime()-4_000_000_000L)
        then:thrown(VerificationProjectionStore.Rejected)
        calls==1 && f.count('live_user_projection_commands')==1
    }
}
