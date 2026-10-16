package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.NotificationDelivery.Result;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.logging.*;

/** Sandbox-only SMTP. Provider text and exceptions never leave this adapter. */
final class MailtrapSmtp {
    private final EmailConfiguration config;
    private final String host;
    private final int port;
    private final SSLSocketFactory tls;
    private final long deadlineMillis;
    // JUL holds named loggers weakly; retain these to keep protocol suppression effective.
    private static final Logger[] PROTOCOL_LOGGERS = {
        Logger.getLogger("org.eclipse.angus.mail.smtp"),
        Logger.getLogger("org.eclipse.angus.mail.smtp.protocol"),
        Logger.getLogger("org.eclipse.angus.mail.util")
    };
    static {
        // Angus also emits protocol data through JUL independently of Session.debug.
        for (Logger logger : PROTOCOL_LOGGERS) logger.setLevel(Level.OFF);
    }
    MailtrapSmtp(EmailConfiguration config) { this(config,config.smtpHost,config.smtpPort,null,15000); }
    /** Package-private seam for loopback fixtures with their own trust store, never environment-configurable. */
    MailtrapSmtp(EmailConfiguration config,String host,int port,SSLSocketFactory tls,long deadlineMillis) {
        this.config=config; this.host=host; this.port=port; this.tls=tls; this.deadlineMillis=deadlineMillis;
    }
    static Properties properties() {
        var p=new Properties();
        p.setProperty("mail.smtp.auth","true");
        p.setProperty("mail.smtp.auth.mechanisms","PLAIN LOGIN");
        p.setProperty("mail.smtp.starttls.enable","true");
        p.setProperty("mail.smtp.starttls.required","true");
        p.setProperty("mail.smtp.ssl.checkserveridentity","true");
        p.setProperty("mail.smtp.ssl.protocols","TLSv1.3 TLSv1.2");
        p.setProperty("mail.smtp.connectiontimeout","5000");
        p.setProperty("mail.smtp.timeout","5000");
        p.setProperty("mail.smtp.writetimeout","5000");
        p.setProperty("mail.smtp.socketFactory.fallback","false");
        p.setProperty("mail.smtp.quitwait","false");
        p.setProperty("mail.smtp.localhost","armoney.invalid");
        p.setProperty("mail.debug","false");
        p.setProperty("mail.debug.auth","false");
        return p;
    }
    Result send(String recipient,String subject,String text) throws InterruptedException {
        return send(recipient,subject,text,null);
    }
    Result send(String recipient,String subject,String text,String html) throws InterruptedException {
        // Closing this underlying socket also aborts a wrapped STARTTLS socket. The
        // caller returns on the deadline even if platform DNS resolution is delayed.
        var socket=new Socket();
        // Own the scheduler across both the plaintext and STARTTLS socket wrappers.
        // Closing the raw socket alone does not dispose Angus write-timeout pools.
        var writes=new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("mailtrap-smtp-write-timeout").daemon(false).factory());
        writes.setRemoveOnCancelPolicy(true);
        var task=new FutureTask<Result>(() -> exchange(socket,writes,recipient,subject,text,html));
        Thread.startVirtualThread(task);
        try { return task.get(deadlineMillis,TimeUnit.MILLISECONDS); }
        catch (TimeoutException | ExecutionException e) { return Result.TRANSIENT_FAILURE; }
        finally {
            close(socket);
            task.cancel(true);
            writes.shutdownNow();
        }
    }
    private Result exchange(Socket socket,ScheduledExecutorService writes,String recipient,String subject,String text,String html) {
        var p=properties();
        p.put("mail.smtp.executor.writetimeout",writes);
        p.put("mail.smtp.socketFactory",new AttemptSocketFactory(socket));
        if (tls!=null) p.put("mail.smtp.ssl.socketFactory",tls);
        var session=Session.getInstance(p);
        session.setDebug(false);
        SMTPTransport transport=new SMTPTransport(session,null);
        try {
            var message=new MimeMessage(session);
            var from=new InternetAddress(config.fromEmail,true);
            from.validate();
            if (from.isGroup()) return Result.PERMANENT_FAILURE;
            from.setPersonal(config.fromName,"UTF-8");
            message.setFrom(from);
            // Strict parsing and one mailbox prevent an address-list injection.
            var address=new InternetAddress(recipient,true);
            address.validate();
            if (address.isGroup()) return Result.PERMANENT_FAILURE;
            message.setRecipient(Message.RecipientType.TO,address);
            message.setSubject(subject,"UTF-8");
            if (html==null) message.setText(text,"UTF-8");
            else {
                var alternatives=new MimeMultipart("alternative");
                var plain=new MimeBodyPart();
                plain.setText(text,"UTF-8");
                alternatives.addBodyPart(plain);
                var rich=new MimeBodyPart();
                rich.setText(html,"UTF-8","html");
                alternatives.addBodyPart(rich);
                message.setContent(alternatives);
            }
            message.saveChanges();
            transport.connect(host,port,config.smtpUsername,config.smtpPassword);
            transport.sendMessage(message,message.getAllRecipients());
            return Result.ACCEPTED; // SMTP DATA acknowledgement, not mailbox delivery.
        } catch (AddressException e) {
            return Result.PERMANENT_FAILURE;
        } catch (MessagingException e) {
            int code=replyCode(e,transport.getLastReturnCode());
            if (code>=400 && code<500) return Result.TRANSIENT_FAILURE;
            if (code>=500 && code<600 || e instanceof AuthenticationFailedException) return Result.PERMANENT_FAILURE;
            return Result.TRANSIENT_FAILURE;
        } catch (IOException e) {
            return Result.TRANSIENT_FAILURE;
        } finally {
            close(socket);
            try { transport.close(); } catch (MessagingException ignored) { /* No provider data in logs. */ }
        }
    }
    private static int replyCode(MessagingException failure,int fallback) {
        Exception current=failure;
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Exception,Boolean>());
        while (current!=null && seen.add(current)) {
            if (current instanceof SMTPAddressFailedException address) return address.getReturnCode();
            if (current instanceof SMTPSendFailedException send && send.getReturnCode()>=400) return send.getReturnCode();
            current=current instanceof MessagingException mail ? mail.getNextException() : null;
        }
        return fallback;
    }
    private static void close(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
    private static final class AttemptSocketFactory extends SocketFactory {
        private final Socket socket;
        AttemptSocketFactory(Socket socket) { this.socket=socket; }
        @Override public Socket createSocket() { return socket; }
        @Override public Socket createSocket(String h,int p) throws IOException { throw new IOException("Unsupported socket operation"); }
        @Override public Socket createSocket(String h,int p,InetAddress l,int lp) throws IOException { throw new IOException("Unsupported socket operation"); }
        @Override public Socket createSocket(InetAddress h,int p) throws IOException { throw new IOException("Unsupported socket operation"); }
        @Override public Socket createSocket(InetAddress h,int p,InetAddress l,int lp) throws IOException { throw new IOException("Unsupported socket operation"); }
    }
}
