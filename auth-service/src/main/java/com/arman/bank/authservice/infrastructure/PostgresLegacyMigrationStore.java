package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.*;
import com.arman.bank.authservice.domain.LegacyMigration.*;
import com.arman.bank.runtime.Database;
import org.jooq.DSLContext;
import org.jooq.Record;
import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

/** Owner/session fencing foundation only. No target linking, authority seed, decision, User ACK or final cutover. */
public final class PostgresLegacyMigrationStore implements LegacyMigrationStore {
    private final Database database;
    private final LegacyMigrationCrypto crypto;
    private final boolean enabled;
    private final SecureRandom random=new SecureRandom();
    public PostgresLegacyMigrationStore(Database database,LegacyMigrationCrypto crypto){this(database,crypto,false);}
    public PostgresLegacyMigrationStore(Database database,LegacyMigrationCrypto crypto,boolean enabled){this.database=Objects.requireNonNull(database);this.crypto=Objects.requireNonNull(crypto);this.enabled=enabled;}
    /** Global prefix is owned by caller before the identity/credential/session-admission row. */
    static Record lockOwner(DSLContext sql,UUID identity){
        PostgresVerificationProjectionStore.requireLocked(sql);
        if(sql.fetchOne("select id from identities where id=? for update",identity)==null)deny();
        var row=sql.fetchOne("select * from legacy_auth_generations where identity_id=? for update",identity);
        if(row==null)unavailable();return row;
    }
    static Record pending(DSLContext sql,UUID identity){return sql.fetchOne("select * from legacy_migration_intents where identity_id=?",identity);}
    static void requireNoPending(DSLContext sql,UUID identity){if(pending(sql,identity)!=null)deny();}
    @Override public Admission admitLocal(UUID identity,String expectedVerifier,long expectedCredentialGeneration,long expectedOwnerGeneration,UUID command){
        return database.transaction(sql->admitLocal(sql,identity,expectedVerifier,expectedCredentialGeneration,expectedOwnerGeneration,command));
    }
    Admission admitLocal(DSLContext sql,UUID identity,String expectedVerifier,long expectedCredentialGeneration,long expectedOwnerGeneration,UUID command){
        if(!enabled)unavailable();if(identity==null||command==null||expectedVerifier==null||expectedVerifier.length()>4096)deny();
        byte[] bytes=new byte[32];random.nextBytes(bytes);String capability=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Arrays.fill(bytes,(byte)0);
            PostgresVerificationProjectionStore.lockControls(sql);var generation=lockOwner(sql,identity);
            if(expectedCredentialGeneration<0||expectedOwnerGeneration<0
                    ||generation.get("credential_generation",Long.class)!=expectedCredentialGeneration
                    ||generation.get("owner_generation",Long.class)!=expectedOwnerGeneration)deny();
            var account=sql.fetchOne("select email,password_hash,registration_phone from identities where id=?",identity);
            if(account==null||account.get("password_hash",String.class)==null||!MessageDigest.isEqual(expectedVerifier.getBytes(StandardCharsets.UTF_8),account.get("password_hash",String.class).getBytes(StandardCharsets.UTF_8)))deny();
            String email=account.get("email",String.class),phone=account.get("registration_phone",String.class);
            try{if(!Objects.equals(email,AuthService.normalizeEmail(email))||!AuthService.canonicalPhone(phone))deny();}catch(AuthFailure bad){deny();}
            if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=?",identity)!=null
                    ||sql.fetchOne("select enrollment_id from verification_authorities where mapped_identity_id=? or pending_identity_id=?",identity,identity)!=null)deny();
            // Exact retry cannot recover lost one-time capability; never allocate a replacement or snapshot later sessions.
            if(pending(sql,identity)!=null)deny();
            if(sql.fetchOne("select count(*) from legacy_migration_intents").get(0,Long.class)>=10000)throw new AuthFailure(AuthFailure.Kind.RATE_LIMITED);
            // This is a newly established migration email baseline, never a historical verified version.
            generation=sql.fetchOne("update legacy_auth_generations set email_generation=email_generation+1 where identity_id=? returning *",identity);
            UUID migration=UUID.randomUUID();Instant start=now(sql),deadline=start.plusSeconds(120);
            sql.execute("""
                insert into legacy_migration_intents(id,identity_id,command_id,retention_key_id,command_hash,capability_hash,owner_generation,credential_generation,email_generation,proof_mode,state,created_at,authorization_deadline)
                values(?,?,?,?,?,?,?,?,?,'LOCAL_PASSWORD','OWNER_BOUND',cast(? as timestamptz),cast(? as timestamptz))
                """,migration,identity,command,crypto.digestKeyId(),crypto.command(identity,command,migration,generation.get("owner_generation",Long.class),generation.get("credential_generation",Long.class),generation.get("email_generation",Long.class)),crypto.capability(migration,identity,capability),
                generation.get("owner_generation",Long.class),generation.get("credential_generation",Long.class),generation.get("email_generation",Long.class),start.toString(),deadline.toString());
            snapshotSessions(sql,migration,identity,generation.get("owner_generation",Long.class));
            if(!now(sql).isBefore(deadline))unavailable();
            return new Admission(migration,identity,generation.get("owner_generation",Long.class),generation.get("credential_generation",Long.class),start,deadline,capability);
    }
    @Override public Status status(UUID migration,String capability){
        if(!enabled)unavailable();if(migration==null||capability==null||!capability.matches("[A-Za-z0-9_-]{43}"))deny();
        return database.transaction(sql->{
            PostgresVerificationProjectionStore.lockControls(sql);
            var routing=sql.fetchOne("select identity_id from legacy_migration_intents where id=?",migration);if(routing==null)deny();
            var generation=lockOwner(sql,routing.get("identity_id",UUID.class));
            var preliminary=sql.fetchOne("select * from legacy_migration_intents where id=?",migration);
            if(preliminary==null)deny();requireModeCurrent(sql,preliminary,generation);
            var intent=sql.fetchOne("select * from legacy_migration_intents where id=? for update",migration);
            if(intent==null||!MessageDigest.isEqual(intent.get("capability_hash",byte[].class),crypto.capability(intent.get("retention_key_id",String.class),migration,intent.get("identity_id",UUID.class),capability)))deny();
            Instant deadline=intent.get("authorization_deadline",OffsetDateTime.class).toInstant();
            if(!now(sql).isBefore(deadline))deny(); // A separate readback capability is not implemented by this foundation.
            return new Status(migration,intent.get("state",String.class),deadline);
        });
    }
    /** Exact surviving old row only. A permanent marker or unrelated authority association always wins. */
    static void requireContinuity(DSLContext sql,UUID identity,String token,Instant originalCreated,Instant originalExpiry,boolean enforceAssociation){
        var generation=lockOwner(sql,identity);
        if(sql.fetchOne("select identity_id from managed_registration_principals where identity_id=? union all select identity_id from managed_identity_sources where identity_id=? limit 1",identity,identity)!=null)deny();
        var intent=pending(sql,identity);if(intent==null)deny();
        requireModeCurrent(sql,intent,generation);
        var snapshot=sql.fetchOne("select * from legacy_migration_continuity where migration_id=? and token_hash=? and identity_id=?",intent.get("id",UUID.class),token,identity);
        var liveRow=sql.fetchOne("select row_id from legacy_auth_session_rows where token_hash=?",token);
        if(snapshot==null||liveRow==null||!snapshot.get("session_row_id",UUID.class).equals(liveRow.get("row_id",UUID.class))
                ||snapshot.get("owner_generation",Long.class).longValue()!=generation.get("owner_generation",Long.class).longValue()
                ||!snapshot.get("original_created_at",OffsetDateTime.class).toInstant().equals(originalCreated)
                ||!snapshot.get("original_expires_at",OffsetDateTime.class).toInstant().equals(originalExpiry))deny();
        // Do not relax an arbitrary authority gate. Exact association generation must come from a future genuine adapter.
        { // Pending migration never exempts an unrelated authority, independently of compatibility flags.
            var authorities=sql.fetch("select enrollment_id from verification_authorities where mapped_identity_id=? or pending_identity_id=?",identity,identity);
            if(!authorities.isEmpty()){
                var association=sql.fetchOne("select * from legacy_migration_associations where migration_id=?",intent.get("id",UUID.class));
                if(authorities.size()!=1||association==null||!association.get("enrollment_id",UUID.class).equals(authorities.get(0).get("enrollment_id",UUID.class))
                        ||association.get("owner_generation",Long.class).longValue()!=generation.get("owner_generation",Long.class).longValue())deny();
            }
        }
    }
    /** Owner prefix is already held. SSO mode binds the exact original active association, never contacts. */
    static void requireModeCurrent(DSLContext sql,Record intent,Record generation){
        PostgresVerificationProjectionStore.requireLocked(sql);
        if(generation.get("owner_generation",Long.class).longValue()!=intent.get("owner_generation",Long.class).longValue())deny();
        String mode=intent.get("proof_mode",String.class);
        if("LOCAL_PASSWORD".equals(mode)){
            if(generation.get("credential_generation",Long.class).longValue()!=intent.get("credential_generation",Long.class).longValue())deny();
        }else if("LEGACY_OIDC_REAUTH".equals(mode)){
            String issuer=intent.get("legacy_issuer",String.class),subject=intent.get("legacy_subject",String.class);
            Long expected=intent.get("association_generation",Long.class);
            if(issuer==null||subject==null||expected==null)deny();
            var association=sql.fetchOne("select * from legacy_auth_association_generations where issuer=? and subject=? for update",issuer,subject);
            var mapping=sql.fetchOne("select identity_id from external_identities where issuer=? and subject=?",issuer,subject);
            if(association==null||mapping==null||!Boolean.TRUE.equals(association.get("active",Boolean.class))
                ||!Objects.equals(association.get("identity_id",UUID.class),intent.get("identity_id",UUID.class))
                ||!Objects.equals(mapping.get("identity_id",UUID.class),intent.get("identity_id",UUID.class))
                ||association.get("generation",Long.class).longValue()!=expected.longValue())deny();
        }else deny();
    }
    static void snapshotSessions(DSLContext sql,UUID migration,UUID identity,long ownerGeneration){
        PostgresVerificationProjectionStore.requireLocked(sql);
        sql.execute("""
            insert into legacy_migration_continuity(migration_id,token_hash,session_row_id,identity_id,owner_generation,original_created_at,original_expires_at)
            select ?,s.token_hash,r.row_id,s.identity_id,?,s.created_at,s.expires_at from sessions s join legacy_auth_session_rows r on r.token_hash=s.token_hash where s.identity_id=? and s.expires_at>clock_timestamp()
            """,migration,ownerGeneration,identity);
    }
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
    private static void deny(){throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED);}
    private static void unavailable(){throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);}
}
