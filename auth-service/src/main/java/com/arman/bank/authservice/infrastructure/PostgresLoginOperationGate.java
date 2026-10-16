package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.runtime.Database;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.function.Function;
import org.jooq.DSLContext;

/** Dormant authorized-operation admission, not a physical orphan-HTTP worker bound.
 * The caller owns one PRIMARY session for the entire scope; no connection is acquired here.
 * Session/transaction pooling is unsupported. No production caller selects this gate yet. */
public final class PostgresLoginOperationGate {
    private static final int NAMESPACE=0x41524D4F;
    private static final int SLOTS=16;
    private record Backend(long database,int pid,Instant started){}

    /** A completed transaction callback followed by commit/restore failure proves no rollback.
     * Callers must withhold token release and reconcile only their ORIGINAL durable operation ID. */
    public static final class UncertainCommit extends RuntimeException {
        private UncertainCommit(){super("login_operation_commit_outcome_unknown");}
    }
    static final class Scope {
        private final Thread owner=Thread.currentThread();
        private boolean closed;
        void check(){if(closed||Thread.currentThread()!=owner)throw unavailable();}
        void close(){check();closed=true;}
    }
    public <T>T withPermit(Database.OwnedSession session,Function<Permit,T> operation){
        Objects.requireNonNull(session);Objects.requireNonNull(operation);
        Permit permit=null;Throwable primary=null;
        try{
            permit=acquire(session);
            T result=operation.apply(permit);
            permit.verify();
            return result;
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{
            if(permit!=null){
                try{permit.release();}
                catch(RuntimeException|Error cleanup){if(primary==null)throw cleanup;primary.addSuppressed(cleanup);}
            }
        }
    }
    private Permit acquire(Database.OwnedSession session){
        try{
            return session.autocommit(sql->{
                Backend backend=backend(sql);
                // Includes unknown/out-of-range slots and shared advisory locks in this namespace.
                if(owned(sql)!=0)throw unavailable();
                for(int slot=0;slot<SLOTS;slot++){
                    boolean acquired=Boolean.TRUE.equals(sql.fetchOne("select pg_try_advisory_lock(?,?) as acquired",NAMESPACE,slot).get("acquired",Boolean.class));
                    if(acquired){
                        var permit=new Permit(session,backend,slot);
                        permit.verify(sql);
                        return permit;
                    }
                }
                if(!backend.equals(backend(sql))||owned(sql)!=0)throw unavailable();
                throw new LoginProof.Failure(LoginProof.Error.RATE_LIMITED);
            });
        }catch(LoginProof.Failure denied){
            if(denied.error()!=LoginProof.Error.RATE_LIMITED)invalidate(session,denied);
            throw denied;
        }catch(RuntimeException|Error failed){invalidate(session,failed);throw failed;}
    }
    public static final class Permit {
        private final Database.OwnedSession session;
        private final Backend backend;
        private final int slot;
        private final Scope scope=new Scope();
        private Permit(Database.OwnedSession session,Backend backend,int slot){this.session=session;this.backend=backend;this.slot=slot;}
        public void verify(){scope.check();session.autocommit(sql->{verify(sql);return null;});}
        public <T>T autocommit(Function<DSLContext,T> action){
            Objects.requireNonNull(action);scope.check();
            return session.autocommit(sql->{verify(sql);T value=action.apply(sql);verify(sql);return value;});
        }
        public <T>T transaction(Function<DSLContext,T> action){
            Objects.requireNonNull(action);scope.check();boolean[] completed={false};
            try{
                return session.transaction(sql->{verify(sql);T value=action.apply(sql);verify(sql);completed[0]=true;return value;});
            }catch(RuntimeException failure){
                if(completed[0]){
                    var uncertain=new UncertainCommit();uncertain.addSuppressed(failure);invalidate(session,uncertain);throw uncertain;
                }
                throw failure;
            }
        }
        private void verify(DSLContext sql){
            scope.check();
            if(!backend.equals(backend(sql)))throw unavailable();
            var locks=sql.fetchOne("""
                select count(*) as total,count(*) filter(where objid::bigint=? and granted and mode='ExclusiveLock') as exact
                from pg_locks where locktype='advisory' and pid=pg_backend_pid()
                 and database=cast(? as oid) and classid=cast(? as oid) and objsubid=2
                """,slot,backend.database(),NAMESPACE);
            if(locks==null||locks.get("total",Long.class)!=1||locks.get("exact",Long.class)!=1)throw unavailable();
        }
        private void release(){
            try{
                scope.check();
                session.autocommit(sql->{
                    if(!backend.equals(backend(sql)))throw unavailable();
                    if(!Boolean.TRUE.equals(sql.fetchOne("select pg_advisory_unlock(?,?) as released",NAMESPACE,slot).get("released",Boolean.class)))throw unavailable();
                    if(owned(sql)!=0)throw unavailable();
                    return null;
                });
            }catch(RuntimeException|Error cleanup){invalidate(session,cleanup);throw cleanup;}
            finally{scope.close();}
        }
        @Override public String toString(){return "LoginOperationPermit[redacted]";}
    }
    private static Backend backend(DSLContext sql){
        var row=sql.fetchOne("""
            select d.oid::bigint as database_id,a.pid,a.backend_start,pg_is_in_recovery() as recovery,
              current_setting('transaction_read_only') as read_only
            from pg_database d cross join pg_stat_activity a
            where d.datname=current_database() and a.pid=pg_backend_pid()
            """);
        if(row==null||Boolean.TRUE.equals(row.get("recovery",Boolean.class))||!"off".equals(row.get("read_only",String.class)))throw unavailable();
        return new Backend(row.get("database_id",Long.class),row.get("pid",Integer.class),row.get("backend_start",OffsetDateTime.class).toInstant());
    }
    private static long owned(DSLContext sql){
        return sql.fetchOne("""
            select count(*) from pg_locks where locktype='advisory' and pid=pg_backend_pid()
             and database=(select oid from pg_database where datname=current_database())
             and classid=cast(? as oid) and objsubid=2
            """,NAMESPACE).get(0,Long.class);
    }
    private static void invalidate(Database.OwnedSession session,Throwable primary){try{session.invalidate();}catch(RuntimeException|Error cleanup){primary.addSuppressed(cleanup);}}
    private static LoginProof.Failure unavailable(){return new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);}
}
