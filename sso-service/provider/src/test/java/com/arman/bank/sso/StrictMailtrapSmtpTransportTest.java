package com.arman.bank.sso;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback SMTP/TLS, ephemeral certificate and synthetic credentials only. */
@org.junit.jupiter.api.parallel.ResourceLock("strict-smtp")
final class StrictMailtrapSmtpTransportTest {
    @TempDir static Path directory;private static SSLContext serverTls,clientTls;
    @BeforeAll static void certificates()throws Exception{
        String exe=System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool";
        Path file=directory.resolve("smtp-fixture.p12");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",exe).toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048",
                "-storetype","PKCS12","-keystore",file.toString(),"-storepass","synthetic-password","-keypass","synthetic-password","-dname","CN=localhost",
                "-ext","SAN=dns:localhost","-validity","2","-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}
        finally{if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}}
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(file)){keys.load(input,"synthetic-password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"synthetic-password".toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        serverTls=SSLContext.getInstance("TLS");serverTls.init(km.getKeyManagers(),null,null);
        clientTls=SSLContext.getInstance("TLS");clientTls.init(null,tm.getTrustManagers(),null);
    }
    @Test void immutableTransportPolicyRequiresTrustedTlsBeforeAuthentication(){
        var p=StrictMailtrapSmtpTransport.properties();
        for(String key:List.of("mail.smtp.starttls.enable","mail.smtp.starttls.required","mail.smtp.ssl.checkserveridentity","mail.smtp.auth"))assertEquals("true",p.getProperty(key));
        for(String key:List.of("mail.smtp.connectiontimeout","mail.smtp.timeout","mail.smtp.writetimeout"))assertEquals("5000",p.getProperty(key));
        assertEquals("false",p.getProperty("mail.smtp.socketFactory.fallback"));assertEquals("false",p.getProperty("mail.smtp.quitwait"));
        assertEquals("false",p.getProperty("mail.debug"));assertEquals("TLSv1.3 TLSv1.2",p.getProperty("mail.smtp.ssl.protocols"));
        assertFalse(p.containsKey("mail.smtp.ssl.trust"));assertFalse(p.containsKey("mail.smtp.ssl.socketFactory"));
    }
    @Test void genuineTlsSubmissionPreservesStockTokenBodyAndHasNoProtocolOrPiiLogs()throws Exception{
        try(var logs=new LogCapture();var fixture=new SmtpFixture("positive");var sender=new StrictMailtrapSmtpTransport("localhost",fixture.port(),clientTls.getSocketFactory(),5000,1000)){
            assertTrue(sender.send(StrictMailtrapEmailSenderTest.settings(),"recipient@example.test","Verify current contact","token-bearing-body","<p>token-bearing-body</p>"));fixture.await();
            assertTrue(fixture.authenticated);assertTrue(fixture.tlsActive);assertEquals(1,fixture.accepts.get());
            assertTrue(fixture.commands.indexOf("STARTTLS")<fixture.commands.indexOf("AUTH [redacted]"));assertTrue(fixture.message.contains("token-bearing-body"));
            assertTrue(fixture.message.contains("multipart/alternative"));assertNull(fixture.failure);
            Logger.getLogger("com.arman.bank.sso").warning("SMTP_LOG_ISOLATION_SENTINEL");
            assertTrue(logs.records.stream().anyMatch(record->"SMTP_LOG_ISOLATION_SENTINEL".equals(record.getMessage())),"Unrelated SSO diagnostics must remain enabled");
            logs.assertPrivate();
        }
    }
    @Test void missingTlsRefusalUntrustedAndWrongHostnameNeverSendAuthOrData()throws Exception{
        try(var logs=new LogCapture()){for(String fault:List.of("missing","refused","untrusted","wronghost","handshake-stall")){
            try(var fixture=new SmtpFixture(fault);var sender=new StrictMailtrapSmtpTransport(fault.equals("wronghost")?"127.0.0.1":"localhost",fixture.port(),fault.equals("untrusted")?null:clientTls.getSocketFactory(),1500,1000)){
                long started=System.nanoTime();assertFalse(sender.send(StrictMailtrapEmailSenderTest.settings(),"recipient@example.test","subject","token-bearing-body",null),fault);
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<3000,fault);fixture.await();
                assertFalse(fixture.commands.stream().anyMatch(line->line.startsWith("AUTH")||line.startsWith("MAIL")||line.equals("DATA")),fault);
                assertTrue(fixture.message.isEmpty());assertEquals(1,fixture.accepts.get());
            }
        }logs.assertPrivate();}
    }
    @Test void stalledAndDrippingPeersAreCutOffByTotalDeadlineAndDoNotRetry()throws Exception{
        try(var logs=new LogCapture()){for(String fault:List.of("greeting-stall","greeting-drip","data-ack-stall")){
            try(var fixture=new SmtpFixture(fault);var sender=new StrictMailtrapSmtpTransport("localhost",fixture.port(),clientTls.getSocketFactory(),750,1000)){
                long started=System.nanoTime();assertFalse(sender.send(StrictMailtrapEmailSenderTest.settings(),"recipient@example.test","subject","token-bearing-body",null));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2250);fixture.await();assertEquals(1,fixture.accepts.get());
                if(!fault.equals("data-ack-stall"))assertFalse(fixture.commands.stream().anyMatch(line->line.startsWith("AUTH")||line.equals("DATA")));
            }
        }logs.assertPrivate();}
    }
    @Test void onlyOneProcessAttemptCanRunAndCloseAbortsOwnedSocket()throws Exception{
        try(var fixture=new SmtpFixture("greeting-stall");var first=new StrictMailtrapSmtpTransport("localhost",fixture.port(),clientTls.getSocketFactory(),5000,1000);
            var second=new StrictMailtrapSmtpTransport("localhost",fixture.port(),clientTls.getSocketFactory(),1000,1000)){
            var result=CompletableFuture.supplyAsync(()->{try{return first.send(StrictMailtrapEmailSenderTest.settings(),"recipient@example.test","subject","body",null);}catch(InterruptedException e){throw new CompletionException(e);}});
            assertTrue(fixture.accepted.await(2,TimeUnit.SECONDS));long started=System.nanoTime();
            assertFalse(second.send(StrictMailtrapEmailSenderTest.settings(),"recipient@example.test","subject","body",null));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<250);
            first.close();assertFalse(result.get(2,TimeUnit.SECONDS));fixture.await();assertEquals(1,fixture.accepts.get());
        }
    }
    @Test void failuresAreSanitizedThroughSenderAndWorkerSchedulersExit()throws Exception{
        try(var fixture=new SmtpFixture("refused");var transport=new StrictMailtrapSmtpTransport("localhost",fixture.port(),clientTls.getSocketFactory(),2000,1000)){
            var sender=new StrictMailtrapEmailSender(StrictMailtrapEmailSenderFactoryTest.session("armoney",null),transport,value->value);try{
            StrictMailtrapEmailSenderTest.sanitized(assertThrows(org.keycloak.email.EmailException.class,()->sender.send(StrictMailtrapEmailSenderTest.config(),"recipient@example.test","subject","token-body",null)));fixture.await();
            }finally{sender.close();}
        }
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<until&&smtpThreads())Thread.sleep(20);
        assertFalse(smtpThreads(),"Attempt scheduler and idle bounded worker must exit");
    }
    @Test void actualBlockedDataWriteIsCutOffAndReleasesWorkerPermitWithinLegalBodyLimit()throws Exception{
        var probe=new WriteProbe();
        // ASCII, bounded <=65536 UTF-8 bytes. No oversized fixture payload bypasses sender policy.
        String body="0123456789abcdef".repeat(4096);assertEquals(65536,body.getBytes(StandardCharsets.UTF_8).length);
        try(var logs=new LogCapture();var fixture=new SmtpFixture("blocked-write");
            var transport=new StrictMailtrapSmtpTransport("localhost",fixture.port(),clientTls.getSocketFactory(),3000,1000,1024,probe)){
            var sender=new StrictMailtrapEmailSender(StrictMailtrapEmailSenderFactoryTest.session("armoney",null),transport,value->value);
            long started=System.nanoTime();
            var result=CompletableFuture.supplyAsync(()->{
                try{sender.send(StrictMailtrapEmailSenderTest.config(),"recipient@example.test","subject",body,null);return true;}
                catch(org.keycloak.email.EmailException failure){StrictMailtrapEmailSenderTest.sanitized(failure);return false;}
            });
            try{
                assertTrue(fixture.dataNotReading.await(2,TimeUnit.SECONDS),"Server must reach354 and stop reading DATA");
                long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1800);long active=-1,since=0;boolean witnessed=false;
                while(System.nanoTime()<until&&!result.isDone()){
                    long entered=probe.entered.get();Thread worker=probe.worker;
                    if(entered>probe.returned.get()&&worker!=null&&socketWrite(worker)){
                        if(active!=entered){active=entered;since=System.nanoTime();}
                        else if(System.nanoTime()-since>=TimeUnit.MILLISECONDS.toNanos(150)){witnessed=true;break;}
                    }else{active=-1;since=0;}
                    Thread.sleep(10);
                }
                assertTrue(witnessed,"No sustained actual socket write observed; OS may have buffered legal DATA. An ACK read is not write evidence.");
                assertFalse(probe.socketClosed.get());assertEquals(1L,probe.exited.getCount());assertFalse(result.isDone());
                assertFalse(result.get(5,TimeUnit.SECONDS));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<4500);
                assertTrue(probe.socketClosed.get(),"Deadline must close the actual tracked plain socket");assertTrue(probe.exited.await(1,TimeUnit.SECONDS));
                assertEquals(probe.entered.get(),probe.returned.get(),"Outstanding delegated write must unwind");
                assertEquals(1,fixture.accepts.get());assertTrue(fixture.message.isEmpty());logs.assertPrivate();
            }finally{sender.close();result.get(5,TimeUnit.SECONDS);}
        }
        // Actual next TLS submission, rather than an internal semaphore assertion.
        try(var fixture=new SmtpFixture("positive");var next=new StrictMailtrapSmtpTransport("localhost",fixture.port(),clientTls.getSocketFactory(),5000,1000)){
            assertTrue(next.send(StrictMailtrapEmailSenderTest.settings(),"recipient@example.test","subject","after blocked write",null));fixture.await();assertTrue(fixture.authenticated);
        }
    }
    private static boolean socketWrite(Thread worker){
        return Arrays.stream(worker.getStackTrace()).anyMatch(frame->{String type=frame.getClassName(),method=frame.getMethodName();
            return type.startsWith("sun.nio.ch.")&&(method.equals("write0")||method.equals("implWrite")||method.equals("write"))
                    ||type.startsWith("java.net.Socket$")&&method.equals("write");
        });
    }
    private static final class WriteProbe implements StrictMailtrapSmtpTransport.DataWriteObserver {
        final java.util.concurrent.atomic.AtomicLong entered=new java.util.concurrent.atomic.AtomicLong(),returned=new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean socketClosed=new java.util.concurrent.atomic.AtomicBoolean();final CountDownLatch exited=new CountDownLatch(1);volatile Thread worker;
        @Override public void entered(Thread worker){this.worker=worker;entered.incrementAndGet();}
        @Override public void returned(){returned.incrementAndGet();}
        @Override public void socketClosed(boolean closed){if(closed)socketClosed.set(true);}
        @Override public void workerExited(){exited.countDown();}
    }
    private static boolean smtpThreads(){return Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().startsWith("armoney-strict-smtp"));}
    private static final class LogCapture implements AutoCloseable {
        final List<LogRecord> records=new CopyOnWriteArrayList<>();final Logger root=Logger.getLogger("");final Level previous=root.getLevel();
        final Handler handler=new Handler(){@Override public void publish(LogRecord record){records.add(record);}@Override public void flush(){}@Override public void close(){}};
        LogCapture(){handler.setLevel(Level.ALL);root.addHandler(handler);root.setLevel(Level.ALL);}
        void assertPrivate(){
            for(LogRecord record:records){
                StringWriter errors=new StringWriter();if(record.getThrown()!=null)record.getThrown().printStackTrace(new PrintWriter(errors));
                String rendered=record.getMessage()+Arrays.toString(record.getParameters())+errors;
                String logger=String.valueOf(record.getLoggerName());
                String namespace=logger.startsWith("com.arman.bank.sso.smtp")?"STRICT_SMTP":logger.startsWith("org.eclipse.angus")?"ANGUS":"OTHER";
                var canaries=List.of("synthetic-user","synthetic-password","recipient@example.test","token-bearing-body","AUTH PLAIN");
                var categories=List.of("USER","PASSWORD","RECIPIENT","BODY","AUTH_WIRE");
                for(int i=0;i<canaries.size();i++)assertFalse(rendered.contains(canaries.get(i)),"SMTP_PRIVACY_"+namespace+"_"+categories.get(i));
                assertFalse(logger.equals("com.arman.bank.sso.smtp")||logger.startsWith("com.arman.bank.sso.smtp."),"SMTP_PRIVACY_STRICT_SMTP_PROTOCOL_RECORD");
            }
        }
        @Override public void close(){root.setLevel(previous);root.removeHandler(handler);}
    }
    static final class SmtpFixture implements AutoCloseable {
        final ServerSocket listener;final List<String> commands=new CopyOnWriteArrayList<>();final CountDownLatch accepted=new CountDownLatch(1);
        final CountDownLatch dataNotReading=new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger accepts=new java.util.concurrent.atomic.AtomicInteger();final CompletableFuture<Void> done=new CompletableFuture<>();
        final String mode;final Thread worker;volatile Socket socket;volatile String message="";volatile boolean authenticated,tlsActive,closing;volatile Throwable failure;
        SmtpFixture(String mode)throws IOException{
            this.mode=mode;
            listener=new ServerSocket();if(mode.equals("blocked-write"))listener.setReceiveBufferSize(1024);
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),1);listener.setSoTimeout(5000);
            worker=Thread.ofPlatform().name("synthetic-smtp-fixture").daemon(true).start(this::serve);
        }
        int port(){return listener.getLocalPort();}
        void serve(){
            try{
                socket=listener.accept();if(mode.equals("blocked-write"))socket.setReceiveBufferSize(1024);socket.setSoTimeout(5000);accepts.incrementAndGet();accepted.countDown();
                if(mode.equals("greeting-stall")){while(socket.getInputStream().read()!=-1){}return;}
                if(mode.equals("greeting-drip")){for(int i=0;i<100;i++){socket.getOutputStream().write('2');socket.getOutputStream().flush();Thread.sleep(100);}return;}
                BufferedReader reader=reader();BufferedWriter writer=writer();reply(writer,"220 synthetic SMTP");
                String line;while((line=reader.readLine())!=null){
                    commands.add(line.startsWith("AUTH ")?"AUTH [redacted]":line);
                    if(line.startsWith("EHLO ")){reply(writer,"250-localhost");if(!tlsActive&&!mode.equals("missing"))reply(writer,"250-STARTTLS");reply(writer,"250 AUTH PLAIN LOGIN");}
                    else if(line.equals("STARTTLS")){
                        if(mode.equals("refused")){reply(writer,"454 synthetic refusal");continue;}
                        reply(writer,"220 Begin TLS");
                        if(mode.equals("handshake-stall")){while(socket.getInputStream().read()!=-1){}return;}
                        socket=serverTls.getSocketFactory().createSocket(socket,"localhost",port(),true);((SSLSocket)socket).setUseClientMode(false);((SSLSocket)socket).startHandshake();
                        tlsActive=true;reader=reader();writer=writer();
                    }else if(line.startsWith("AUTH PLAIN ")){
                        String[] credentials=new String(Base64.getDecoder().decode(line.substring(11)),StandardCharsets.UTF_8).split("\u0000",-1);
                        authenticated=credentials.length==3&&credentials[1].equals("synthetic-user")&&credentials[2].equals("synthetic-password");reply(writer,authenticated?"235 Authenticated":"535 Rejected");
                    }else if(line.startsWith("MAIL FROM:")||line.startsWith("RCPT TO:"))reply(writer,"250 Accepted");
                    else if(line.equals("DATA")){
                        reply(writer,"354 Send content");
                        if(mode.equals("blocked-write")){dataNotReading.countDown();while(!closing)Thread.sleep(10);return;}
                        StringBuilder data=new StringBuilder();while((line=reader.readLine())!=null&&!line.equals(".")){if(data.length()>200000)throw new IOException("Fixture body bound");data.append(line).append('\n');}message=data.toString();
                        if(mode.equals("data-ack-stall")){while(reader.read()!=-1){}return;}reply(writer,"250 Accepted");
                    }else if(line.equals("QUIT")){reply(writer,"221 Goodbye");return;}else throw new IOException("Unexpected synthetic SMTP command");
                }
            }catch(Exception e){if(mode.equals("positive")&&!closing)failure=e;}
            finally{try{if(socket!=null)socket.close();}catch(IOException ignored){}done.complete(null);}
        }
        BufferedReader reader()throws IOException{return new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));}
        BufferedWriter writer()throws IOException{return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));}
        static void reply(BufferedWriter writer,String value)throws IOException{writer.write(value+"\r\n");writer.flush();}
        void await()throws Exception{done.get(6,TimeUnit.SECONDS);}
        @Override public void close()throws Exception{closing=true;listener.close();if(socket!=null)socket.close();worker.join(2000);assertFalse(worker.isAlive());}
    }
}
