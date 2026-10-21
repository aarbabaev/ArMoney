package com.arman.bank.authservice

import org.opentest4j.TestAbortedException
import org.opentest4j.TestSkippedException
import org.spockframework.runtime.extension.IMethodInvocation
import spock.lang.Specification

class RequireAcceptanceExecutionSpec extends Specification {
    def 'abort becomes failure without leaking message cause suppressed or file names #kind'() {
        given:
        Throwable secretCause = new IllegalStateException('CANARY_CAUSE')
        Throwable abort = kind == 'aborted' ? new TestAbortedException('CANARY_ABORT', secretCause) : new TestSkippedException('CANARY_ABORT', secretCause)
        abort.addSuppressed(new RuntimeException('CANARY_SUPPRESSED'))
        abort.stackTrace = [new StackTraceElement('safe.Feature', 'run', 'CANARY_FILE', 42)] as StackTraceElement[]
        abort.cause.stackTrace = [new StackTraceElement('safe.Helper', 'work', 'CANARY_FILE', 7)] as StackTraceElement[]
        def invocation = Mock(IMethodInvocation)
        1 * invocation.proceed() >> { throw abort }

        when:
        new RequireAcceptanceExecution.AbortInterceptor().intercept(invocation)

        then:
        AssertionError failure = thrown()
        failure.message.contains(abort.class.name)
        failure.message.contains('java.lang.IllegalStateException')
        failure.message.contains('safe.Feature.run.42')
        failure.message.contains('safe.Helper.work.7')
        !failure.toString().contains('CANARY')
        failure.cause == null
        failure.suppressed.length == 0

        where:
        kind << ['aborted', 'skipped']
    }

    def 'diagnostic has bounded chain frames and total length'() {
        given:
        Throwable tail = null
        20.times {
            tail = new RuntimeException('unused', tail)
            tail.stackTrace = (1..50).collect { new StackTraceElement('x' * 1000, 'y' * 1000, 'unused', it) } as StackTraceElement[]
        }
        Throwable abort = new TestAbortedException('unused', tail)
        when:
        def failure = RequireAcceptanceExecution.AbortInterceptor.sanitized(abort)
        then:
        failure.message.length() <= 8192
        failure.cause == null
        failure.suppressed.length == 0
    }

    def 'ordinary failure is preserved by identity'() {
        given:
        def original = new IllegalStateException('ordinary')
        def invocation = Mock(IMethodInvocation)
        1 * invocation.proceed() >> { throw original }
        when:
        new RequireAcceptanceExecution.AbortInterceptor().intercept(invocation)
        then:
        IllegalStateException failure = thrown()
        failure.is(original)
    }

    def 'normal invocation proceeds exactly once'() {
        given:
        def invocation = Mock(IMethodInvocation)
        when:
        new RequireAcceptanceExecution.AbortInterceptor().intercept(invocation)
        then:
        1 * invocation.proceed()
        noExceptionThrown()
    }
}
