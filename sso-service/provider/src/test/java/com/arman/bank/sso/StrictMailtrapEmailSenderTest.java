package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.email.EmailException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class StrictMailtrapEmailSenderTest {
    static Map<String,String> config(){return new HashMap<>(Map.of("host","sandbox.smtp.mailtrap.io","port","2525","auth","true","starttls","true",
            "ssl","false","debug","false","user","synthetic-user","password","synthetic-password","from","bank@example.test","fromDisplayName","ArMoney"));}
    static StrictMailtrapEmailSender.Settings settings(){return StrictMailtrapEmailSender.settings(config());}
    private static StrictMailtrapEmailSender sender(){return new StrictMailtrapEmailSender(StrictMailtrapEmailSenderFactoryTest.session("armoney",null),new StrictMailtrapSmtpTransport(),value->{throw new AssertionError("Invalid input must be rejected before secret resolution or network");});}
    @Test void strictConfigurationRejectsTransportOverridesAndMalformedCredentials(){
        for(var entry:Map.ofEntries(Map.entry("host","attacker.test"),Map.entry("port","587"),Map.entry("auth","false"),Map.entry("starttls","false"),
                Map.entry("ssl","true"),Map.entry("debug","true"),Map.entry("allowutf8","true"),Map.entry("authType","token"),Map.entry("timeout","10000"),
                Map.entry("connectionTimeout","0"),Map.entry("writeTimeout","-1"),Map.entry("mail.smtp.ssl.trust","*"),Map.entry("password","synthetic\r\nAUTH"),Map.entry("user","")).entrySet()){
            var config=config();config.put(entry.getKey(),entry.getValue());assertThrows(IllegalArgumentException.class,()->StrictMailtrapEmailSender.settings(config),entry.getKey());
        }
        for(String field:List.of("host","port","auth","starttls","user","password","from")){var config=config();config.remove(field);assertThrows(IllegalArgumentException.class,()->StrictMailtrapEmailSender.settings(config),field);}
        assertEquals("StrictSmtpSettings[redacted]",settings().toString());
    }
    @Test void oneCanonicalMailboxAndBoundedHeadersAndBodiesAreRequiredBeforeNetwork(){
        try(var sender=sender()){
            for(String address:List.of("one@example.test,two@example.test","group:one@example.test;","one@example.test\r\nBcc:two@example.test","Person@example.test","Name <one@example.test>"))
                sanitized(assertThrows(EmailException.class,()->sender.send(config(),address,"subject","text",null)));
            for(String subject:List.of("header\r\nBcc:secret@example.test","s".repeat(257),"é".repeat(129)))
                sanitized(assertThrows(EmailException.class,()->sender.send(config(),"person@example.test",subject,"text",null)));
            sanitized(assertThrows(EmailException.class,()->sender.send(config(),"person@example.test","subject","a".repeat(65537),null)));
            sanitized(assertThrows(EmailException.class,()->sender.send(config(),"person@example.test","subject","é".repeat(32769),null)));
            sanitized(assertThrows(EmailException.class,()->sender.send(config(),"person@example.test","subject","a".repeat(32768),"a".repeat(32769))));
            sanitized(assertThrows(EmailException.class,()->sender.send(config(),"person@example.test","subject",null,null)));
        }
        var config=config();config.put("fromDisplayName","é".repeat(129));assertThrows(IllegalArgumentException.class,()->StrictMailtrapEmailSender.settings(config));
    }
    @Test void validationHasNoNetworkAndResolvedSecretFailuresDoNotEscape(){
        var transport=new StrictMailtrapSmtpTransport();
        try(var sender=new StrictMailtrapEmailSender(StrictMailtrapEmailSenderFactoryTest.session("armoney",null),transport,value->{throw new IllegalStateException("synthetic-password secret@example.test");})){
            assertDoesNotThrow(()->sender.validate(config()));sanitized(assertThrows(EmailException.class,()->sender.send(config(),"person@example.test","subject","token-bearing-body",null)));
        }
    }
    static void sanitized(EmailException failure){assertEquals("ARMONEY_SMTP_UNAVAILABLE",failure.getMessage());assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);}
}
