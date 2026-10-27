package com.arman.bank.userservice
import com.arman.bank.runtime.Database
import com.arman.bank.userservice.application.*
import com.arman.bank.userservice.infrastructure.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

class LiveUserProjectionUpgradeIntegrationSpec extends Specification {
    def 'V7 to V8 adds only empty immutable custody and preserves earlier history'(){
        given:def pg=new PostgreSQLContainer('postgres:17.6-alpine');pg.start()
        Flyway.configure().dataSource(pg.jdbcUrl,pg.username,pg.password).locations('classpath:db/migration').target('7').load().migrate()
        def before=DriverManager.getConnection(pg.jdbcUrl,pg.username,pg.password).withCloseable{connection->connection.createStatement().withCloseable{statement->statement.executeQuery('select row_to_json(t)::text from flyway_schema_history t order by installed_rank').withCloseable{rs->def rows=[];while(rs.next())rows.add(rs.getString(1));rows}}}
        when:def database=new Database(pg.jdbcUrl,pg.username,pg.password)
        then:database.transaction{sql->sql.fetch("select row_to_json(t)::text from flyway_schema_history t where version<>'8' order by installed_rank").getValues(0,String)==before}
        database.transaction{sql->sql.fetchOne('select count(*) from live_user_projection_commands').get(0,Long)==0L}
        when:
        def c=LiveUserRevisionSpec.command()
        def p=new PostgresVerificationProjectionStore(database,{writer,r,start->LiveUserRevisionSpec.negativeClassification(r)} as UserMutationSuspensionPort,LiveUserRevisionSpec.WRITER)
        p.commit('auth',c)
        database.transaction{sql->sql.execute('delete from live_user_projection_commands')}
        then:thrown(org.jooq.exception.DataAccessException)
        cleanup:database?.close();pg?.stop()
    }
}
