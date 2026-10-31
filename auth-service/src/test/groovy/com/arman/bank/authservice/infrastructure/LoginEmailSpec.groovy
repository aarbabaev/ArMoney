package com.arman.bank.authservice.infrastructure
import spock.lang.Specification
import java.time.Instant
class LoginEmailSpec extends Specification {
    def 'plain and HTML show actual UTC only and escape optional profile presentation'(){
        when:def email=LoginEmail.render(Instant.parse('2026-10-31T09:10:11Z'),'<Alice & "Bob">')
        then:email.text().contains('2026-10-31T09:10:11Z (UTC)')
        email.text().contains('<Alice & "Bob">')
        email.html().contains('&lt;Alice &amp; &quot;Bob&quot;&gt;')
        !email.html().contains('<Alice')
        !email.html().contains('http')
        !email.text().contains('IP')
    }
    def 'missing invalid or oversized display label uses generic greeting'(){
        when:def email=LoginEmail.render(Instant.EPOCH,name)
        then:email.text().startsWith('Hello,\n')
        email.html().contains('<p>Hello,</p>')
        where:name<<[null,'','  ','x'*101,'bad\r\nname']
    }
    def 'dedicated gate off allocates no resources even with OTP SMTP parser selected'(){
        given:def env=[LOGIN_NOTIFICATIONS_ENABLED:'true']
        def config=new LoginNotificationConfiguration(env)
        expect:!config.enabled
        LoginNotificationResources.open(null,config,null,null).empty
        env==[LOGIN_NOTIFICATIONS_ENABLED:'true']
    }
    def 'enabled requires managed configuration and delivery independent of existing OTP flag'(){
        given:def env=[AUTH_LOGIN_NOTIFICATIONS_ENABLED:'true',AUTH_MANAGED_BANKING_ENABLED:'true',LOGIN_NOTIFICATIONS_ENABLED:'false',USER_BASE_URL:'http://user-service:8080',INTERNAL_AUTH_KEY:'k'*43,MAILTRAP_SMTP_USERNAME:'synthetic',MAILTRAP_SMTP_PASSWORD:'synthetic-password',MAILTRAP_FROM_EMAIL:'sender@example.test']
        when:def config=new LoginNotificationConfiguration(env)
        then:config.enabled;config.email.enabled
        env.LOGIN_NOTIFICATIONS_ENABLED=='false'
    }
}
