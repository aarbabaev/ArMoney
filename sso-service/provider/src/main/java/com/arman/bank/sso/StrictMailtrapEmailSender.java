package com.arman.bank.sso;

import org.keycloak.email.*;
import org.keycloak.models.KeycloakSession;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.UnaryOperator;

/** Strict SMTP submission only. Templates and action-token authority remain in Keycloak. */
final class StrictMailtrapEmailSender implements EmailSenderProvider {
    static final int HEADER_LIMIT=256,BODY_LIMIT=65536;
    private static final Set<String> CONFIG=Set.of("host","port","auth","authType","starttls","ssl","debug","allowutf8",
            "user","password","from","fromDisplayName","timeout","connectionTimeout","writeTimeout");
    private final KeycloakSession session;
    private final StrictMailtrapSmtpTransport transport;
    private final UnaryOperator<String> secret;
    private volatile boolean closed;
    StrictMailtrapEmailSender(KeycloakSession session){this(session,new StrictMailtrapSmtpTransport(),value->{
        try(var resolved=session.vault().getStringSecret(value)){return resolved.get().orElse(value);}
    });}
    /** Synthetic collaborators cannot be selected through realm configuration or the factory. */
    StrictMailtrapEmailSender(KeycloakSession session,StrictMailtrapSmtpTransport transport,UnaryOperator<String> secret){
        this.session=Objects.requireNonNull(session);this.transport=Objects.requireNonNull(transport);this.secret=Objects.requireNonNull(secret);
    }
    private boolean strict(){
        if(session.getContext()==null||session.getContext().getRealm()==null)throw new IllegalStateException();
        return "armoney".equals(session.getContext().getRealm().getName());
    }
    private EmailSenderProvider stock(){
        EmailSenderProvider provider=session.getProvider(EmailSenderProvider.class,"default");
        if(provider==null||provider==this||provider instanceof StrictMailtrapEmailSender)throw new IllegalStateException();return provider;
    }
    private boolean strictOrReject()throws EmailException{try{return strict();}catch(Exception failure){throw rejected();}}
    private EmailSenderProvider stockOrReject()throws EmailException{try{return stock();}catch(Exception failure){throw rejected();}}
    @Override public void send(Map<String,String> config,String address,String subject,String textBody,String htmlBody)throws EmailException{
        if(closed)throw rejected();
        if(!strictOrReject()){stockOrReject().send(config,address,subject,textBody,htmlBody);return;}
        try{
            Settings settings=settings(config);mailbox(address);header(subject,false);
            if((textBody!=null&&textBody.length()>BODY_LIMIT)||(htmlBody!=null&&htmlBody.length()>BODY_LIMIT)
                    ||(long)bytes(textBody)+bytes(htmlBody)>BODY_LIMIT||textBody==null&&htmlBody==null)throw new IllegalArgumentException();
            String password=secret.apply(settings.password());credential(password);
            settings=new Settings(settings.user(),password,settings.from(),settings.display());
            if(closed||!transport.send(settings,address,subject,textBody,htmlBody))throw new IllegalStateException();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw rejected();}
        catch(Exception failure){throw rejected();}
    }
    @Override public void validate(Map<String,String> config)throws EmailException{
        if(closed)throw rejected();
        if(!strictOrReject()){stockOrReject().validate(config);return;}
        try{settings(config);}
        catch(Exception failure){throw rejected();}
    }
    static Settings settings(Map<String,String> config){
        if(config==null||!CONFIG.containsAll(config.keySet())||config.values().stream().anyMatch(Objects::isNull)
                ||!"sandbox.smtp.mailtrap.io".equals(config.get("host"))||!"2525".equals(config.get("port"))
                ||!"true".equals(config.get("auth"))||!"true".equals(config.get("starttls"))
                ||!"false".equals(config.getOrDefault("ssl","false"))||!"false".equals(config.getOrDefault("debug","false"))
                ||!"false".equals(config.getOrDefault("allowutf8","false"))||!"basic".equals(config.getOrDefault("authType","basic")))throw new IllegalArgumentException();
        for(String timeout:List.of("timeout","connectionTimeout","writeTimeout"))if(config.containsKey(timeout)&&!"5000".equals(config.get(timeout)))throw new IllegalArgumentException();
        String user=config.get("user"),password=config.get("password"),from=config.get("from"),display=config.getOrDefault("fromDisplayName","");
        credential(user);credential(password);mailbox(from);header(display,true);return new Settings(user,password,from,display);
    }
    static void mailbox(String value){
        header(value,false);
        if(!EnrollmentObservation.canonicalEmail(value))throw new IllegalArgumentException();
        try{
            var address=new jakarta.mail.internet.InternetAddress(value,true);address.validate();
            if(address.isGroup()||address.getPersonal()!=null||!value.equals(address.getAddress()))throw new IllegalArgumentException();
        }catch(jakarta.mail.internet.AddressException invalid){throw new IllegalArgumentException();}
    }
    private static void header(String value,boolean empty){
        if(value==null||(!empty&&value.isEmpty())||value.length()>HEADER_LIMIT||bytes(value)>HEADER_LIMIT||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
    }
    private static void credential(String value){if(value==null||value.isBlank()||value.length()>2048||bytes(value)>2048||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException();}
    private static int bytes(String value){return value==null?0:value.getBytes(StandardCharsets.UTF_8).length;}
    private static EmailException rejected(){return new EmailException("ARMONEY_SMTP_UNAVAILABLE");}
    record Settings(String user,String password,String from,String display){@Override public String toString(){return "StrictSmtpSettings[redacted]";}}
    @Override public void close(){closed=true;transport.close();}
}
