package com.arman.bank.paymentservice.infrastructure

import com.arman.bank.paymentservice.application.EmailDelivery
import spock.lang.*
import javax.net.ssl.*
import java.nio.file.*
import java.security.KeyStore
import java.util.concurrent.*

/** Loopback-only SMTP transcripts with ephemeral, synthetic TLS credentials. */
class MailtrapSmtpSpec extends Specification {
    @Shared @TempDir Path tlsDirectory
    @Shared SSLContext serverTls
    @Shared SSLContext clientTls

    def setupSpec() {
        def keytool=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').startsWith('Windows') ? 'keytool.exe' : 'keytool').toString()
        def file=tlsDirectory.resolve('synthetic.p12')
        def process=new ProcessBuilder(keytool,'-genkeypair','-alias','fixture','-keyalg','RSA','-keysize','2048',
            '-storetype','PKCS12','-keystore',file.toString(),'-storepass','synthetic-password',
            '-keypass','synthetic-password','-dname','CN=localhost','-ext','SAN=dns:localhost','-validity','2','-noprompt')
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        assert process.waitFor(30,TimeUnit.SECONDS)
        assert process.exitValue()==0
        def keys=KeyStore.getInstance('PKCS12')
        Files.newInputStream(file).withCloseable { keys.load(it,'synthetic-password'.toCharArray()) }
        def km=KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm)
        km.init(keys,'synthetic-password'.toCharArray())
        def tm=TrustManagerFactory.getInstance(TrustManagerFactory.defaultAlgorithm)
        tm.init(keys)
        serverTls=SSLContext.getInstance('TLS'); serverTls.init(km.keyManagers,null,null)
        clientTls=SSLContext.getInstance('TLS'); clientTls.init(null,tm.trustManagers,null)
    }
    static Map env() {
        [EMAIL_DELIVERY_ENABLED:'true',MAILTRAP_TRANSPORT:'smtp',MAILTRAP_MODE:'sandbox',
         MAILTRAP_SMTP_USERNAME:'synthetic-user',MAILTRAP_SMTP_PASSWORD:'synthetic-password',
         MAILTRAP_FROM_EMAIL:'bank@example.test',AUTH_BASE_URL:'http://auth-service:8080',USER_BASE_URL:'http://user-service:8080',
         INTERNAL_AUTH_KEY:'synthetic-internal-key-at-least-32-characters']
    }
    def 'SMTP configuration uses no API token and rejects unsafe configuration without echoing secrets'() {
        expect:
        def config=new EmailConfiguration(env())
        config.smtpHost=='sandbox.smtp.mailtrap.io'
        config.smtpPort==2525
        config.token==null && config.endpoint==null
        !new EmailConfiguration([MAILTRAP_TRANSPORT:'smtp']).enabled
        when:
        new EmailConfiguration(env()+values)
        then:
        def error=thrown(IllegalArgumentException)
        !error.message.contains('synthetic-password')
        !error.message.contains('attacker')
        where:
        values << [[MAILTRAP_TRANSPORT:'other'],[MAILTRAP_MODE:'sending'],[MAILTRAP_SMTP_HOST:'attacker.test'],
            [MAILTRAP_SMTP_PORT:'465'],[MAILTRAP_SMTP_PORT:'0'],[MAILTRAP_SMTP_PORT:'65536'],
            [MAILTRAP_SMTP_USERNAME:''],[MAILTRAP_SMTP_PASSWORD:'synthetic-password\r\nAUTH'],
            [MAILTRAP_SMTP_PASSWORD:'']]
    }
    def 'TLS and timeout controls cannot be disabled by environment variables'() {
        expect:
        def properties=MailtrapSmtp.properties()
        properties.getProperty('mail.smtp.starttls.required')=='true'
        properties.getProperty('mail.smtp.ssl.checkserveridentity')=='true'
        properties.getProperty('mail.smtp.timeout')=='5000'
        properties.getProperty('mail.smtp.connectiontimeout')=='5000'
        properties.getProperty('mail.smtp.writetimeout')=='5000'
        !properties.containsKey('mail.smtp.ssl.trust')
        properties.getProperty('mail.debug')=='false'
    }
    def 'real SMTP STARTTLS sends one message and classifies #stage reply #code'() {
        given:
        def fixture=new SmtpFixture(serverTls,stage,code)
        def delivery=new MailtrapSmtp(new EmailConfiguration(env()),'localhost',fixture.port,clientTls.socketFactory,15000)
        when:
        def result=delivery.send('recipient@example.test','ArMoney: Payment completed','Payment completed. Amount: AED 1.25. Payment ID: synthetic. Event ID: synthetic.')
        fixture.done.get(5,TimeUnit.SECONDS)
        then:
        result==expected
        fixture.commands.indexOf('STARTTLS')<fixture.commands.findIndexOf { it.startsWith('AUTH ') }
        fixture.authenticatedWithSyntheticCredentials
        stage!='data' || fixture.message.contains('Amount: AED 1.25')
        stage!='data' || fixture.message.contains('To: recipient@example.test')
        fixture.failure==null
        cleanup:
        fixture?.close()
        where:
        stage | code | expected
        'data' | 250 | EmailDelivery.Result.ACCEPTED
        'data' | 451 | EmailDelivery.Result.TRANSIENT_FAILURE
        'data' | 550 | EmailDelivery.Result.PERMANENT_FAILURE
        'rcpt' | 450 | EmailDelivery.Result.TRANSIENT_FAILURE
        'rcpt' | 550 | EmailDelivery.Result.PERMANENT_FAILURE
        'auth' | 454 | EmailDelivery.Result.TRANSIENT_FAILURE
        'auth' | 535 | EmailDelivery.Result.PERMANENT_FAILURE

    }
    def 'TLS omission or refusal never sends credentials or content'() {
        given:
        def fixture=new SmtpFixture(serverTls,stage,454)
        def delivery=new MailtrapSmtp(new EmailConfiguration(env()),'localhost',fixture.port,clientTls.socketFactory,15000)
        expect:
        delivery.send('recipient@example.test','subject','body')!=EmailDelivery.Result.ACCEPTED
        fixture.done.get(5,TimeUnit.SECONDS)==null
        !fixture.commands.any { it.startsWith('AUTH ') || it.startsWith('MAIL ') }
        fixture.message.empty
        cleanup:
        fixture?.close()
        where:
        stage << ['no-tls','tls-refused']

    }
    def 'untrusted certificate or hostname mismatch fails before credentials'() {
        given:
        def fixture=new SmtpFixture(serverTls,'data',250)
        def delivery=new MailtrapSmtp(new EmailConfiguration(env()),host,fixture.port,trusted ? clientTls.socketFactory : null,15000)
        expect:
        delivery.send('recipient@example.test','subject','body')==EmailDelivery.Result.TRANSIENT_FAILURE
        fixture.done.get(5,TimeUnit.SECONDS)==null
        !fixture.commands.any { it.startsWith('AUTH ') || it.startsWith('MAIL ') }
        cleanup:
        fixture?.close()
        where:
        host | trusted
        'localhost' | false
        '127.0.0.1' | true

    }
    def 'connection failure is retryable and deadline bounds a stalled peer'() {
        given:
        def unused=new ServerSocket(0,1,InetAddress.getLoopbackAddress())
        int port=unused.localPort
        unused.close()
        def disconnected=new MailtrapSmtp(new EmailConfiguration(env()),'localhost',port,clientTls.socketFactory,15000)
        def fixture=new SmtpFixture(serverTls,'stall',250)
        def stalled=new MailtrapSmtp(new EmailConfiguration(env()),'localhost',fixture.port,clientTls.socketFactory,200)
        expect:
        disconnected.send('recipient@example.test','subject','body')==EmailDelivery.Result.TRANSIENT_FAILURE
        when:
        long started=System.nanoTime()
        def result=stalled.send('recipient@example.test','subject','body')
        then:
        result==EmailDelivery.Result.TRANSIENT_FAILURE
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2000
        cleanup:
        fixture?.close()
    }
    def 'styled SMTP email preserves equivalent UTF-8 plain and HTML alternatives for #recipientName'() {
        given:
        def fixture=new SmtpFixture(serverTls,'data',250)
        def delivery=new MailtrapSmtp(new EmailConfiguration(env()),'localhost',fixture.port,clientTls.socketFactory,15000)
        def claim=new com.arman.bank.paymentservice.application.EmailOutbox.Claim(
            UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),'PAYMENT_RECEIVED',125L,UUID.randomUUID(),1,null,null,recipientName)
        def content=PaymentEmail.render(claim)
        when:
        def result=delivery.send('recipient@example.test',content.subject(),content.text(),content.html())
        fixture.done.get(5,TimeUnit.SECONDS)
        def parsed=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()),
            new ByteArrayInputStream(fixture.message.getBytes('UTF-8')))
        def alternatives=(jakarta.mail.Multipart)parsed.content
        then:
        result==EmailDelivery.Result.ACCEPTED
        parsed.isMimeType('multipart/alternative')
        alternatives.count==2
        alternatives.getBodyPart(0).isMimeType('text/plain')
        alternatives.getBodyPart(1).isMimeType('text/html')
        new jakarta.mail.internet.ContentType(alternatives.getBodyPart(0).contentType).getParameter('charset').equalsIgnoreCase('UTF-8')
        new jakarta.mail.internet.ContentType(alternatives.getBodyPart(1).contentType).getParameter('charset').equalsIgnoreCase('UTF-8')
        alternatives.getBodyPart(0).content.toString().replace('\r\n','\n').strip()==content.text().strip()
        alternatives.getBodyPart(1).content.toString().replace('\r\n','\n').strip()==content.html().strip()
        alternatives.getBodyPart(0).content.toString().contains(plainGreeting)
        alternatives.getBodyPart(1).content.toString().contains(htmlGreeting)
        parsed.subject==content.subject()
        fixture.failure==null
        cleanup:
        fixture?.close()
        where:
        recipientName             | plainGreeting                     | htmlGreeting
        null                      | 'Hello,'                          | 'Hello,'
        'ليلى Zoë <&> "\''       | 'Hello ليلى Zoë <&> "\','        | 'Hello ليلى Zoë &lt;&amp;&gt; &quot;&#39;,'
    }

    def 'lost DATA acknowledgement remains retryable'() {
        given:
        def fixture=new SmtpFixture(serverTls,'lost-ack',250)
        def delivery=new MailtrapSmtp(new EmailConfiguration(env()),'localhost',fixture.port,clientTls.socketFactory,15000)
        expect:
        delivery.send('recipient@example.test','subject','body')==EmailDelivery.Result.TRANSIENT_FAILURE
        fixture.done.get(5,TimeUnit.SECONDS)==null
        fixture.message.contains('body')
        cleanup:
        fixture?.close()
    }
    def 'recipient lists groups and header injection are rejected before connecting'() {
        given:
        def delivery=new MailtrapSmtp(new EmailConfiguration(env()),'localhost',1,clientTls.socketFactory,15000)
        expect:
        delivery.send(address,'subject','body')==EmailDelivery.Result.PERMANENT_FAILURE
        where:
        address << ['one@example.test,two@example.test','group:one@example.test;',
            'one@example.test\r\nBcc: two@example.test']
    }
    def 'repeated SMTP attempts release write timeout scheduler threads'() {
        given:
        // Initialize the JDK's shared virtual-thread timer before taking the baseline.
        Thread.startVirtualThread { Thread.sleep(1) }.join()
        def baseline=Thread.getAllStackTraces().keySet().collect { it.threadId() }.toSet()
        when:
        ['data','lost-ack','tls-refused','stall'].each { stage ->
            3.times {
                def fixture=new SmtpFixture(serverTls,stage,250)
                try {
                    def delivery=new MailtrapSmtp(new EmailConfiguration(env()),'localhost',fixture.port,clientTls.socketFactory,stage=='stall' ? 200 : 15000)
                    assert delivery.send('recipient@example.test','subject','body')==
                        (stage=='data' ? EmailDelivery.Result.ACCEPTED : EmailDelivery.Result.TRANSIENT_FAILURE)
                } finally { fixture.close() }
            }
        }
        then:
        new spock.util.concurrent.PollingConditions(timeout:2).eventually {
            def remaining=Thread.getAllStackTraces().findAll { thread, stack ->
                !baseline.contains(thread.threadId()) && stack.any {
                    it.className.startsWith('java.util.concurrent.ScheduledThreadPoolExecutor')
                }
            }.keySet()*.name
            assert remaining.empty
        }
    }
    def 'a standalone SMTP JVM exits naturally after provider acceptance'() {
        given:
        def fixture=new SmtpFixture(serverTls,'data',250)
        def locations=[]
        for (def loader=getClass().classLoader; loader!=null; loader=loader.parent) {
            if (loader instanceof URLClassLoader) locations.addAll(loader.URLs.collect { new File(it.toURI()).path })
        }
        locations.addAll(System.getProperty('java.class.path').split(File.pathSeparator))
        def java=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').startsWith('Windows') ? 'java.exe' : 'java').toString()
        def process=new ProcessBuilder(java,'-cp',locations.unique().join(File.pathSeparator),
            NaturalExitProbe.name,fixture.port.toString(),tlsDirectory.resolve('synthetic.p12').toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        when:
        boolean exited=process.waitFor(15,TimeUnit.SECONDS)
        then:
        exited
        process.exitValue()==0
        fixture.done.get(5,TimeUnit.SECONDS)==null
        fixture.message.contains('synthetic natural exit probe')
        cleanup:
        if (process?.isAlive()) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS) }
        fixture?.close()
    }
    static class NaturalExitProbe {
        static void main(String[] args) {
            def keys=KeyStore.getInstance('PKCS12')
            Files.newInputStream(Path.of(args[1])).withCloseable { keys.load(it,'synthetic-password'.toCharArray()) }
            def tm=TrustManagerFactory.getInstance(TrustManagerFactory.defaultAlgorithm)
            tm.init(keys)
            def tls=SSLContext.getInstance('TLS'); tls.init(null,tm.trustManagers,null)
            def delivery=new MailtrapSmtp(new EmailConfiguration(MailtrapSmtpSpec.env()),'localhost',Integer.parseInt(args[0]),tls.socketFactory,10000)
            assert delivery.send('recipient@example.test','subject','synthetic natural exit probe')==EmailDelivery.Result.ACCEPTED
            // Deliberately no System.exit: remaining non-daemon threads fail the parent test.
        }
    }
    static class SmtpFixture implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName('127.0.0.1'))
        final List<String> commands=Collections.synchronizedList([])
        final CompletableFuture<Void> done
        volatile Socket socket
        volatile String message=''
        volatile boolean authenticatedWithSyntheticCredentials=false
        volatile Throwable failure
        int getPort() { listener.localPort }
        SmtpFixture(SSLContext tls,String stage,int code) {
            listener.soTimeout=5000
            done=CompletableFuture.runAsync {
                try {
                    socket=listener.accept(); socket.soTimeout=3000
                    if (stage=='stall') { socket.inputStream.read(); return }
                    def reader=socket.inputStream.newReader('UTF-8')
                    def writer=socket.outputStream.newWriter('UTF-8')
                    def reply={ String value -> writer.write(value+'\r\n'); writer.flush() }
                    reply('220 synthetic SMTP')
                    while (true) {
                        String line=reader.readLine()
                        if (line==null) break
                        // Never retain AUTH bytes in diagnostic transcripts.
                        commands.add(line.startsWith('AUTH ') ? 'AUTH [redacted]' : line)
                        if (line.startsWith('EHLO ')) {
                            reply('250-localhost')
                            if (stage!='no-tls' && !(socket instanceof SSLSocket)) reply('250-STARTTLS')
                            reply('250 AUTH PLAIN LOGIN')
                        } else if (line=='STARTTLS') {
                            if (stage=='tls-refused') { reply('454 TLS unavailable'); continue }
                            reply('220 Begin TLS')
                            socket=tls.socketFactory.createSocket(socket,'localhost',port,true)
                            ((SSLSocket)socket).useClientMode=false
                            ((SSLSocket)socket).startHandshake()
                            reader=socket.inputStream.newReader('UTF-8'); writer=socket.outputStream.newWriter('UTF-8')
                        } else if (line.startsWith('AUTH PLAIN ')) {
                            def fields=new String(Base64.decoder.decode(line.substring(11)),'UTF-8').split('\u0000',-1).toList()
                            authenticatedWithSyntheticCredentials=fields in [
                                ['', 'synthetic-user', 'synthetic-password'],
                                ['synthetic-user', 'synthetic-user', 'synthetic-password']]
                            reply(stage=='auth' ? "${code} synthetic auth rejection" : '235 Authenticated')
                        } else if (line.startsWith('MAIL FROM:')) reply('250 Sender accepted')
                        else if (line.startsWith('RCPT TO:')) reply(stage=='rcpt' ? "${code} recipient@example.test synthetic rejection" : '250 Recipient accepted')
                        else if (line=='DATA') {
                            reply('354 Send content')
                            def lines=[]
                            while ((line=reader.readLine())!=null && line!='.') lines.add(line)
                            message=lines.join('\n')
                            if (stage=='lost-ack') break
                            reply("${code} recipient@example.test synthetic result")
                        } else if (line=='RSET') reply('250 Reset')
                        else if (line=='QUIT') break // Client must not depend on QUIT acknowledgement.
                        else throw new AssertionError('Unexpected synthetic SMTP command')
                    }
                } catch (SSLException ignored) { /* Expected for negative TLS cases. */ }
                  catch (SocketException ignored) { /* Client aborts on deadline or TLS failure. */ }
                  catch (Throwable e) { failure=e }
                finally { socket?.close() }
            }
        }
        void close() { socket?.close(); listener.close(); done.get(5,TimeUnit.SECONDS) }
    }
}
