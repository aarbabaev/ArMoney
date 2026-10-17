package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.CodeIntentPairScopes
import groovy.json.JsonOutput
import spock.lang.Specification

class CodeIntentPairScopesSpec extends Specification {
    static Map pair(String issuer='https://issuer.example/sso/realms/armoney',String client='armoney-web'){[issuer:issuer,client_id:client]}
    static String json(List values){JsonOutput.toJson(values)}
    def 'exact relation never authorizes Cartesian crossings unknown or legacy as managed'(){
        given:
        def a='https://a.example/sso/realms/armoney';def b='https://b.example/sso/realms/armoney'
        def scopes=new CodeIntentPairScopes(json([pair(a,'armoney-web'),pair(b,'armoney-ios')]),json([pair(a,'armoney-android')]))
        expect:
        scopes.classify(a,'armoney-web')==CodeIntentPairScopes.Classification.MANAGED
        scopes.classify(a,'armoney-android')==CodeIntentPairScopes.Classification.LEGACY
        scopes.classify(a,'armoney-ios')==CodeIntentPairScopes.Classification.UNKNOWN
        scopes.classify(b,'armoney-web')==CodeIntentPairScopes.Classification.UNKNOWN
        scopes.classify(null,'armoney-web')==CodeIntentPairScopes.Classification.UNKNOWN
        scopes.managedIssuers()==[a,b] as Set
        scopes.managedClients()==['armoney-web','armoney-ios'] as Set
        when:scopes.requireManaged(a,'armoney-android')
        then:thrown(IllegalArgumentException)
        when:scopes.managed().clear()
        then:thrown(UnsupportedOperationException)
    }
    def 'strict supplied arrays reject malformed null unknown duplicated or trailing structure'(){
        when:new CodeIntentPairScopes(raw,'[]')
        then:thrown(IllegalArgumentException)
        where:raw << [null,'','null','{}','true','[null]','[[{}]]','[1]','[] []','/*x*/[]',
            '[{"issuer":"https://a.example","issuer":"https://b.example","client_id":"armoney-web"}]',
            json([pair()+[subject:'forbidden']]),json([[issuer:null,client_id:'armoney-web']]),json([[issuer:'https://a.example',client_id:1]]),
            json([pair(),pair()])]
    }
    def 'pair bounds whitespace URI clients and surrogates reject without normalization'(){
        when:new CodeIntentPairScopes(json([pair(issuer,client)]),'[]')
        then:thrown(IllegalArgumentException)
        where:
        issuer | client
        'http://issuer.example' | 'armoney-web'
        'https://issuer.example/' | 'armoney-web'
        'https://user@issuer.example/realm' | 'armoney-web'
        'https://issuer.example/realm?q=1' | 'armoney-web'
        'https://issuer.example/realm#fragment' | 'armoney-web'
        ' https://issuer.example' | 'armoney-web'
        'https://issuer.example/'+'a'*500 | 'armoney-web'
        'https://issuer.example/realm' | 'armoney-migration'
        'https://issuer.example/realm' | 'armoney web'
        'https://issuer.example/realm' | 'x'*129
        'https://issuer.example/realm' | 'bad\u2003client'
        'https://issuer.example/realm' | 'bad\uD800client'
        'https://issuer.example/realm' | 'bad\uDC00client'
        'https://issuer.example/realm\u00a0suffix' | 'armoney-web'
        'https://issuer.example/realm\u202fsuffix' | 'armoney-web'
        'https://issuer.example/realm\u2007suffix' | 'armoney-web'
        'https://issuer.example:0/realm' | 'armoney-web'
        'https://issuer.example:65536/realm' | 'armoney-web'
    }
    def 'nonwhitespace Unicode issuer path remains valid without normalization'(){
        given:String issuer='https://issuer.example/realm/caf\u00e9'
        def scopes=new CodeIntentPairScopes(json([pair(issuer)]),'[]')
        expect:scopes.classify(issuer,'armoney-web')==CodeIntentPairScopes.Classification.MANAGED
        scopes.managed().first().issuer()==issuer
    }
    def 'issuer default and exact valid port bounds are preserved #port'(){
        given:String issuer='https://issuer.example'+port+'/realm'
        expect:new CodeIntentPairScopes(json([pair(issuer)]),'[]').classify(issuer,'armoney-web')==CodeIntentPairScopes.Classification.MANAGED
        where:port << ['',':1',':65535']
    }
    def 'malformed credential bearing pair URI never escapes in message cause or stack trace'(){
        given:String secret='synthetic-private-user-password'
        when:new CodeIntentPairScopes(json([pair('https://user:'+secret+'@[broken')]),'[]')
        then:
        def rejected=thrown(IllegalArgumentException)
        rejected.message=='Invalid code intent pair scope';rejected.cause==null
        def trace=new StringWriter();rejected.printStackTrace(new PrintWriter(trace))
        !trace.toString().contains(secret);!trace.toString().contains('https://user:')
    }
    def 'array byte and count bounds and cross-scope overlap are enforced while empty arrays remain valid'(){
        expect:new CodeIntentPairScopes('[]','[]').managed().isEmpty()
        when:new CodeIntentPairScopes(json([pair()]),json([pair()]))
        then:thrown(IllegalArgumentException)
        when:new CodeIntentPairScopes(json((1..17).collect{pair('https://issuer.example','c'+it)}),'[]')
        then:thrown(IllegalArgumentException)
        when:new CodeIntentPairScopes(' '*16385+'[]','[]')
        then:thrown(IllegalArgumentException)
        when:new CodeIntentPairScopes(json((1..16).collect{pair('https://a'+it+'.example','c'+it)}),json([pair('https://seventeenth.example','other')]))
        then:thrown(IllegalArgumentException)
    }
    def 'exact configured upper bounds accept without widening the relation'(){
        given:String base='https://issuer.example/'
        String issuer=base+'x'*(512-base.length())
        expect:
        new CodeIntentPairScopes(json([pair(issuer,'c'*128)]),'[]').managed().size()==1
        new CodeIntentPairScopes(json((1..16).collect{pair('https://issuer.example','c'+it)}),'[]').managed().size()==16
        new CodeIntentPairScopes(' '*16382+'[]','[]').managed().isEmpty()
    }
}
