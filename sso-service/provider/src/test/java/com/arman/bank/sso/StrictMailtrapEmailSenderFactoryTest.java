package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.email.*;
import org.keycloak.models.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class StrictMailtrapEmailSenderFactoryTest {
    static KeycloakSession session(String realm,EmailSenderProvider stock){
        var model=CodeSessionObservationTest.model(RealmModel.class,Map.of("getName",realm));
        var context=CodeSessionObservationTest.model(KeycloakContext.class,Map.of("getRealm",model));
        var values=new HashMap<String,Object>();values.put("getContext",context);values.put("getProvider",(CodeSessionObservationTest.Call)args->{
            assertEquals(EmailSenderProvider.class,args[0]);assertEquals("default",args[1]);return stock;
        });return CodeSessionObservationTest.model(KeycloakSession.class,values);
    }
    @Test void packagingDoesNotReplaceStockDefaultAndFactoryHasNoStartupSideEffects(){
        var factory=new StrictMailtrapEmailSenderFactory();assertEquals("armoney-strict-mailtrap",factory.getId());assertEquals(0,factory.order());
        factory.init(null);factory.postInit(null);try(var provider=factory.create(session("armoney",null))){assertNotNull(provider);}factory.close();
    }
    @Test void selectedProviderDelegatesOnlyUnrelatedRealmsToExplicitStockId()throws Exception{
        var sends=new AtomicInteger();var validations=new AtomicInteger();
        var stock=new EmailSenderProvider(){
            @Override public void send(Map<String,String> config,String address,String subject,String text,String html){sends.incrementAndGet();}
            @Override public void validate(Map<String,String> config){validations.incrementAndGet();}
            @Override public void close(){fail("Request sender does not own stock provider lifetime");}
        };
        try(var sender=new StrictMailtrapEmailSenderFactory().create(session("master",stock))){sender.validate(Map.of());sender.send(Map.of(),"unrelated","subject","text",null);}
        assertEquals(1,sends.get());assertEquals(1,validations.get());
        try(var sender=new StrictMailtrapEmailSenderFactory().create(session("armoney",stock))){assertThrows(EmailException.class,()->sender.send(Map.of(),"recipient@example.test","subject","text",null));}
        assertEquals(1,sends.get());assertEquals(1,validations.get());
    }
    @Test void missingStockDelegateAndClosedProviderFailWithSanitizedErrors(){
        var sender=new StrictMailtrapEmailSenderFactory().create(session("master",null));
        EmailException missing=assertThrows(EmailException.class,()->sender.validate(Map.of()));assertEquals("ARMONEY_SMTP_UNAVAILABLE",missing.getMessage());assertNull(missing.getCause());
        sender.close();assertThrows(EmailException.class,()->sender.validate(Map.of()));
    }
}
