package com.arman.bank.ledgerservice.infrastructure

import com.arman.bank.runtime.Database
import com.arman.bank.ledgerservice.infrastructure.*
import org.testcontainers.containers.*
import org.testcontainers.containers.wait.strategy.Wait
import org.jooq.impl.DSL
import org.jooq.SQLDialect
import spock.lang.Specification
import java.sql.DriverManager
import java.time.Duration

class LedgerReadsIntegrationSpec extends Specification {
    def "physical standby reads alternate catch up and fall back on paused replay outage or promoted endpoint"() {
        given:
        def network = Network.newNetwork()
        def pg = new PostgreSQLContainer('postgres:17.6-alpine')
            .withNetwork(network).withNetworkAliases('primary')
            .withEnv('POSTGRES_HOST_AUTH_METHOD', 'trust')
            .withCommand('postgres', '-c', 'cluster_name=primary')
        pg.start()
        pg.execInContainer('sh', '-c', 'echo "host replication all all trust" >> "$PGDATA/pg_hba.conf"')
        def db = new Database(pg.jdbcUrl, pg.username, pg.password)
        db.transaction { it.execute('select pg_reload_conf()') }
        def replicas = []
        def urls = []
        (1..2).each { n ->
            def replica = new GenericContainer('postgres:17.6-alpine')
                .withNetwork(network).withExposedPorts(5432)
                .withEnv('PGDATA', '/tmp/standby')
                .withCommand('sh', '-c', "mkdir -m 700 /tmp/standby && pg_basebackup -c fast -h primary -U ${pg.username} -D /tmp/standby -R && chown -R postgres:postgres /tmp/standby && exec docker-entrypoint.sh postgres -c cluster_name=replica${n}")
                .waitingFor(Wait.forLogMessage('.*database system is ready to accept read-only connections.*\\n', 1))
                .withStartupTimeout(Duration.ofSeconds(60))
            replicas.add(replica)
            replica.start()
            urls.add("jdbc:postgresql://${replica.host}:${replica.getMappedPort(5432)}/${pg.databaseName}")
        }
        def reads = new LedgerReads(db, urls.join(','), pg.username, pg.password)
        def store = new PostgresLedger(db, reads)
        def owner = UUID.randomUUID()
        def account = store.open(owner, UUID.randomUUID(), 'AED')
        def source = { reads.read { it.fetchOne("select current_setting('cluster_name')").get(0, String) } }

        expect: 'constructing standby pools runs no Flyway writes and immediate reads see primary commits'
        store.account(owner, account.id()).get() == account
        [source(), source()].toSet() == ['replica1', 'replica2'].toSet()
        store.account(UUID.randomUUID(), account.id()).isEmpty()

        when: 'both physical replicas stop applying WAL before a new primary commit'
        def connections = urls.collect { DriverManager.getConnection(it, pg.username, pg.password) }
        assert connections.every { !LedgerReads.writablePrimary(DSL.using(it, SQLDialect.POSTGRES)) }
        connections.each { DSL.using(it, SQLDialect.POSTGRES).execute('select pg_wal_replay_pause()') }
        def fresh = store.open(owner, UUID.randomUUID(), 'AED')
        long pausedStart = System.nanoTime()
        def visible = store.account(owner, fresh.id())
        long pausedElapsed = System.nanoTime() - pausedStart

        then: 'the WAL fence prevents stale not-found and routes to primary within a bound'
        visible.get() == fresh
        pausedElapsed < Duration.ofSeconds(3).toNanos()
        source() == 'primary'

        when:
        connections.each { DSL.using(it, SQLDialect.POSTGRES).execute('select pg_wal_replay_resume()') }
        Thread.sleep(500)

        then:
        [source(), source()].toSet() == ['replica1', 'replica2'].toSet()

        when: 'a read URL selects another database in the same physical cluster'
        def wrongDatabase = new LedgerReads(db, urls[0].replace('/' + pg.databaseName, '/postgres'), pg.username, pg.password)

        then: 'matching cluster and timeline cannot authorize reads from another database'
        wrongDatabase.read { it.fetchOne('select current_database()').get(0, String) } == pg.databaseName

        when: 'a configured physical standby belongs to another cluster on the same timeline'
        def foreignPrimary = new PostgreSQLContainer('postgres:17.6-alpine')
            .withNetwork(network).withNetworkAliases('foreign-primary')
            .withUsername(pg.username).withPassword(pg.password).withDatabaseName(pg.databaseName)
            .withEnv('POSTGRES_HOST_AUTH_METHOD', 'trust')
        foreignPrimary.start()
        foreignPrimary.execInContainer('sh', '-c', 'echo "host replication all all trust" >> "$PGDATA/pg_hba.conf"')
        def foreignConnection = DriverManager.getConnection(foreignPrimary.jdbcUrl, pg.username, pg.password)
        def foreignSql = DSL.using(foreignConnection, SQLDialect.POSTGRES)
        foreignSql.execute('select pg_reload_conf()')
        def foreignReplica = new GenericContainer('postgres:17.6-alpine')
            .withNetwork(network).withExposedPorts(5432).withEnv('PGDATA', '/tmp/standby')
            .withCommand('sh', '-c', "mkdir -m 700 /tmp/standby && pg_basebackup -c fast -h foreign-primary -U ${pg.username} -D /tmp/standby -R && chown -R postgres:postgres /tmp/standby && exec docker-entrypoint.sh postgres -c cluster_name=foreign-replica")
            .waitingFor(Wait.forLogMessage('.*database system is ready to accept read-only connections.*\\n', 1))
            .withStartupTimeout(Duration.ofSeconds(60))
        foreignReplica.start()
        def foreignUrl = "jdbc:postgresql://${foreignReplica.host}:${foreignReplica.getMappedPort(5432)}/${pg.databaseName}"
        def foreignReads = new LedgerReads(db, foreignUrl, pg.username, pg.password)

        then: 'the foreign system identifier prevents serving its data and no migrations run there'
        foreignSql.fetchOne('select system_identifier::text from pg_control_system()').get(0, String) !=
            db.transaction { it.fetchOne('select system_identifier::text from pg_control_system()').get(0, String) }
        foreignReads.read { it.fetchOne("select current_setting('cluster_name')").get(0, String) } == 'primary'
        foreignSql.fetchOne("select to_regclass('public.flyway_schema_history')::text").get(0, String) == null

        when: 'a former physical standby is promoted independently'
        DSL.using(connections[0], SQLDialect.POSTGRES).execute('select pg_promote(true, 5)')

        then: 'the promoted endpoint is rejected even though its replay LSN was sufficient'
        [source(), source()].toSet() == ['primary', 'replica2'].toSet()

        when: 'standbys are unavailable'
        connections.each { it.close() }
        replicas.each { it.stop() }
        long outageStart = System.nanoTime()
        def outageSource = source()
        long outageElapsed = System.nanoTime() - outageStart

        then:
        outageSource == 'primary'
        outageElapsed < Duration.ofSeconds(3).toNanos()

        when: 'a configured read endpoint is actually a writable primary'
        def wrong = new LedgerReads(db, pg.jdbcUrl, pg.username, pg.password)

        then:
        wrong.read { it.fetchOne("select current_setting('transaction_read_only')").get(0, String) } == 'off'
        reads.ready()

        cleanup:
        foreignReads?.close()
        foreignConnection?.close()
        foreignReplica?.stop()
        foreignPrimary?.stop()
        wrongDatabase?.close()
        wrong?.close()
        connections?.each { it.close() }
        reads?.close()
        replicas?.each { it.stop() }
        db?.close()
        pg?.stop()
        network?.close()
    }
}
