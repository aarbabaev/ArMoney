package com.arman.bank.authservice

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.IdentityVerification
import com.arman.bank.authservice.domain.Verification
import com.arman.bank.authservice.infrastructure.VerificationRoutes
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.javalin.Javalin
import java.net.http.*
import java.time.Instant
import spock.lang.Specification

/** Synthetic port/HTTP contracts only; persistence acceptance remains separate. */
class VerificationRoutesSpec extends Specification {
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final String KEY='synthetic-incoming-bridge-credential-unique'
    static final String PHONE='+971501234567'
    static final String EMAIL='fixture@example.test'
    def authority=Mock(IdentityVerificationStore)
    def legacy=Mock(VerificationStore)
    def client=HttpClient.newHttpClient()
    Javalin app
    Map binding(){[issuer:ISSUER,subject:'subject',client_id:'armoney-ios',provider_root_session_id:'root',provider_tab_id:'tab',authentication_session_id:Base64.urlEncoder.withoutPadding().encodeToString(new byte[32])]}
    void start(boolean modern=true){
        VerificationRoutes routes
        if(modern){
            def policy=IdentityVerification.Policy.defaults([ISSUER] as Set,['armoney-ios'] as Set,['sso-provider'] as Set)
            IdentityVerificationStore.Observations observer={flow,started->new IdentityVerification.Observation(flow,PHONE,EMAIL,PHONE,true,true,started.plusSeconds(60),started)} as IdentityVerificationStore.Observations
            routes=new VerificationRoutes(new IdentityVerificationService(authority,observer,policy),KEY)
        }else routes=new VerificationRoutes(new VerificationService(legacy,[ISSUER] as Set),KEY)
        app=Javalin.create{config->routes.configure(config)}.start(0)
    }
    def post(String path,Map body,String key=KEY){
        def builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}${path}")).header('Content-Type','application/json')
        if(key!=null)builder.header('X-ArMoney-SSO-Bridge-Key',key)
        def response=client.send(builder.POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body))).build(),HttpResponse.BodyHandlers.ofString())
        [status:response.statusCode(),json:new JsonSlurper().parseText(response.body()),cache:response.headers().firstValue('Cache-Control').orElse('')]
    }
    def cleanup(){if(app!=null)app.stop();client.close()}

    def 'authority enrollment returns exactly seven independent-version fields and explicit safe states'(){
        given:
        start()
        def command=UUID.randomUUID()
        def enrollment=UUID.randomUUID()
        def input=binding()+[command_id:command.toString(),phone_number:PHONE,email:EMAIL]
        def result=new Verification.Result(enrollment,null,null,7,state,Instant.now().plusSeconds(86400),null,0,null,true,false,7,2,4)
        when:
        def response=post('/internal/enrollments',input)
        then:
        1 * authority.admit('sso-provider',_) >> {Instant.now()}
        1 * authority.enroll('sso-provider',{it.commandId()==command&&it.flow().rootSessionId()=='root'&&it.flow().tabId()=='tab'},_,_) >> result
        response.status==200
        response.cache=='no-store'
        response.json.keySet()==['enrollment_id','version','state','email_contact_version','phone_contact_version','email_verified','phone_verified'] as Set
        response.json.version==7
        response.json.email_contact_version==2
        response.json.phone_contact_version==4
        response.json.state==state
        where:
        state << ['PENDING_CONTACTS','PROJECTION_PENDING','PROJECTED','SUSPENDED','BLOCKED_MIGRATION']
    }
    def 'explicit legacy constructor retains three-field binding and cannot accept authority payload'(){
        given:
        start(false)
        def old=[issuer:ISSUER,subject:'subject',authentication_session_id:'legacy-binding',command_id:UUID.randomUUID().toString(),phone_number:PHONE,email:EMAIL]
        when:
        def accepted=post('/internal/enrollments',old)
        def rejected=post('/internal/enrollments',binding()+[command_id:UUID.randomUUID().toString(),phone_number:PHONE,email:EMAIL])
        then:
        1 * legacy.enroll(_) >> new Verification.Result(UUID.randomUUID(),null,null,1,'PENDING_CONTACTS',Instant.now().plusSeconds(86400),null,0,null)
        accepted.status==200
        rejected.status==400
        0 * authority._
    }
    def 'authority never downgrades three-field binding or accepts old aliases extra authority or missing credential'(){
        given:
        start()
        def input=binding()+[command_id:UUID.randomUUID().toString(),phone_number:PHONE,email:EMAIL]
        if(change=='legacy'){input.remove('client_id');input.remove('provider_root_session_id');input.remove('provider_tab_id')}
        if(change=='alias'){input.root_session_id=input.remove('provider_root_session_id')}
        if(change=='epoch')input.verification_epoch=1
        when:
        def response=post('/internal/enrollments',input,key)
        then:
        response.status==expected
        0 * authority._
        0 * legacy._
        where:
        change   | key   | expected
        'legacy' | KEY   | 400
        'alias'  | KEY   | 400
        'epoch'  | KEY   | 400
        'none'   | null  | 401
        'none'   | 'bad' | 401
    }
    def 'ACTIVE and operation states cannot escape through authority enrollment result'(){
        given:
        start()
        when:
        def response=post('/internal/enrollments',binding()+[command_id:UUID.randomUUID().toString(),phone_number:PHONE,email:EMAIL])
        then:
        1 * authority.admit('sso-provider',_) >> {Instant.now()}
        1 * authority.enroll(_,_,_,_) >> new Verification.Result(UUID.randomUUID(),null,null,1,state,Instant.now().plusSeconds(60),null,0,null)
        response.status==503
        where:
        state << ['ACTIVE','VERIFIED','CONSUMED','PENDING_DELIVERY']
    }
    def 'challenge and consume retain their exact five and three-field operation responses'(){
        given:
        start()
        def enrollment=UUID.randomUUID()
        def challenge=UUID.randomUUID()
        def receipt=UUID.randomUUID()
        def expiry=Instant.now().plusSeconds(300)
        def resend=expiry.minusSeconds(240)
        def issue=binding()+[command_id:UUID.randomUUID().toString(),purpose:'REGISTRATION_PHONE',contact_version:4]
        def consume=binding()+[command_id:UUID.randomUUID().toString(),purpose:'REGISTRATION_PHONE',contact_version:4,code:'123456']
        when:
        def issued=post("/internal/enrollments/${enrollment}/challenges",issue)
        def consumed=post("/internal/enrollments/${enrollment}/challenges/${challenge}/consume",consume)
        then:
        2 * authority.admit('sso-provider',_) >> {Instant.now()}
        1 * authority.issue(_,_,_,_) >> new Verification.Result(enrollment,challenge,null,4,'PENDING_DELIVERY',expiry,resend,5,null,true,false,7,2,4)
        1 * authority.consume(_,_,_,_) >> new Verification.Result(enrollment,challenge,receipt,4,'CONSUMED',null,null,4,null,true,true,8,2,4)
        issued.status==200
        issued.json.keySet()==['challenge_id','state','expires_at','resend_at','attempts_remaining'] as Set
        issued.json.state=='PENDING_DELIVERY'
        issued.json.expires_at==expiry.toString()
        consumed.status==200
        consumed.json.keySet()==['receipt_id','contact_version','state'] as Set
        consumed.json.state=='CONSUMED'
        consumed.json.contact_version==4
    }
}
