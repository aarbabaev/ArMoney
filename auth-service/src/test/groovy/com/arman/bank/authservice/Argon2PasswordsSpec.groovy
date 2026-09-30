package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.Argon2Passwords
import spock.lang.Specification

class Argon2PasswordsSpec extends Specification {
    def "salted Argon2id hashes verify without storing the original password"() {
        given:
        def passwords = new Argon2Passwords()
        def password = 'a long пароль with spaces'
        def first = passwords.hash(password)
        def second = passwords.hash(password)

        expect:
        first != second
        !first.contains(password)
        first.startsWith('$argon2id$v=19$m=19456,t=2,p=1$')
        passwords.verify(password, first)
        passwords.verify(password, second)
        !passwords.verify('a different long password', first)
        !passwords.verify(password, 'not-a-hash')
        !passwords.verify(password, '$argon2id$v=19$m=19456,t=2,p=1$bad$bad')
    }
}
