package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.Verification.Purpose
import com.arman.bank.authservice.infrastructure.PostgresVerificationStore.DeliveryLease
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import spock.lang.*
import javax.net.ssl.*
import java.nio.file.*
import java.security.KeyStore
import java.time.*
import java.util.concurrent.TimeUnit

/** Real SMTP/TLS through the actual verification sender. Only socket/trust seams
 * target loopback; no environment bypass, real Mailtrap, database or ACTIVE claim.
 */
class VerificationDeliverySmtpTlsAcceptanceSpec extends Specification {
    @Shared @TempDir Path certificates
    @Shared SSLContext serverTls
    @Shared SSLContext clientTls
    static final String ADDRESS = 'synthetic-otp@example.test'
    static final String CODE = '123456'
    static final String TEXT = 'Your ArMoney registration verification code is 123456. It expires in 5 minutes. Do not share this code.'
    List<VerificationDeliveryTransport> transports = []

    def setupSpec() {
        // Same pinned-JDK certificate tool and fixture as the existing SMTP suite.
        String keytool = Path.of(System.getProperty('java.home'), 'bin',
            System.getProperty('os.name').startsWith('Windows') ? 'keytool.exe' : 'keytool').toString()
        Path file = certificates.resolve('ephemeral.p12')
        def process = new ProcessBuilder(keytool, '-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048',
            '-storetype', 'PKCS12', '-keystore', file.toString(), '-storepass', 'synthetic-password',
            '-keypass', 'synthetic-password', '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost',
            '-validity', '2', '-noprompt').redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assert process.waitFor(30, TimeUnit.SECONDS)
        assert process.exitValue() == 0
        def keys = KeyStore.getInstance('PKCS12')
        Files.newInputStream(file).withCloseable { keys.load(it, 'synthetic-password'.toCharArray()) }
        def keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm)
        keyManager.init(keys, 'synthetic-password'.toCharArray())
        def trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.defaultAlgorithm)
        trustManager.init(keys)
        serverTls = SSLContext.getInstance('TLS'); serverTls.init(keyManager.keyManagers, null, null)
        clientTls = SSLContext.getInstance('TLS'); clientTls.init(null, trustManager.trustManagers, null)
    }
    def cleanup() { transports.each { it.close() }; Thread.interrupted() }

    VerificationDeliveryTransport transport(def fixture, String host = 'localhost', boolean trust = true, long deadline = 15000) {
        def config = new EmailConfiguration(MailtrapSmtpSpec.env())
        def smtp = new MailtrapSmtp(config, host, fixture.port, trust ? clientTls.socketFactory : null, deadline)
        // This is the real SMTP adapter, not a mock of successful delivery.
        def result = new VerificationDeliveryTransport(smtp.&send as VerificationDeliveryTransport.MailSender,
            URI.create('http://sms-sandbox:8080'), 'synthetic_sms_sender_token_32_characters', false)
        transports.add(result)
        result
    }
    static DeliveryLease lease() {
        new DeliveryLease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Purpose.REGISTRATION_EMAIL, 1, Instant.now().plusSeconds(60),
            new VerificationCrypto.Encrypted('test', new byte[12], new byte[20]))
    }
    static Map capture(Closure operation) {
        def bytes = new ByteArrayOutputStream()
        def originalOut = System.out, originalErr = System.err
        def sink = new PrintStream(bytes, true, 'UTF-8')
        try {
            System.setOut(sink); System.setErr(sink)
            [accepted: operation.call(), diagnostics: bytes.toString('UTF-8')]
        } finally { System.setOut(originalOut); System.setErr(originalErr); sink.close() }
    }
    static boolean redacted(Map observed) {
        [CODE, ADDRESS, 'synthetic-password', 'synthetic-user', 'SSLHandshakeException'].every {
            !observed.diagnostics.contains(it)
        }
    }
    static MimeMessage message(def fixture) {
        new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(fixture.message.getBytes('UTF-8')))
    }

    static boolean exactAlternatives(MimeMessage parsed) {
        assert parsed.isMimeType('multipart/alternative')
        def parts = (jakarta.mail.Multipart) parsed.content
        assert parts.count == 2
        assert parts.getBodyPart(0).isMimeType('text/plain')
        assert parts.getBodyPart(1).isMimeType('text/html')
        assert parts.getBodyPart(0).content.toString().replace('\r\n', '\n').strip() == TEXT
        String html = parts.getBodyPart(1).content.toString().replace('\r\n', '\n').strip()
        assert html == VerificationEmail.render(CODE).html().strip()
        assert html.contains(CODE)
        assert html.contains('It expires in 5 minutes. Do not share this code.')
        return true
    }

    def 'verification email uses real authenticated STARTTLS and exact plain and HTML OTP alternatives'() {
        given:
        def fixture = new MailtrapSmtpSpec.SmtpFixture(serverTls, 'data', 250)
        def sender = transport(fixture)
        when:
        def observed = capture { sender.send(lease(), new VerificationCrypto.DeliveryPayload(ADDRESS, CODE)) }
        fixture.done.get(5, TimeUnit.SECONDS)
        def parsed = message(fixture)
        then:
        observed.accepted
        redacted(observed)
        fixture.failure == null
        fixture.commands.indexOf('STARTTLS') >= 0
        fixture.commands.indexOf('STARTTLS') < fixture.commands.findIndexOf { it.startsWith('AUTH ') }
        fixture.authenticatedWithSyntheticCredentials
        parsed.subject == 'ArMoney registration verification'
        exactAlternatives(parsed)
        parsed.allRecipients*.toString() == [ADDRESS]
        !fixture.commands.any { it.contains(CODE) || it.contains('synthetic-password') }
        cleanup:
        fixture?.close()
    }

    def 'verification transport refuses missing or refused STARTTLS before credentials or OTP content: #stage'() {
        given:
        def fixture = new MailtrapSmtpSpec.SmtpFixture(serverTls, stage, 454)
        def sender = transport(fixture)
        when:
        def observed = capture { sender.send(lease(), new VerificationCrypto.DeliveryPayload(ADDRESS, CODE)) }
        fixture.done.get(5, TimeUnit.SECONDS)
        then:
        !observed.accepted
        redacted(observed)
        !fixture.commands.any { it.startsWith('AUTH ') || it.startsWith('MAIL ') || it == 'DATA' }
        fixture.message.empty
        cleanup:
        fixture?.close()
        where:
        stage << ['no-tls', 'tls-refused']
    }

    def 'verification transport rejects untrusted certificate or mismatched hostname before auth: #host trusted=#trust'() {
        given:
        def fixture = new MailtrapSmtpSpec.SmtpFixture(serverTls, 'data', 250)
        def sender = transport(fixture, host, trust)
        when:
        def observed = capture { sender.send(lease(), new VerificationCrypto.DeliveryPayload(ADDRESS, CODE)) }
        fixture.done.get(5, TimeUnit.SECONDS)
        then:
        !observed.accepted
        redacted(observed)
        !fixture.commands.any { it.startsWith('AUTH ') || it.startsWith('MAIL ') || it == 'DATA' }
        fixture.message.empty
        cleanup:
        fixture?.close()
        where:
        host        | trust
        'localhost' | false
        '127.0.0.1' | true
    }

    def 'verification SMTP total deadline cancels stalled peer without exposing payload or credentials'() {
        given:
        def fixture = new MailtrapSmtpSpec.SmtpFixture(serverTls, 'stall', 250)
        def sender = transport(fixture, 'localhost', true, 200)
        when:
        long started = System.nanoTime()
        def observed = capture { sender.send(lease(), new VerificationCrypto.DeliveryPayload(ADDRESS, CODE)) }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        then:
        !observed.accepted
        elapsed < 2000
        redacted(observed)
        fixture.message.empty
        cleanup:
        fixture?.close()
    }

    def 'lost DATA acknowledgement is uncertain despite captured OTP message and never acknowledged by worker'() {
        given:
        def fixture = new MailtrapSmtpSpec.SmtpFixture(serverTls, 'lost-ack', 250)
        def sender = transport(fixture)
        def crypto = new VerificationCrypto('test', ['test': ([11] * 32) as byte[]], ['test': ([22] * 32) as byte[]])
        UUID enrollment = UUID.randomUUID(), challenge = UUID.randomUUID()
        def encrypted = crypto.encrypt(enrollment, challenge, Purpose.REGISTRATION_EMAIL, 1, ADDRESS, CODE)
        def intent = new DeliveryLease(UUID.randomUUID(), UUID.randomUUID(), enrollment, challenge,
            Purpose.REGISTRATION_EMAIL, 1, Instant.now().plusSeconds(60), encrypted)
        // Only the worker persistence boundary is synthetic; SMTP runs for real.
        def outbox = Mock(VerificationDeliveryWorker.Outbox)
        def worker = new VerificationDeliveryWorker(outbox, crypto, sender, Clock.systemUTC())
        when:
        def observed = capture { worker.tick(); false }
        fixture.done.get(5, TimeUnit.SECONDS)
        then:
        1 * outbox.claim() >> Optional.of(intent)
        1 * outbox.finish(intent.deliveryId(), intent.fence(), false) >> true
        redacted(observed)
        fixture.authenticatedWithSyntheticCredentials
        exactAlternatives(message(fixture))
        fixture.failure == null
        cleanup:
        worker?.close(); fixture?.close()
    }
}
