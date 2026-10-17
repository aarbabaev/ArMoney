package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.LoginProof
import spock.lang.Specification
import java.util.concurrent.*

/** Pure capability lifetime checks; PostgreSQL ownership is proved separately. */
class LoginOperationGateSpec extends Specification {
    def 'closed operation capability cannot escape or reopen'(){
        given: def scope=new PostgresLoginOperationGate.Scope()
        when: scope.check();scope.close();scope.check()
        then: def failure=thrown(LoginProof.Failure);failure.error()==LoginProof.Error.UNAVAILABLE
        when: scope.close()
        then: thrown(LoginProof.Failure)
    }
    def 'another thread cannot use or close the owner capability'(){
        given: def scope=new PostgresLoginOperationGate.Scope();def pool=Executors.newSingleThreadExecutor()
        when: pool.submit({if(close)scope.close();else scope.check();null} as Callable).get(5,TimeUnit.SECONDS)
        then: def failure=thrown(ExecutionException);failure.cause instanceof LoginProof.Failure
        when: scope.check();scope.close()
        then: noExceptionThrown()
        cleanup: pool.shutdownNow()
        where: close << [false,true]
    }
}
