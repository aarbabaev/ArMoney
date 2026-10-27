package com.arman.bank.userservice

import com.arman.bank.runtime.Database
import com.arman.bank.userservice.infrastructure.VerificationProjectionConfiguration
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.exception.DataAccessException
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

/** Real populated V5 -> V7 primary database upgrade; synthetic legacy operator
 * flags remain legacy data and are not auth receipts or ACTIVE eligibility.
 */
class VerificationProjectionUpgradeIntegrationSpec extends Specification {
    def 'V6 and V7 preserve published V5 phones physical profiles pinned placements audit and raw migration checksums'() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations('classpath:db/migration').target('5').load().migrate()
        def connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        def sql = DSL.using(connection, SQLDialect.POSTGRES)
        UUID primary = UUID.randomUUID()
        UUID pending = UUID.randomUUID()
        UUID remote = UUID.randomUUID()
        UUID uninitialized = UUID.randomUUID()
        UUID primaryProfile = UUID.randomUUID()
        sql.execute('''insert into profiles(id,identity_id,display_name,phone_number,phone_verified)
            values (?,?,?, ?,true)''', primaryProfile, primary, 'Synthetic Primary', '+971501234567')
        sql.execute('''insert into profiles(id,identity_id,display_name,phone_number,phone_verified)
            values (?,?,?,null,false)''', UUID.randomUUID(), pending, 'Synthetic Pending')
        sql.execute('''insert into profile_directory(identity_id,profile_id,shard_id,initialized,phone_number,phone_verified)
            select identity_id,id,'primary',true,phone_number,phone_verified from profiles''')
        sql.execute('''insert into profile_directory(identity_id,profile_id,shard_id,initialized,phone_number,phone_verified)
            values (?,?,'east',true,?,false)''', remote, UUID.randomUUID(), '+971521234567')
        sql.execute('''insert into profile_directory(identity_id,profile_id,shard_id,initialized,phone_number,phone_verified)
            values (?,?,'west',false,?,false)''', uninitialized, UUID.randomUUID(), '+971541234567')
        [(primary):'+971501234567',(remote):'+971521234567',(uninitialized):'+971541234567'].each { identity, phone ->
            sql.execute('insert into registration_phone_claims(identity_id,phone_number) values (?,?)', identity, phone)
        }
        sql.execute('''insert into phone_verification_audit(id,identity_id,phone_number,operator_reference,evidence_reference)
            values (?,?,?,'synthetic-operator','synthetic-evidence')''', UUID.randomUUID(), primary, '+971501234567')
        sql.execute('insert into phone_lookup_limits(requester_id,window_start,attempts) values (?,statement_timestamp(),3)', pending)
        def before = snapshots(sql)
        def history = sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        def constraints = metadata(sql)
        connection.close()

        when:
        def database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        database.transaction { current ->
            assert current.fetchOne('select version from flyway_schema_history order by installed_rank desc limit 1').get('version') == '8'
            assert current.fetch('select * from flyway_schema_history where version not in (?,?,?) order by installed_rank', '6', '7', '8').intoMaps() == history
            assert snapshots(current) == before
            assert metadata(current) == constraints
            assert current.fetchOne("""select count(*) as n from pg_trigger where
                tgrelid='profile_directory'::regclass and tgname='migration_directory_guard' and not tgisinternal""").get('n', Long) == 1
            ['verification_projection_current','verification_projection_operations','verification_projection_tombstones','verification_projection_writer_budgets'].each { table ->
                assert current.fetchOne('select count(*) as n from ' + table).get('n', Long) == 0
            }
            true
        }
        !VerificationProjectionConfiguration.from([:]).enabled()

        when: 'immutable placement must not become mutable after installing projection storage'
        database.transaction { it.execute("update profile_directory set shard_id='west' where identity_id=?", primary) }
        then:
        thrown(DataAccessException)
        database.transaction { snapshots(it) } == before

        when: 'published phone uniqueness must still reject a conflicting legacy profile'
        database.transaction { it.execute('''insert into profiles(id,identity_id,display_name,phone_number,phone_verified)
            values (?,?,?, ?,true)''', UUID.randomUUID(), UUID.randomUUID(), 'Synthetic Conflict', '+971501234567') }
        then:
        def duplicate = thrown(DataAccessException)
        duplicate.sqlState() == '23505'
        database.transaction { snapshots(it) } == before

        when:
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        database.ready()
        database.transaction { snapshots(it) } == before
        cleanup:
        connection?.close(); database?.close(); postgres?.stop()
    }

    private static Map snapshots(def sql) {
        [profiles:'identity_id',profile_directory:'identity_id',registration_phone_claims:'identity_id',
         phone_verification_audit:'id',phone_lookup_limits:'requester_id'].collectEntries { table, order ->
            [(table):sql.fetch('select row_to_json(t)::text as snapshot from ' + table + ' t order by ' + order).getValues('snapshot', String)]
        }
    }
    private static List metadata(def sql) {
        // Fixed owning-schema tables only; compare actual pre-existing indexes and trigger definitions.
        sql.fetch("""select tablename,indexname,indexdef from pg_indexes where schemaname='public'
            and tablename in ('profiles','profile_directory','registration_phone_claims') order by tablename,indexname""").intoMaps() +
        sql.fetch("""select tgname,pg_get_triggerdef(oid) as definition from pg_trigger
            where tgrelid='profile_directory'::regclass and not tgisinternal
              and tgname<>'migration_directory_guard' order by tgname""").intoMaps()
    }
}
