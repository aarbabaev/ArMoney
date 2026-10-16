package com.arman.bank.authservice.infrastructure

import spock.lang.Specification

class VerificationEmailSpec extends Specification {
    def 'registration has consistent branding and complete plain text without a preidentity profile'() {
        when:
        def email = VerificationEmail.render('012345')
        then:
        email.subject() == 'ArMoney registration verification'
        email.text() == 'Your ArMoney registration verification code is 012345. It expires in 5 minutes. Do not share this code.'
        email.html().contains('max-width:600px;background:#ffffff')
        email.html().contains('background:#14283f;color:#ffffff')
        email.html().contains('Hello,')
        email.html().contains('012345')
        email.html().contains('It expires in 5 minutes. Do not share this code.')
        !email.html().contains('http:')
        !email.html().contains('https:')
        !email.html().contains('<img')
        !email.html().contains('<script')
    }
    def 'rejects malformed or injectable codes without returning their contents'() {
        when:
        VerificationEmail.render(code)
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'Invalid verification code'
        where:
        code << [null, '', '12345', '1234567', '１２３４５６', '12345a', '<b>123456</b>', '123456\r\nBcc: other@example.test']
    }
}
