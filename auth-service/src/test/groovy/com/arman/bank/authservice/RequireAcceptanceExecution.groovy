package com.arman.bank.authservice

import java.lang.annotation.*
import org.opentest4j.TestAbortedException
import org.opentest4j.TestSkippedException
import org.spockframework.runtime.extension.*
import org.spockframework.runtime.model.SpecInfo

/** Local diagnostic: an unexpected abort is never acceptance evidence. */
@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE])
@ExtensionAnnotation(RequireAcceptanceExecution.Extension)
@interface RequireAcceptanceExecution {
    class Extension implements IAnnotationDrivenExtension<RequireAcceptanceExecution> {
        @Override
        void visitSpecAnnotation(RequireAcceptanceExecution annotation, SpecInfo spec) {
            def interceptor = new AbortInterceptor()
            spec.allFeatures.each { it.featureMethod.addInterceptor(interceptor) }
            [spec.setupMethods, spec.cleanupMethods, spec.setupSpecMethods, spec.cleanupSpecMethods]
                .each { methods -> methods.each { it.addInterceptor(interceptor) } }
        }
    }

    class AbortInterceptor implements IMethodInterceptor {
        @Override
        void intercept(IMethodInvocation invocation) throws Throwable {
            try {
                invocation.proceed()
            } catch (TestAbortedException | TestSkippedException abort) {
                // Never attach the original throwable: its message/cause/suppressed data may contain secrets.
                throw sanitized(abort)
            }
        }

        static AssertionError sanitized(Throwable abort) {
            StringBuilder diagnostic = new StringBuilder('Unexpected acceptance abort')
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>())
            Throwable current = abort
            int depth = 0
            while (current != null && depth++ < 8 && seen.add(current)) {
                diagnostic.append('\n').append(current.class.name.take(192))
                current.stackTrace.take(12).each { frame ->
                    diagnostic.append('\n').append(frame.className.take(192)).append('.').append(frame.methodName.take(128))
                        .append('.').append(frame.lineNumber)
                }
                current = current.cause
            }
            new AssertionError((Object) diagnostic.toString().take(8192))
        }
    }
}
