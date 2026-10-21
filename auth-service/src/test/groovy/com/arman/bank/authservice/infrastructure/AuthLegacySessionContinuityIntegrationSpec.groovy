package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.AuthFailure
import com.arman.bank.authservice.application.AuthService
import spock.lang.Specification
import java.time.Instant

/** Real PostgreSQL legacy rows; no grants, publication receipts or successful financial fixtures. */
class AuthLegacySessionContinuityIntegrationSpec extends Specification {
    LegacyMigrationIntegrationSpec fixture
    def setup() { fixture=new LegacyMigrationIntegrationSpec(); fixture.setup() }
    def cleanup() { fixture?.cleanup() }
    private Optional existing(Instant now = Instant.now()) {
        new PostgresAuthStore(fixture.database,true).findSession(AuthService.digest(fixture.oldToken),now)
    }

    def 'pending continuity still requires exact original timestamps and owner generation'() {
        given:
        fixture.admit()
        assert existing().orElseThrow().id()==fixture.identityId
        when:
        fixture.database.transaction { sql ->
            if(change=='created') sql.execute("update sessions set created_at=created_at-interval '1 second' where identity_id=?",fixture.identityId)
            if(change=='expiry') sql.execute("update sessions set expires_at=expires_at+interval '1 second' where identity_id=?",fixture.identityId)
            if(change=='owner') sql.execute('update legacy_auth_generations set owner_generation=owner_generation+1 where identity_id=?',fixture.identityId)
            if(change=='credential') sql.execute('update identities set password_hash=? where id=?','synthetic changed verifier',fixture.identityId)
        }
        existing()
        then:
        thrown(AuthFailure)
        where:
        change << ['created','expiry','owner','credential']
    }

    def 'pending continuity cannot restore logout expired or deleted live rows'() {
        given:
        fixture.admit()
        when:
        if(change=='logout') fixture.auth.logout('Bearer '+fixture.oldToken)
        // Evaluate the exact expiry boundary without changing the valid stored row.
        Instant observedAt = change=='expiry' ? fixture.database.transaction { sql ->
            sql.fetchOne('select expires_at from sessions where token_hash=?',AuthService.digest(fixture.oldToken))
                .get('expires_at',java.time.OffsetDateTime).toInstant()
        } : Instant.now()
        if(change=='security') fixture.store.revokeOwnerSessions(fixture.identityId)
        then:
        existing(observedAt).empty
        fixture.count('legacy_migration_continuity')==1
        where:
        change << ['logout','expiry','security']
    }

    def 'permanent managed classification denies surviving historical row even without pending migration'() {
        given:
        assert existing().present
        fixture.database.transaction { sql ->
            // Negative classification fixture only: it never establishes a grant or successful FINAL.
            sql.execute('insert into managed_registration_principals(identity_id,enrollment_id) values(?,?)',fixture.identityId,UUID.randomUUID())
        }
        when:
        existing()
        then:
        thrown(AuthFailure)
        fixture.count('sessions')==1
        fixture.count('legacy_migration_intents')==0
    }
}