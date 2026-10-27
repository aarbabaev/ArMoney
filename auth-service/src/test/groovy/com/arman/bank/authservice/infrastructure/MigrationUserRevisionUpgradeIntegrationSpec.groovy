package com.arman.bank.authservice.infrastructure

import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

/** Populated synthetic metadata upgrade, not User classification or banking authority. */
class MigrationUserRevisionUpgradeIntegrationSpec extends Specification {
    def 'V21 preserves original V20 identity session history and adds empty immutable receipt custody'(){
        given:
        def pg=new PostgreSQLContainer('postgres:17.6-alpine');pg.start()
        Flyway.configure().dataSource(pg.jdbcUrl,pg.username,pg.password).locations('classpath:db/migration').target('20').load().migrate()
        def connection=DriverManager.getConnection(pg.jdbcUrl,pg.username,pg.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        def owner=UUID.randomUUID()
        sql.execute('insert into identities(id) values(?)',owner)
        sql.execute("insert into sessions(token_hash,identity_id,expires_at) values(?,?,clock_timestamp()+interval '1 hour')",'a'*64,owner)
        def before=sql.fetch('select installed_rank,version,description,script,checksum,success from flyway_schema_history order by installed_rank').intoMaps()
        def identity=sql.fetchOne('select * from identities where id=?',owner).intoMap()
        def session=sql.fetchOne('select * from sessions where identity_id=?',owner).intoMap()
        when:
        def database=new Database(pg.jdbcUrl,pg.username,pg.password)
        then:
        sql.fetch('select installed_rank,version,description,script,checksum,success from flyway_schema_history where installed_rank<=? order by installed_rank',before.size()).intoMaps()==before
        sql.fetchOne('select * from identities where id=?',owner).intoMap()==identity
        sql.fetchOne('select * from sessions where identity_id=?',owner).intoMap()==session
        sql.fetchOne('select count(*) from auth_user_mutation_receipts').get(0,Long)==0L
        cleanup:
        database?.close();connection?.close();pg?.stop()
    }
}
