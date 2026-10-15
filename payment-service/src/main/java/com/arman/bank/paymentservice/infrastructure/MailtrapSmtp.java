package com.arman.bank.paymentservice.infrastructure;

import com.arman.bank.paymentservice.application.EmailDelivery.Result;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.logging.*;

/** Sandbox-only SMTP. Provider text and exceptions never leave this adapter. */
final class MailtrapSmtp {
    enum Stage { LOCAL_SENDER, LOCAL_RECIPIENT, AUTH, MAIL, RCPT, DATA, DATA_ACK, UNKNOWN }
    enum Enhanced { NONE, OTHER, ADDRESS, MAILBOX, MAIL_SYSTEM, NETWORK_ROUTING, PROTOCOL, CONTENT, SECURITY_POLICY }
    record Diagnostic(Stage stage,int code,Enhanced enhanced) {}
    private static final Diagnostic UNKNOWN=new Diagnostic(Stage.UNKNOWN,0,Enhanced.NONE);
    private static final Logger DIAGNOSTICS=Logger.getLogger(MailtrapSmtp.class.getName());
    private static final Pattern ENHANCED=Pattern.compile("^([1-5][0-9]{2})[ -]([245])\\.([0-7])\\.[0-9]{1,3}(?:\\s|$)");
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
        var observed=new AtomicReference<>(UNKNOWN);
        var task=new FutureTask<Result>(() -> exchange(socket,writes,recipient,subject,text,html,observed));
        Thread.startVirtualThread(task);
        final Result result;
        final Diagnostic diagnostic;
        try { result=task.get(deadlineMillis,TimeUnit.MILLISECONDS); diagnostic=observed.get(); }
        catch (TimeoutException | ExecutionException e) {
            return failed(Result.TRANSIENT_FAILURE,UNKNOWN);
        } catch (InterruptedException e) {
            failed(Result.TRANSIENT_FAILURE,UNKNOWN);
            throw e;
        }
        finally {
            close(socket);
            task.cancel(true);
            writes.shutdownNow();
        }
        return result==Result.ACCEPTED ? result : failed(result,diagnostic);
    }
    private static Result failed(Result result,Diagnostic diagnostic) {
        // All parameters are local enums or a bounded integer, never provider text.
        DIAGNOSTICS.log(Level.WARNING,"SMTP attempt failed stage={0} code={1} enhanced={2} result={3}",
            new Object[]{diagnostic.stage(),diagnostic.code(),diagnostic.enhanced(),result});
        return result;
    }
    private Result exchange(Socket socket,ScheduledExecutorService writes,String recipient,String subject,String text,String html,AtomicReference<Diagnostic> observed) {
        var p=properties();
        p.put("mail.smtp.executor.writetimeout",writes);
        p.put("mail.smtp.socketFactory",new AttemptSocketFactory(socket));
        if (tls!=null) p.put("mail.smtp.ssl.socketFactory",tls);
        var session=Session.getInstance(p);
        session.setDebug(false);
        SMTPTransport transport=new SMTPTransport(session,null);
        Stage local=Stage.LOCAL_SENDER;
        try {
            var message=new MimeMessage(session);
            var from=new InternetAddress(config.fromEmail,true);
            from.validate();
            if (from.isGroup()) { observed.set(new Diagnostic(local,0,Enhanced.NONE)); return Result.PERMANENT_FAILURE; }
            from.setPersonal(config.fromName,"UTF-8");
            message.setFrom(from);
            // Strict parsing and one mailbox prevent an address-list injection.
            local=Stage.LOCAL_RECIPIENT;
            var address=new InternetAddress(recipient,true);
            address.validate();
            if (address.isGroup()) { observed.set(new Diagnostic(local,0,Enhanced.NONE)); return Result.PERMANENT_FAILURE; }
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
            observed.set(new Diagnostic(local,0,Enhanced.NONE));
            return Result.PERMANENT_FAILURE;
        } catch (MessagingException e) {
            int code=replyCode(e,transport.getLastReturnCode());
            observed.set(diagnostic(e,code,transport.getLastServerResponse()));
            if (code==550 && config.mode.equals("sandbox") && sandboxThrottle(e,transport.getLastServerResponse()))
                return Result.RATE_LIMITED;
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
    static Diagnostic diagnostic(MessagingException failure,int fallback,String response) {
        Exception current=failure;
        Diagnostic send=null;
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Exception,Boolean>());
        while (current!=null && seen.add(current)) {
            if (current instanceof SMTPAddressFailedException address)
                return detail(stage(address.getCommand()),address.getReturnCode(),address.getMessage());
            if (current instanceof SMTPSendFailedException rejected && send==null)
                send=detail(stage(rejected.getCommand()),rejected.getReturnCode(),rejected.getMessage());
            if (current instanceof AuthenticationFailedException)
                return detail(Stage.AUTH,fallback,current.getMessage());
            current=current instanceof MessagingException mail ? mail.getNextException() : null;
        }
        return send!=null ? send : detail(Stage.UNKNOWN,fallback,response);
    }
    private static Stage stage(String command) {
        if (command==null) return Stage.UNKNOWN;
        if (command.equals("DATA")) return Stage.DATA;
        if (command.equals(".")) return Stage.DATA_ACK;
        if (command.startsWith("MAIL ")) return Stage.MAIL;
        if (command.startsWith("RCPT ")) return Stage.RCPT;
        return Stage.UNKNOWN;
    }
    private static Diagnostic detail(Stage stage,int value,String response) {
        int code=value>=100 && value<=599 ? value : 0;
        Enhanced enhanced=Enhanced.NONE;
        if (response!=null && response.length()<=1024) {
            var match=ENHANCED.matcher(response.stripLeading());
            if (match.find() && Integer.parseInt(match.group(1))==code && Integer.parseInt(match.group(2))==code/100) {
                enhanced=switch(match.group(3)) {
                    case "1" -> Enhanced.ADDRESS; case "2" -> Enhanced.MAILBOX;
                    case "3" -> Enhanced.MAIL_SYSTEM; case "4" -> Enhanced.NETWORK_ROUTING;
                    case "5" -> Enhanced.PROTOCOL; case "6" -> Enhanced.CONTENT;
                    case "7" -> Enhanced.SECURITY_POLICY; default -> Enhanced.OTHER;
                };
            }
        }
        return new Diagnostic(stage,code,enhanced);
    }
    private static boolean sandboxThrottle(MessagingException failure,String response) {
        // Mailtrap Sandbox documents this exact enhanced status and reply. Do not
        // broaden permanent SMTP errors into retries or expose provider text.
        final String throttle="550 5.7.0 Requested action not taken: too many emails per second";
        boolean rejectedReply=false;
        Exception current=failure;
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Exception,Boolean>());
        while (current!=null && seen.add(current)) {
            boolean rejected=(current instanceof SMTPAddressFailedException address && address.getReturnCode()==550)
                || (current instanceof SMTPSendFailedException send && send.getReturnCode()==550);
            if (rejected) {
                rejectedReply=true;
                if (current.getMessage()!=null && throttle.equals(current.getMessage().strip())) return true;
            }
            current=current instanceof MessagingException mail ? mail.getNextException() : null;
        }
        // A later RSET/cleanup reply cannot override an authoritative rejection.
        return !rejectedReply && response!=null && throttle.equals(response.strip());
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
