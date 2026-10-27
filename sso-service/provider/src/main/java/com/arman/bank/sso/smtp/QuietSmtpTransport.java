package com.arman.bank.sso.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Isolated SMTP logger namespace; no verification or identity authority. */
public final class QuietSmtpTransport extends SMTPTransport {
    // Angus MailLogger(Class) derives the PACKAGE, not the class name. Retain the
    // loggers so JUL's weak references cannot silently discard this privacy policy.
    private static final Logger[] QUIET={Logger.getLogger(QuietSmtpTransport.class.getPackageName()),
            Logger.getLogger(QuietSmtpTransport.class.getPackageName()+".protocol")};
    static{for(Logger logger:QUIET)logger.setLevel(Level.OFF);}
    private final DataWriteObserver observer;
    public QuietSmtpTransport(Session session,DataWriteObserver observer){super(session,null);this.observer=observer;}
    /** Nonblocking test instrumentation only; no content, contacts, secrets or exceptions. */
    public interface DataWriteObserver {
        void entered(Thread worker);
        void returned();
        void socketClosed(boolean closed);
        void workerExited();
    }
    @Override protected OutputStream data()throws MessagingException{
        OutputStream output=super.data();
        if(observer==null)return output;
        return new FilterOutputStream(output){
            @Override public void write(int value)throws IOException{observer.entered(Thread.currentThread());try{out.write(value);}finally{observer.returned();}}
            @Override public void write(byte[] bytes,int offset,int length)throws IOException{observer.entered(Thread.currentThread());try{out.write(bytes,offset,length);}finally{observer.returned();}}
            @Override public void flush()throws IOException{observer.entered(Thread.currentThread());try{out.flush();}finally{observer.returned();}}
        };
    }
}
