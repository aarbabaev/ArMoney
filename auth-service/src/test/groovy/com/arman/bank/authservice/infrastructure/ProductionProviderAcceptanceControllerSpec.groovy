package com.arman.bank.authservice.infrastructure

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.http.*
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

class ProductionProviderAcceptanceControllerSpec extends Specification {
    static final String KEY='synthetic-private-production-controller-key'
    static final String USER='+971501234567'
    ProductionProviderAcceptanceController controller
    HttpClient client
    AtomicInteger calls=new AtomicInteger()
    Closure outcome={ deadline -> [registration_active:false,projection_acknowledged:false,bank_session_count:0,confirmed_event_ids:[]] }
    def setup(){
        controller=new ProductionProviderAcceptanceController(KEY,USER,new ProductionProviderAcceptanceController.Operations(){
            Map delivery(String purpose,ProductionProviderAcceptanceController.Deadline deadline){calls.incrementAndGet();throw new ProductionProviderAcceptanceController.Rejected(503)}
            Map outcomes(ProductionProviderAcceptanceController.Deadline deadline){calls.incrementAndGet();outcome.call(deadline)}
            void removePolicy(ProductionProviderAcceptanceController.Deadline deadline){calls.incrementAndGet()}
            void restorePolicy(ProductionProviderAcceptanceController.Deadline deadline){calls.incrementAndGet()}
        })
        controller.start('127.0.0.1',0)
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build()
    }
    def cleanup(){controller?.close();client?.close()}
    def post(String operation,Object body,String key=KEY,boolean duplicateKey=false){
        String json=body instanceof String?body:JsonOutput.toJson(body)
        def builder=HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+controller.port()+ProductionProviderAcceptanceController.PREFIX+operation))
            .timeout(Duration.ofSeconds(6)).header('Content-Type','application/json')
        if(key!=null)builder.header(ProductionProviderAcceptanceController.HEADER,key)
        if(duplicateKey)builder.header(ProductionProviderAcceptanceController.HEADER,KEY)
        client.send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString())
    }
    def 'authentication precedes malformed body and operation work'(){
        when:def response=post('outcomes','invalid json',key,duplicate)
        then:response.statusCode()==401;response.body()=='';calls.get()==0
        where:
        key << [null,'wrong-synthetic-key',KEY]
        duplicate << [false,false,true]
    }
    def 'capabilities exactly declares five supported operations without making authority calls'(){
        when:def response=post('capabilities',[:])
        then:
        response.statusCode()==200
        new JsonSlurper().parseText(response.body())==[version:2,production_provider_only:true,operations:['capabilities','outcomes','read_delivery','remove_policy','restore_policy']]
        response.headers().firstValue('Cache-Control').orElse('')=='no-store'
        calls.get()==0
    }
    def 'exact schema rejects malformed duplicate trailing foreign owner and invalid purpose'(){
        when:def response=post(operation,body)
        then:response.statusCode()==400;response.body()=='';calls.get()==0
        where:
        operation | body
        'capabilities' | '{"extra":true}'
        'capabilities' | '{} {}'
        'capabilities' | '[]'
        'outcomes' | '{"username":"+971501234567","username":"+971501234567"}'
        'outcomes' | '{"username":null}'
        'outcomes' | '{"username":"+971521234567"}'
        'read_delivery' | '{"username":"+971501234567","purpose":"LEGACY_MIGRATION_PHONE"}'
        'remove_policy' | '{"url":"https://caller.invalid"}'
        'restore_policy' | '{"enabled":true}'
    }
    def 'policy responses match driver lowercase closed schema'(){
        expect:
        new JsonSlurper().parseText(post('remove_policy',[:]).body())==[state:'disabled']
        new JsonSlurper().parseText(post('restore_policy',[:]).body())==[state:'restored']
        calls.get()==2
    }
    def 'version two outcomes returns complete sorted event inventory including zero and sixteen'(){
        given:
        def ids=(1..16).collect{String.format('00000000-0000-4000-8000-%012d',it)}
        outcome={ deadline -> [registration_active:true,projection_acknowledged:true,bank_session_count:2,confirmed_event_ids:ids.reverse()] }
        when:def response=post('outcomes',[username:USER])
        then:
        response.statusCode()==200
        new JsonSlurper().parseText(response.body())==[registration_active:true,projection_acknowledged:true,bank_session_count:2,confirmed_event_ids:ids.sort(false)]
        when:
        outcome={ deadline -> [registration_active:false,projection_acknowledged:false,bank_session_count:0,confirmed_event_ids:[]] }
        def empty=post('outcomes',[username:USER])
        then:new JsonSlurper().parseText(empty.body()).confirmed_event_ids==[]
    }
    def 'version two inventory rejects duplicate malformed noncanonical nonstring and overflow events'(){
        given:outcome={ deadline -> [registration_active:true,projection_acknowledged:true,bank_session_count:0,confirmed_event_ids:events] }
        when:def response=post('outcomes',[username:USER])
        then:response.statusCode()==503;response.body()==''
        where:
        events << [null,'not-an-array',[null],[42],['not-a-uuid'],['1-1-1-1-1'],['AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA'],
            ['00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000001'],
            (1..17).collect{String.format('00000000-0000-4000-8000-%012d',it)}]
    }
    def 'version one singular event response is rejected instead of silently adapted'(){
        given:outcome={ deadline -> [registration_active:false,projection_acknowledged:false,bank_session_count:0,confirmed_event_id:null] }
        when:def response=post('outcomes',[username:USER])
        then:response.statusCode()==503;response.body()==''
    }
    def 'operation deadline cancels blocked work and never emits stale success'(){
        given:
        def interrupted=new CountDownLatch(1)
        outcome={ deadline -> try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);[:]}catch(InterruptedException cancelled){interrupted.countDown();Thread.currentThread().interrupt();throw new ProductionProviderAcceptanceController.Rejected(503)} }
        long started=System.nanoTime()
        when:def response=post('outcomes',[username:USER])
        then:
        response.statusCode()==503;response.body()==''
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<5500
        interrupted.await(1,TimeUnit.SECONDS)
    }
    def 'startup refuses unowned environment before allocating resources'(){
        when:ProductionProviderAcceptanceMain.validate([:])
        then:thrown(ProductionProviderAcceptanceController.Rejected)
    }
    def 'standalone SMTP helper captures an actually accepted TLS message without authority fixture classes'(){
        given:
        def mail=new EmailConfiguration([LOGIN_NOTIFICATIONS_ENABLED:'true',MAILTRAP_SMTP_USERNAME:'synthetic-local-smtp-user',
            MAILTRAP_SMTP_PASSWORD:'synthetic-local-smtp-password',MAILTRAP_FROM_EMAIL:'sender@example.test'])
        def smtp=new ProductionProviderAcceptanceTransport.LocalSmtp(mail.smtpUsername,mail.smtpPassword)
        smtp.recipient('recipient@example.test')
        def sender=new MailtrapSmtp(mail,'localhost',smtp.port(),smtp.clientTls.socketFactory,3000)
        def message=VerificationEmail.render('314159')
        when:
        def result=sender.send('recipient@example.test',message.subject(),message.text(),message.html())
        then:
        result==com.arman.bank.authservice.application.NotificationDelivery.Result.ACCEPTED
        smtp.code('recipient@example.test')=='314159'
        cleanup:smtp?.close()
    }
}
