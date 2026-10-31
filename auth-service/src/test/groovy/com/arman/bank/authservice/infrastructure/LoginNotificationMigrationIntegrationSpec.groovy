package com.arman.bank.authservice.infrastructure
import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.application.NotificationDelivery.Result
import com.arman.bank.authservice.domain.LoginProof
import spock.lang.Specification
import java.time.*
/** Real Auth/User FINAL and fresh C2 native proof. SMTP acceptance is a synthetic result only. */
class LoginNotificationMigrationIntegrationSpec extends Specification {
    MigrationSourcePromotionIntegrationSpec scenario
    def setup(){scenario=new MigrationSourcePromotionIntegrationSpec();scenario.setup()}
    def cleanup(){scenario?.cleanup()}
    def invoke(String name,Class[] types,Object... args){def method=MigrationSourcePromotionIntegrationSpec.getDeclaredMethod(name,types);method.accessible=true;method.invoke(scenario,args)}
    def 'migration proof and FINAL emit none while fresh managed native login captures same-owner notice'(){
        given:
        def managed=scenario.fixture.fixture
        def pairs={issuer,client->issuer==managed.ISSUER&&client=='managed-client'} as ManagedBankingService.PairScope
        def keys=new ManagedBankingKeys('native-bank',['native-bank':Base64.encoder.encodeToString(([94]*32) as byte[])])
        // Capture is explicitly enabled before migration-purpose account/managed proof and FINAL.
        // Those paths must never invoke the native successful-session capture boundary.
        def store=new PostgresManagedBankingStore(managed.database,keys,pairs,managed.ACTOR,managed.crypto,scenario.currentUser,true)
        def service=new ManagedBankingService(true,store,managed.observer,pairs)
        def context=invoke('prepared',[Boolean.TYPE,Boolean.TYPE] as Class[],sso,false)
        invoke('promote',[Map] as Class[],context)
        assert managed.count('login_notification_outbox')==0
        // Bind this attempted exchange to the genuine signed migration-purpose receipt.
        // Its OIDC metadata has no native C2 interactive event/proof, as the migration protocol requires.
        def migration=managed.database.transaction{sql->sql.fetchOne('select issuer,subject,client_id,user_session_id,issued_at,token_expires_at from legacy_migration_managed_receipts where migration_id=?',context.original.admission.migrationId()).intoMap()}
        def migrationPrincipal=new SsoTokens.Principal(migration.issuer as String,migration.subject as String,managed.PHONE,managed.EMAIL,true,true,
            new SsoTokens.TokenContext(SsoTokens.Metadata.present(migration.client_id as String),SsoTokens.Metadata.present(migration.user_session_id as String),
                SsoTokens.Metadata.present((migration.issued_at as OffsetDateTime).toInstant()),SsoTokens.Metadata.present((migration.token_expires_at as OffsetDateTime).toInstant()),SsoTokens.Metadata.absent()))
        when:service.exchange('migration.purpose.without.native.proof',migrationPrincipal)
        then:def denied=thrown(AuthFailure)
        denied.kind()==AuthFailure.Kind.UNAUTHORIZED
        managed.count('login_notification_outbox')==0
        managed.count('managed_banking_sessions')==0
        managed.count('managed_banking_operations')==0
        when:
        def binding=invoke('nativeBinding',[Map] as Class[],context) as LoginProof.Binding
        def currentUser=scenario.currentUser
        def policy=LoginProof.Policy.defaults([binding.issuer()] as Set,[binding.clientId()] as Set,['native-v1'] as Set,[managed.ACTOR] as Set)
        def staging=new PostgresCodeIntentStore(managed.database,policy,
            new CodeIntentRetentionKeys('native-retention',['native-retention':Base64.encoder.encodeToString(([91]*32) as byte[])]),
            new CodeIntentDetailCrypto('native-aead',['native-aead':Base64.encoder.encodeToString(([92]*32) as byte[])],[] as Set),managed.crypto,currentUser)
        def proofs=new PostgresLoginProofStore(managed.database,policy,([93]*32) as byte[],{sql->new PostgresBankingEligibilityStore(sql,managed.crypto)})
        def confirmation=new PostgresCodeConfirmationStore(managed.database,staging,proofs,policy,managed.crypto,currentUser)
        def now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        def input=new LoginProof.Write(UUID.randomUUID(),UUID.randomUUID().toString().replace('-','')*2,'native-v1',binding.issuer(),binding.subject(),binding.clientId(),binding.userSessionId(),
            'synthetic-native-authentication',now,now.plusSeconds(120),LoginProof.Kind.INTERACTIVE,UUID.randomUUID(),now)
        staging.cleanup();staging.stage(managed.ACTOR,input)
        def confirmed=new CodeConfirmationService(confirmation,managed.observer).confirm(managed.ACTOR,new LoginProof.Resolve(input.codeDigest(),input.codeKeyId(),input.issuer(),input.subject(),input.clientId(),input.userSessionId()))
        assert !confirmed.deniedCommitted()
        assert managed.count('login_notification_outbox')==0
        store.cleanup()
        def principal=new SsoTokens.Principal(binding.issuer(),binding.subject(),managed.PHONE,managed.EMAIL,true,true,
            new SsoTokens.TokenContext(SsoTokens.Metadata.present(binding.clientId()),SsoTokens.Metadata.present(binding.userSessionId()),SsoTokens.Metadata.present(input.issuedAt()),SsoTokens.Metadata.present(input.expiresAt()),SsoTokens.Metadata.present(new SsoTokens.ProofClaims('interactive',input.eventId(),true))))
        def session=service.exchange('migration.notice.native',principal)
        def outbox=new PostgresLoginNotificationOutbox(managed.database,keys,managed.crypto)
        def claimed=outbox.claim().orElseThrow()
        then:session.accessToken().size()==43
        claimed.owner()==context.original.identityId
        claimed.recipient()==managed.EMAIL
        managed.count('login_notification_outbox')==1
        when:def prepared=outbox.prepareSend(claimed,null).orElseThrow();outbox.finish(prepared,Result.ACCEPTED)
        then:managed.database.transaction{it.fetchOne('select state,ciphertext from login_notification_outbox').get('state',String)}=='SENT'
        managed.count('managed_banking_sessions')==1;managed.count('migration_banking_authorities')==1
        where:sso<<[false,true]
    }
}
