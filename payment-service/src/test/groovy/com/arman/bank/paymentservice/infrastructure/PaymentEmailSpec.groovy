package com.arman.bank.paymentservice.infrastructure

import com.arman.bank.paymentservice.application.EmailOutbox
import spock.lang.Specification

class PaymentEmailSpec extends Specification {
    def claim(String type='PAYMENT_COMPLETED', long amount=125L) {
        new EmailOutbox.Claim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),type,amount,
            UUID.randomUUID(),1,'do-not-infer-name@example.test',false)
    }
    def 'HTML and plain text describe the same authoritative #event outcome'() {
        given:
        def c=claim(event)
        def email=PaymentEmail.render(c)
        expect:
        email.subject()=='ArMoney: '+title
        [email.text(),email.html()].every {
            it.contains('Hello,') && it.contains('AED 1.25') && it.contains(status) &&
            it.contains(direction) && it.contains(c.paymentId().toString()) && it.contains(c.id().toString())
        }
        !email.html().contains('do-not-infer-name')
        !email.text().contains('do-not-infer-name')
        !email.html().contains('<script')
        !email.html().contains('<img')
        !email.html().contains('href=')
        !email.text().contains('balance')
        event!='PAYMENT_REJECTED' || !email.text().contains('has been completed')
        where:
        event | title | status | direction
        'PAYMENT_COMPLETED' | 'Payment completed' | 'Completed' | 'Outgoing transfer'
        'PAYMENT_RECEIVED' | 'Payment received' | 'Completed' | 'Incoming transfer'
        'PAYMENT_REJECTED' | 'Payment rejected' | 'Rejected' | 'Outgoing transfer'
    }
    def 'money remains exact at all integer fils bounds'() {
        expect:
        PaymentEmail.render(claim('PAYMENT_COMPLETED',amount)).text().contains(expected)
        PaymentEmail.render(claim('PAYMENT_COMPLETED',amount)).html().contains(expected)
        where:
        amount | expected
        1L | 'AED 0.01'
        100L | 'AED 1.00'
        109L | 'AED 1.09'
        Long.MAX_VALUE | 'AED 92233720368547758.07'
    }
    def 'dynamic text is escaped as text instead of active markup'() {
        expect:
        PaymentEmail.escape('<script x="1">A&B</script>\'')=='&lt;script x=&quot;1&quot;&gt;A&amp;B&lt;/script&gt;&#39;'
    }
    def 'profile display name is plain text and HTML escaped including non ASCII characters'() {
        given:
        def name='Amina <Admin> & "Team" – أمينة'
        def email=PaymentEmail.render(claim().withRecipient(new com.arman.bank.paymentservice.application.EmailDelivery.Recipient(
            'owner@example.test',true,name)))
        expect:
        email.text().contains('Hello '+name+',')
        email.html().contains('Hello Amina &lt;Admin&gt; &amp; &quot;Team&quot; – أمينة,')
        !email.html().contains('<Admin>')
        !email.subject().contains(name)
    }

    def 'unknown events and invalid amounts fail closed'() {
        when:
        PaymentEmail.render(claim(event,amount))
        then:
        thrown(IllegalArgumentException)
        where:
        event | amount
        'LOGIN' | 125L
        'PAYMENT_COMPLETED' | 0L
        'PAYMENT_COMPLETED' | -1L
    }
}
