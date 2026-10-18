package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*
import java.util.concurrent.*

/** Foundation only: real existing credentials/sessions; no OTP/User ACK/provider proof or financial success. */
class LegacyMigrationIntegrationSpec extends Specification {
    PostgreSQLContainer postgres
    Database database
    AuthService auth
    PostgresAuthStore store
    PostgresLegacyMigrationStore migrations
    UUID identityId
    String oldToken
    static final String PASSWORD='synthetic long legacy password'
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        store=new PostgresAuthStore(database,true)
        auth=new AuthService(store,new Argon2Passwords(),Clock.systemUTC(),{id,phone->} as RegistrationProfiles)
        auth.register('legacy@example.test',PASSWORD,'+971501234567')
        oldToken=auth.login('legacy@example.test',PASSWORD).accessToken();identityId=auth.me('Bearer '+oldToken).id()
        migrations=new PostgresLegacyMigrationStore(database,new LegacyMigrationCrypto('aead',[aead:([71]*32) as byte[]],([72]*32) as byte[]),true)
    }
    def cleanup(){database?.close();postgres?.stop()}
    def row(String table){database.transaction{sql->sql.fetchOne('select * from '+table)?.intoMap()}}
    long count(String table){database.transaction{sql->sql.fetchOne('select count(*) from '+table).get(0,Long)}}
    def admit(){auth.beginLegacyMigration('legacy@example.test',PASSWORD,UUID.randomUUID(),migrations)}
    def 'admission preserves same owner verifier and original sessions while new admissions and publication stay denied'(){
        given:def identity=row('identities');def session=row('sessions')
        when:def admitted=admit()
        then:admitted.identityId()==identityId;row('identities')==identity;row('sessions')==session
        count('legacy_migration_continuity')==1;count('managed_registration_principals')==0;count('external_identities')==0
        auth.me('Bearer '+oldToken).id()==identityId
        migrations.status(admitted.migrationId(),admitted.ownerCapability()).state()=='OWNER_BOUND'
        when:auth.login('legacy@example.test',PASSWORD)
        then:thrown(AuthFailure);count('sessions')==1
        when:migrations.authorizePublication(admitted.migrationId())
        then:thrown(AuthFailure)
        when:migrations.complete(admitted.migrationId())
        then:thrown(AuthFailure);count('managed_registration_principals')==0;count('sessions')==1
    }
    def 'explicit logout removes live row and snapshot cannot recreate authorization'(){
        given:admit()
        when:auth.logout('Bearer '+oldToken)
        then:count('legacy_migration_continuity')==1;count('sessions')==0
        when:auth.me('Bearer '+oldToken)
        then:thrown(AuthFailure)
    }
    def 'unverified wrong or unknown credentials do not create intents or alter old sessions'(){
        when:auth.beginLegacyMigration(email,'different synthetic long password',UUID.randomUUID(),migrations)
        then:thrown(AuthFailure);count('legacy_migration_intents')==0;count('sessions')==1
        where:email << ['legacy@example.test','unknown@example.test']
    }
    def 'owner security revocation advances real generation and keeps pending metadata without continuity bypass'(){
        given:def admitted=admit();long before=row('legacy_auth_generations').owner_generation
        when:store.revokeOwnerSessions(identityId)
        then:row('legacy_auth_generations').owner_generation==before+1;count('sessions')==0;count('legacy_migration_continuity')==1
        when:auth.me('Bearer '+oldToken)
        then:thrown(AuthFailure)
    }
    def 'password and canonical email changes advance real post-schema generations'(){
        given:def before=row('legacy_auth_generations')
        when:database.transaction{sql->sql.execute('update identities set password_hash=?,email=? where id=?',new Argon2Passwords().hash('new synthetic password long enough'),'changed@example.test',identityId)}
        then:row('legacy_auth_generations').credential_generation==before.credential_generation+1
        row('legacy_auth_generations').email_generation==before.email_generation+1
    }
    def 'admission and concurrent new session serialize to a single exact continuity set'(){
        given:def executor=Executors.newFixedThreadPool(2);def start=new CountDownLatch(1)
        when:
        def admission=executor.submit({start.await(3,TimeUnit.SECONDS);admit()} as Callable)
        def login=executor.submit({start.await(3,TimeUnit.SECONDS);try{auth.login('legacy@example.test',PASSWORD).accessToken()}catch(AuthFailure denied){null}} as Callable)
        start.countDown()
        def admitted=admission.get(15,TimeUnit.SECONDS)
        def newToken=login.get(15,TimeUnit.SECONDS)
        then:admitted.identityId()==identityId;count('legacy_migration_intents')==1
        count('legacy_migration_continuity')==count('sessions')
        auth.me('Bearer '+oldToken).id()==identityId
        if(newToken!=null){assert auth.me('Bearer '+newToken).id()==identityId}
        cleanup:executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS)
    }
    def 'different command cannot replace intent extend deadline or recover lost capability'(){
        given:def original=admit();def frozen=row('legacy_migration_intents')
        when:admit()
        then:thrown(AuthFailure);row('legacy_migration_intents')==frozen;count('legacy_migration_continuity')==1
        auth.me('Bearer '+oldToken).id()==identityId
    }
    def 'default off foundation allocates no intent and does not block unchanged login'(){
        given:def off=new PostgresLegacyMigrationStore(database,new LegacyMigrationCrypto('aead',[aead:([71]*32) as byte[]],([72]*32) as byte[]))
        when:auth.beginLegacyMigration('legacy@example.test',PASSWORD,UUID.randomUUID(),off)
        then:thrown(AuthFailure);count('legacy_migration_intents')==0
        when:def current=auth.login('legacy@example.test',PASSWORD)
        then:auth.me('Bearer '+current.accessToken()).id()==identityId
    }

    def 'deleted and recreated same digest with exact original timestamps is not the snapshotted session row'(){
        given:admit();def original=row('sessions')
        when:store.revoke(AuthService.digest(oldToken))
        database.transaction{sql->sql.execute('insert into sessions(token_hash,identity_id,created_at,expires_at) values(?,?,cast(? as timestamptz),cast(? as timestamptz))',original.token_hash,identityId,original.created_at.toString(),original.expires_at.toString())}
        then:count('sessions')==1;count('legacy_migration_continuity')==1
        when:auth.me('Bearer '+oldToken)
        then:thrown(AuthFailure)
    }
    def 'permanent managed denial wins over pending continuity in both compatibility modes'(){
        given:admit()
        // Negative classification only, never a positive receipt/grant or fabricated User ACK.
        database.transaction{sql->sql.execute('insert into managed_registration_principals(identity_id,enrollment_id) values(?,?)',identityId,UUID.randomUUID())}
        when:new PostgresAuthStore(database,enforced).findSession(AuthService.digest(oldToken),Instant.now())
        then:thrown(AuthFailure);count('sessions')==1
        where:enforced << [false,true]
    }
    def 'reverting verifier bytes cannot hide the intervening credential generation change'(){
        given:def credentials=store.findByEmail('legacy@example.test').orElseThrow()
        database.transaction{sql->
            sql.execute('update identities set password_hash=? where id=?',new Argon2Passwords().hash('another long synthetic password'),identityId)
            sql.execute('update identities set password_hash=? where id=?',credentials.passwordHash(),identityId)
        }
        when:migrations.admitLocal(identityId,credentials.passwordHash(),credentials.credentialGeneration(),credentials.ownerGeneration(),UUID.randomUUID())
        then:thrown(AuthFailure);count('legacy_migration_intents')==0;count('sessions')==1
    }
    def 'renewed expiry cannot join an existing snapshot'(){
        given:admit()
        database.transaction{sql->sql.execute("update sessions set expires_at=expires_at+interval '1 minute' where token_hash=?",AuthService.digest(oldToken))}
        when:auth.me('Bearer '+oldToken)
        then:thrown(AuthFailure);count('legacy_migration_continuity')==1
    }

    def 'existing SSO-only owner keeps null local credentials and exact association counter without fabricated migration proof'(){
        given:def session=auth.sso('synthetic.token',{token->new SsoTokens.Principal('https://legacy.example/realm','sso-only-subject','+971521234567', null, false, false)} as SsoTokens)
        def ssoOwner=auth.me('Bearer '+session.accessToken()).id()
        when:def saved=database.transaction{sql->sql.fetchOne('select email,password_hash from identities where id=?',ssoOwner).intoMap()}
        then:saved.email==null;saved.password_hash==null
        when:database.transaction{sql->sql.execute('update external_identities set identity_id=identity_id where issuer=? and subject=?','https://legacy.example/realm','sso-only-subject')}
        then:row('legacy_auth_association_generations').generation==1L
        when:migrations.admitExistingSso('https://legacy.example/realm','sso-only-subject',null)
        then:thrown(AuthFailure);count('legacy_migration_intents')==0;auth.me('Bearer '+session.accessToken()).id()==ssoOwner
    }
    def 'counter overflow fails atomically before independent security revocation'(){
        given:database.transaction{sql->sql.execute('update legacy_auth_generations set owner_generation=? where identity_id=?',Long.MAX_VALUE,identityId)}
        when:store.revokeOwnerSessions(identityId)
        then:thrown(RuntimeException);count('sessions')==1;row('legacy_auth_generations').owner_generation==Long.MAX_VALUE
        auth.me('Bearer '+oldToken).id()==identityId
    }

    def 'equal owner generations outside Long cache preserve exact continuity and unequal generation denies'(){
        given:database.transaction{sql->sql.execute('update legacy_auth_generations set owner_generation=? where identity_id=?',128L,identityId)}
        def admitted=admit()
        expect:admitted.ownerGeneration()==128L;row('legacy_migration_continuity').owner_generation==128L
        auth.me('Bearer '+oldToken).id()==identityId
        when:database.transaction{sql->sql.execute('update legacy_auth_generations set owner_generation=owner_generation+1 where identity_id=?',identityId)}
        auth.me('Bearer '+oldToken)
        then:thrown(AuthFailure);count('sessions')==1;count('legacy_migration_continuity')==1
    }

}
