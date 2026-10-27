package com.arman.bank.sso;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import com.arman.bank.sso.smtp.QuietSmtpTransport;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded SMTP adapter, with a tracked plain socket and normal production JSSE trust. */
final class StrictMailtrapSmtpTransport implements AutoCloseable {
    private static final Semaphore INFLIGHT=new Semaphore(1);
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(0,1,1,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().name("armoney-strict-smtp").daemon(true).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final String host;private final int port;private final SSLSocketFactory testTls;
    private final long workMillis,cleanupMillis;
    private final int testSendBuffer;
    private final DataWriteObserver testObserver;
    private final AtomicReference<Attempt> current=new AtomicReference<>();private volatile boolean closed;
    StrictMailtrapSmtpTransport(){this("sandbox.smtp.mailtrap.io",2525,null,14000,1000);}
    /** Loopback TLS fixtures only; never reachable from production config or environment. */
    StrictMailtrapSmtpTransport(String host,int port,SSLSocketFactory tls,long workMillis,long cleanupMillis){
        this(host,port,tls,workMillis,cleanupMillis,0,null);
    }
    /** Nonblocking instrumentation and advisory plain-socket buffer for synthetic loopback tests only. */
    StrictMailtrapSmtpTransport(String host,int port,SSLSocketFactory tls,long workMillis,long cleanupMillis,int sendBuffer,DataWriteObserver observer){
        this.host=host;this.port=port;this.testTls=tls;this.workMillis=workMillis;this.cleanupMillis=cleanupMillis;
        this.testSendBuffer=sendBuffer;this.testObserver=observer;
        if(workMillis<1||workMillis>14000||cleanupMillis<0||cleanupMillis>1000||sendBuffer<0)throw new IllegalArgumentException();
    }
    static Properties properties(){
        var p=new Properties();p.setProperty("mail.smtp.auth","true");p.setProperty("mail.smtp.auth.mechanisms","PLAIN LOGIN");
        p.setProperty("mail.smtp.starttls.enable","true");p.setProperty("mail.smtp.starttls.required","true");p.setProperty("mail.smtp.ssl.checkserveridentity","true");
        p.setProperty("mail.smtp.ssl.protocols","TLSv1.3 TLSv1.2");p.setProperty("mail.smtp.connectiontimeout","5000");p.setProperty("mail.smtp.timeout","5000");
        p.setProperty("mail.smtp.writetimeout","5000");p.setProperty("mail.smtp.socketFactory.fallback","false");p.setProperty("mail.smtp.quitwait","false");
        p.setProperty("mail.smtp.localhost","armoney.invalid");p.setProperty("mail.debug","false");p.setProperty("mail.debug.auth","false");return p;
    }
    boolean send(StrictMailtrapEmailSender.Settings settings,String recipient,String subject,String text,String html)throws InterruptedException{
        if(closed||!INFLIGHT.tryAcquire())return false;
        final Attempt attempt;
        try{attempt=new Attempt(testSendBuffer,testObserver);}catch(IOException unavailable){INFLIGHT.release();return false;}
        if(!current.compareAndSet(null,attempt)){attempt.abort();attempt.writes.shutdownNow();INFLIGHT.release();return false;}
        var task=new FutureTask<Boolean>(()->{
            try{return !closed&&exchange(attempt,settings,recipient,subject,text,html);}
            finally{attempt.abort();attempt.writes.shutdownNow();current.compareAndSet(attempt,null);attempt.exited.countDown();INFLIGHT.release();if(testObserver!=null)testObserver.workerExited();}
        });
        try{
            try{WORKER.execute(task);}catch(RejectedExecutionException rejected){attempt.abort();attempt.writes.shutdownNow();current.compareAndSet(attempt,null);INFLIGHT.release();return false;}
            try{return task.get(workMillis,TimeUnit.MILLISECONDS);}
            catch(ExecutionException|TimeoutException unavailable){return false;}
            finally{
                // Socket.close does not require SMTPTransport's protocol monitor. DNS resolution
                // may remain blocked; the single process permit is held until actual worker exit.
                attempt.abort();attempt.writes.shutdownNow();attempt.exited.await(cleanupMillis,TimeUnit.MILLISECONDS);
            }
        }finally{attempt.abort();}
    }
    private boolean exchange(Attempt attempt,StrictMailtrapEmailSender.Settings settings,String recipient,String subject,String text,String html){
        Properties p=properties();p.put("mail.smtp.socketFactory",new TrackingSocketFactory(attempt));p.put("mail.smtp.executor.writetimeout",attempt.writes);
        if(testTls!=null)p.put("mail.smtp.ssl.socketFactory",testTls);
        Session session=Session.getInstance(p);session.setDebug(false);
        QuietSmtpTransport transport=new QuietSmtpTransport(session,testObserver);
        try{
            MimeMessage message=new MimeMessage(session);InternetAddress from=new InternetAddress(settings.from(),true);
            from.setPersonal(settings.display(),"UTF-8");message.setFrom(from);message.setRecipient(Message.RecipientType.TO,new InternetAddress(recipient,true));message.setSubject(subject,"UTF-8");
            MimeMultipart body=new MimeMultipart("alternative");
            if(text!=null){var part=new MimeBodyPart();part.setText(text,"UTF-8");body.addBodyPart(part);}
            if(html!=null){var part=new MimeBodyPart();part.setText(html,"UTF-8","html");body.addBodyPart(part);}
            message.setContent(body);message.saveChanges();
            if(closed||attempt.aborted.get())return false;
            transport.connect(host,port,settings.user(),settings.password());
            if(closed||attempt.aborted.get()||!transport.isSSL())return false;
            transport.sendMessage(message,message.getAllRecipients());return !closed&&!attempt.aborted.get();
        }catch(Exception unavailable){return false;}
        finally{attempt.abort();try{transport.close();}catch(MessagingException ignored){}}
    }
    @Override public void close(){closed=true;var attempt=current.get();if(attempt!=null){attempt.abort();attempt.writes.shutdownNow();}}
    /** No content, endpoint, credentials or exception material crosses this test seam. */
    interface DataWriteObserver extends QuietSmtpTransport.DataWriteObserver { }
    private static final class Attempt {
        final Socket socket=new Socket();final AtomicBoolean aborted=new AtomicBoolean();final AtomicBoolean supplied=new AtomicBoolean();
        final DataWriteObserver observer;
        final CountDownLatch exited=new CountDownLatch(1);
        final ScheduledThreadPoolExecutor writes=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("armoney-strict-smtp-write").daemon(true).factory());
        Attempt(int sendBuffer,DataWriteObserver observer)throws IOException{
            this.observer=observer;writes.setRemoveOnCancelPolicy(true);
            try{if(sendBuffer>0)socket.setSendBufferSize(sendBuffer);}catch(IOException failure){abort();writes.shutdownNow();throw failure;}
        }
        void abort(){
            aborted.set(true);try{socket.close();}catch(IOException ignored){}
            if(observer!=null)observer.socketClosed(socket.isClosed());
        }
    }
    private static final class TrackingSocketFactory extends SocketFactory {
        private final Attempt attempt;TrackingSocketFactory(Attempt attempt){this.attempt=attempt;}
        @Override public Socket createSocket()throws IOException{
            if(attempt.aborted.get()||!attempt.supplied.compareAndSet(false,true))throw new IOException("SMTP_SOCKET_UNAVAILABLE");return attempt.socket;
        }
        private Socket connect(InetSocketAddress remote,InetAddress local,int localPort)throws IOException{
            Socket socket=createSocket();if(local!=null)socket.bind(new InetSocketAddress(local,localPort));socket.connect(remote,5000);socket.setSoTimeout(5000);return socket;
        }
        @Override public Socket createSocket(String host,int port)throws IOException{return connect(new InetSocketAddress(host,port),null,0);}
        @Override public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{return connect(new InetSocketAddress(host,port),local,localPort);}
        @Override public Socket createSocket(InetAddress host,int port)throws IOException{return connect(new InetSocketAddress(host,port),null,0);}
        @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{return connect(new InetSocketAddress(host,port),local,localPort);}
    }
}
