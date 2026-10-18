package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.models.*;
import org.keycloak.sessions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Simulates pinned SUCCESS→remove action→evaluateTriggers lifecycle with fresh synthetic Auth status. */
class VerificationActionLifecycleTest {
    static final String EMAIL="armoney-verify-email-code",PHONE="armoney-verify-phone-code";
    @Test void emailSuccessThenTriggerReevaluationReachesMandatoryPhoneInsteadOfReaddingEmail(){
        var f=new Fixture();var email=f.action("REGISTRATION_EMAIL",EMAIL);var phone=f.action("REGISTRATION_PHONE",PHONE);
        email.evaluateTriggers(f.context);phone.evaluateTriggers(f.context);
        assertEquals(Set.of(EMAIL,PHONE),f.actions);assertEquals(0,f.reads);
        assertTrue(email.complete(f.context,f.current));assertEquals(1,f.successes);assertFalse(f.actions.contains(EMAIL));
        email.evaluateTriggers(f.context);phone.evaluateTriggers(f.context);
        assertEquals(Set.of(PHONE),f.actions);assertEquals(1,f.reads);
        // Replaying trigger evaluation never consumes a receipt or grants banking authority; it reads again.
        email.evaluateTriggers(f.context);assertEquals(Set.of(PHONE),f.actions);assertEquals(2,f.reads);
        assertEquals(0,f.terminalPages);
    }
    @Test void bindingOrContactChangesInvalidateRoutingMarkerAndRequireEmailAgain(){
        for(String changed:List.of("realm","root","tab","client","user","email","version","enrollment","revoked","unavailable")){
            var f=new Fixture();var email=f.action("REGISTRATION_EMAIL",EMAIL);assertTrue(email.complete(f.context,f.current));
            switch(changed){
                case "realm"->f.realm="other-realm";case "root"->f.root="other-root";case "tab"->f.tab="other-tab";
                case "client"->f.client="other-client";case "user"->f.user="other-user";case "email"->f.email="other@example.test";
                case "version"->f.current=status(2,true,"00000000-0000-0000-0000-000000000001");
                case "enrollment"->f.current=status(1,true,"00000000-0000-0000-0000-000000000002");
                case "revoked"->f.current=status(1,false,"00000000-0000-0000-0000-000000000001");case "unavailable"->f.unavailable=true;
            }
            email.evaluateTriggers(f.context);assertTrue(f.actions.contains(EMAIL),changed);
            assertFalse(f.notes.containsKey(EMAIL+".routing-complete"),changed);assertEquals(1,f.successes);
        }
    }
    @Test void malformedUnknownMarkerNeverSuppressesActionOrMakesRemoteInitialObservation(){
        for(String marker:List.of("{}","not-json","{\"enrollment_id\":true}","x".repeat(2049))){
            var f=new Fixture();f.notes.put(EMAIL+".routing-complete",marker);
            f.action("REGISTRATION_EMAIL",EMAIL).evaluateTriggers(f.context);assertTrue(f.actions.contains(EMAIL));assertEquals(0,f.reads);
        }
        var f=new Fixture();f.allowed=false;f.action("REGISTRATION_EMAIL",EMAIL).evaluateTriggers(f.context);assertTrue(f.actions.isEmpty());assertEquals(0,f.reads);
    }
    @Test void phoneStillRequiresProjectedDualReceiptsAndUsesEarlyInfoWithoutSuccess(){
        var f=new Fixture();var phone=f.action("REGISTRATION_PHONE",PHONE);
        phone.evaluateTriggers(f.context);assertTrue(f.actions.contains(PHONE));assertFalse(phone.complete(f.context,f.current));
        f.current=org.keycloak.util.JsonSerialization.mapper.createObjectNode().put("email_verified",true).put("phone_verified",true).put("state","PROJECTED");
        assertTrue(phone.complete(f.context,f.current));assertEquals(1,f.terminalPages);assertEquals(0,f.successes);
        phone.evaluateTriggers(f.context);assertTrue(f.actions.contains(PHONE));assertEquals(0,f.reads);
    }
    static JsonNode status(int version,boolean verified,String enrollment){
        return org.keycloak.util.JsonSerialization.mapper.createObjectNode().put("enrollment_id",enrollment).put("version",version).put("state","PENDING_CONTACTS")
                .put("email_contact_version",version).put("phone_contact_version",1).put("email_verified",verified).put("phone_verified",false);
    }
    interface Call{Object apply(String name,Object[] args);}
    @SuppressWarnings("unchecked")static <T>T model(Class<T> type,Call call){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.apply(m.getName(),a));}
    static Object unexpected(String name){throw new AssertionError("Unexpected synthetic lifecycle operation: "+name);}
    static final class Fixture{
        final Map<String,String> notes=new HashMap<>();final Set<String> actions=new LinkedHashSet<>();
        String realm="realm",root="root",tab="tab",client="internal-client",user="subject",email="synthetic@example.test",username="+971501234567";
        int reads,successes,terminalPages;boolean unavailable,allowed=true;
        JsonNode current=status(1,true,"00000000-0000-0000-0000-000000000001");
        final AuthenticationSessionModel auth=model(AuthenticationSessionModel.class,(n,a)->switch(n){
            case "getAuthNote"->notes.get(a[0]);case "setAuthNote"->{notes.put((String)a[0],(String)a[1]);yield null;}case "removeAuthNote"->{notes.remove(a[0]);yield null;}
            case "addRequiredAction"->{actions.add((String)a[0]);yield null;}case "getTabId"->tab;
            case "getParentSession"->model(RootAuthenticationSessionModel.class,(name,args)->name.equals("getId")?root:unexpected(name));
            case "getClient"->model(ClientModel.class,(name,args)->name.equals("getId")?client:unexpected(name));default->unexpected(n);
        });
        final RequiredActionContext context=model(RequiredActionContext.class,(n,a)->switch(n){
            case "getAuthenticationSession"->auth;case "getRealm"->model(RealmModel.class,(name,args)->name.equals("getId")?realm:unexpected(name));
            case "getUser"->model(UserModel.class,(name,args)->switch(name){case "getId"->user;case "getEmail"->email;case "getUsername"->username;default->unexpected(name);});
            // Mirrors the pinned action manager removing EMAIL after SUCCESS, before evaluating again.
            case "success"->{successes++;actions.remove(EMAIL);yield null;}default->unexpected(n);
        });
        VerificationAction action(String purpose,String id){return new VerificationAction(purpose,id,()->true,ignored->allowed,c->terminalPages++,
                (c,enrollment)->{reads++;if(unavailable)throw new Bridge.Unavailable();return current;},()->"isolated-synthetic-routing-key-for-fixtures");}
    }
}
