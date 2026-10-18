package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import jakarta.ws.rs.core.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit status operation with synthetic fresh authority, never a synthetic OTP receipt producer. */
class VerificationActionStatusTest {
    @Test void pendingToProjectedUsesFreshStatusWithoutEnrollmentOtpOrCommandNoteChanges(){
        var f=new Fixture();f.current=facts("PROJECTION_PENDING",1,1,true,true);
        f.action.rememberProjection(f.context,f.current);var before=new HashMap<>(f.base.notes);
        f.operation("status");f.action.processAction(f.context);
        assertEquals(1,f.reads);assertEquals(true,f.attributes.get("verificationPending"));assertEquals(0,f.base.terminalPages);assertEquals(before,f.base.notes);
        f.current=facts("PROJECTED",1,1,true,true);f.action.processAction(f.context);
        assertEquals(2,f.reads);assertEquals(1,f.base.terminalPages);assertEquals(0,f.base.successes);assertEquals(before,f.base.notes);
    }
    @Test void missingUnknownEmptyDuplicateOperationsCannotReachEnrollmentOrOtp(){
        for(List<String> values:List.of(List.<String>of(),List.of(""),List.of("unknown"),List.of("status","status"),List.of("verify","send"))){
            var f=new Fixture();if(!values.isEmpty())f.parameters.put("operation",values);
            f.action.processAction(f.context);assertEquals(0,f.reads);assertEquals(0,f.base.terminalPages);assertTrue(f.base.notes.isEmpty());assertFalse(Boolean.TRUE.equals(f.attributes.get("verificationPending")));
        }
    }
    @Test void statusWithoutPhoneConsumedMarkerOrWithChangedVersionContactOrNegativeAuthorityDenies(){
        for(String change:List.of("missing","email-version","phone-version","email","phone","revoked-email","revoked-phone","suspended","blocked","pending-contacts","binding","malformed","email-purpose")){
            var f=new Fixture();f.current=facts("PROJECTION_PENDING",1,1,true,true);f.action.rememberProjection(f.context,f.current);
            switch(change){
                case "missing"->f.base.notes.clear();case "email-version"->f.current=facts("PROJECTION_PENDING",2,1,true,true);case "phone-version"->f.current=facts("PROJECTION_PENDING",1,2,true,true);
                case "email"->f.base.email="changed@example.test";case "phone"->f.base.username="+971521234567";
                case "revoked-email"->f.current=facts("PROJECTION_PENDING",1,1,false,true);case "revoked-phone"->f.current=facts("PROJECTION_PENDING",1,1,true,false);
                case "suspended"->f.current=facts("SUSPENDED",1,1,true,true);case "blocked"->f.current=facts("BLOCKED_MIGRATION",1,1,true,true);case "pending-contacts"->f.current=facts("PENDING_CONTACTS",1,1,true,true);
                case "binding"->f.base.tab="other-tab";case "malformed"->f.base.notes.put(VerificationActionLifecycleTest.PHONE+".projection-pending","{}");
                case "email-purpose"->f.action=f.action("REGISTRATION_EMAIL",VerificationActionLifecycleTest.EMAIL);
            }
            var before=new HashMap<>(f.base.notes);f.operation("status");f.action.processAction(f.context);
            assertEquals(0,f.base.terminalPages,change);assertEquals(0,f.base.successes,change);assertEquals(before,f.base.notes,change);assertFalse(Boolean.TRUE.equals(f.attributes.get("verificationPending")),change);
        }
    }
    @Test void unverifiedOrNonpendingStatusCannotMintPendingRoutingMarker(){
        for(JsonNode status:List.of(facts("PROJECTION_PENDING",1,1,false,true),facts("PROJECTION_PENDING",1,1,true,false),facts("SUSPENDED",1,1,true,true),facts("PROJECTED",1,1,true,true))){
            var f=new Fixture();assertThrows(Bridge.Unavailable.class,()->f.action.rememberProjection(f.context,status));assertTrue(f.base.notes.isEmpty());
        }
    }
    @Test void unavailableFreshStatusKeepsOnlyPendingRetryThenRequiresFreshProjectedWithoutNoteMutation(){
        var f=new Fixture();f.current=facts("PROJECTION_PENDING",1,1,true,true);f.action.rememberProjection(f.context,f.current);
        var original=new HashMap<>(f.base.notes);f.operation("status");f.unavailable=true;f.action.processAction(f.context);
        assertEquals(1,f.reads);assertEquals(true,f.attributes.get("verificationPending"));assertEquals(0,f.base.terminalPages);assertEquals(original,f.base.notes);
        f.unavailable=false;f.current=facts("PROJECTED",1,1,true,true);f.action.processAction(f.context);
        assertEquals(2,f.reads);assertEquals(1,f.base.terminalPages);assertEquals(0,f.base.successes);assertEquals(original,f.base.notes);
        // A malformed original marker never receives the transient retry presentation.
        f.base.notes.put(VerificationActionLifecycleTest.PHONE+".projection-pending","{}");f.unavailable=true;f.action.processAction(f.context);
        assertEquals(2,f.reads);assertEquals(false,f.attributes.get("verificationPending"));
    }
    static JsonNode facts(String state,int email,int phone,boolean emailVerified,boolean phoneVerified){return org.keycloak.util.JsonSerialization.mapper.createObjectNode()
            .put("enrollment_id","00000000-0000-0000-0000-000000000001").put("version",1).put("state",state).put("email_contact_version",email).put("phone_contact_version",phone).put("email_verified",emailVerified).put("phone_verified",phoneVerified);}
    static final class Fixture{
        final VerificationActionLifecycleTest.Fixture base=new VerificationActionLifecycleTest.Fixture();
        final MultivaluedMap<String,String> parameters=new MultivaluedHashMap<>();final Map<String,Object> attributes=new HashMap<>();
        JsonNode current;int reads;boolean unavailable;
        final LoginFormsProvider form=VerificationActionLifecycleTest.model(LoginFormsProvider.class,(n,a)->switch(n){case "setAttribute"->{attributes.put((String)a[0],a[1]);yield form();}case "createForm"->Response.ok("synthetic verification",MediaType.TEXT_HTML_TYPE).build();default->VerificationActionLifecycleTest.unexpected(n);});
        LoginFormsProvider form(){return form;}
        final HttpRequest request=VerificationActionLifecycleTest.model(HttpRequest.class,(n,a)->n.equals("getDecodedFormParameters")?parameters:VerificationActionLifecycleTest.unexpected(n));
        final RequiredActionContext context=(RequiredActionContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RequiredActionContext.class},(p,m,a)->switch(m.getName()){
            case "getHttpRequest"->request;case "form"->form;case "challenge"->{((Response)a[0]).close();yield null;}default->m.invoke(base.context,a);
        });
        VerificationAction action=action("REGISTRATION_PHONE",VerificationActionLifecycleTest.PHONE);
        VerificationAction action(String purpose,String id){return new VerificationAction(purpose,id,()->true,ignored->true,c->base.terminalPages++,
                (c,enrollment)->{reads++;if(unavailable)throw new Bridge.Unavailable();return current;},()->"isolated-synthetic-routing-key-for-fixtures");}
        void operation(String operation){parameters.putSingle("operation",operation);}
    }
}
