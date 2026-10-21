package com.arman.bank.authservice.infrastructure

import spock.lang.Specification
import groovy.transform.CompileStatic

/** Pure own-row comparison semantics only; not promotion authority or PostgreSQL acceptance. */
class MigrationBankingPromotionSpec extends Specification {
    private static Map normalized(Map input){
        def method=PostgresMigrationBankingEligibilityStore.getDeclaredMethod('normalizedMap',Map)
        method.accessible=true
        method.invoke(null,input) as Map
    }
    /** Invoke Java Map.equals directly: dynamic Groovy Map.equals numerically coerces values. */
    @CompileStatic
    private static boolean sameNormalized(Map left,Map right){
        Map actual=normalized(left)
        Map expected=normalized(right)
        return ((Object)actual).equals((Object)expected)
    }
    def 'equal four integral wrappers normalize to exact signed64 scalar values'(){
        expect:
        sameNormalized([generation:value],[generation:1L])
        where:
        value << [Byte.valueOf((byte)1),Short.valueOf((short)1),Integer.valueOf(1),Long.valueOf(1L)]
    }
    def 'numeric inequality and nonintegral representations remain distinct'(){
        expect:
        !sameNormalized([generation:value],[generation:1L])
        where:
        value << [2L,Double.valueOf(1D),Float.valueOf(1F),BigDecimal.ONE,BigInteger.ONE]
    }
    def 'comparison preserves exact fields nulls and signed64 endpoints'(){
        expect:
        normalized([generation:Long.MAX_VALUE]).generation==Long.MAX_VALUE
        normalized([generation:Long.MIN_VALUE]).generation==Long.MIN_VALUE
        !sameNormalized([generation:1L],[other:1L])
        !sameNormalized([generation:1L],[generation:1L,extra:null])
        !sameNormalized([generation:null],[generation:1L])
    }
}
