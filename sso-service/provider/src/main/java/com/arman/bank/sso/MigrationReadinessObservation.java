package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.keycloak.models.*;
import org.keycloak.util.JsonSerialization;
import java.time.Clock;
import java.util.*;

/** Read-only provider verification evidence. Never Auth contact or migration authority. */
final class MigrationReadinessObservation {
    record Request(CodeSessionObservation.Binding binding,CodeIntentProviderConfiguration.Purpose purpose,String redirect) {
        @Override public String toString(){return "MigrationReadinessRequest[redacted]";}
    }
    static Request parse(JsonNode input){
        if(input==null||!input.isObject())throw new IllegalArgumentException();
        Set<String> fields=new HashSet<>();input.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("protocol_version","purpose","original_redirect_uri","issuer","subject","client_id","user_session_id"))
                ||!input.path("protocol_version").isIntegralNumber()||!input.path("protocol_version").canConvertToInt()||input.path("protocol_version").intValue()!=2)throw new IllegalArgumentException();
        var purpose=CodeIntentProviderConfiguration.Purpose.valueOf(text(input,"purpose",32));
        if(!migration(purpose))throw new IllegalArgumentException();
        ObjectNode tuple=JsonSerialization.mapper.createObjectNode();
        for(String field:List.of("issuer","subject","client_id","user_session_id"))tuple.set(field,input.get(field));
        return new Request(CodeSessionObservation.parse(tuple),purpose,text(input,"original_redirect_uri",2048));
    }
    private static String text(JsonNode input,String name,int maximum){
        JsonNode value=input.get(name);
        if(value==null||!value.isTextual()||value.textValue().isEmpty()||value.textValue().length()>maximum
                ||value.textValue().chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)))throw new IllegalArgumentException();
        return value.textValue();
    }
    static boolean migration(CodeIntentProviderConfiguration.Purpose purpose){
        return purpose==CodeIntentProviderConfiguration.Purpose.MIGRATION_ACCOUNT||purpose==CodeIntentProviderConfiguration.Purpose.MIGRATION_MANAGED;
    }
    static void requireClient(CodeIntentProviderConfiguration configuration,String issuer,ClientModel client,String redirect,CodeIntentProviderConfiguration.Purpose purpose){
        if(configuration==null||!configuration.enabled()||!migration(purpose)||client==null||!client.isEnabled()||!client.isPublicClient()
                ||!client.isStandardFlowEnabled()||client.isImplicitFlowEnabled()||client.isDirectAccessGrantsEnabled()||client.isServiceAccountsEnabled()
                ||!"S256".equals(client.getAttribute("pkce.code.challenge.method"))||redirect==null||client.getRedirectUris()==null
                ||!client.getRedirectUris().contains(redirect)||configuration.purpose(issuer,client.getClientId(),redirect)!=purpose)throw new Bridge.Unavailable();
    }
    static ObjectNode observe(KeycloakSession session,Request request,Clock clock,CodeIntentProviderConfiguration configuration){
        if(configuration==null||!configuration.enabled()
                ||configuration.purpose(request.binding().issuer(),request.binding().clientId(),request.redirect())!=request.purpose())throw new Bridge.Unavailable();
        var snapshot=CodeSessionObservation.snapshot(session,request.binding(),clock,configuration);
        if(!Objects.equals(request.binding().subject(),snapshot.user().getId()))throw new Bridge.Unavailable();
        String actual=snapshot.clientSession().getRedirectUri();
        if(!Objects.equals(request.redirect(),actual))throw new Bridge.Unavailable();
        requireClient(configuration,request.binding().issuer(),snapshot.client(),actual,request.purpose());
        // One resolved user; refuse a contact mutation observed while assembling this response.
        String email=snapshot.facts().path("email").textValue(),phone=snapshot.facts().path("phone_number").textValue();
        if(!EnrollmentObservation.canonicalEmail(email)||!EnrollmentObservation.canonicalPhone(phone))throw new Bridge.Unavailable();
        boolean verified=snapshot.user().isEmailVerified();
        if(!Objects.equals(email,snapshot.user().getEmail())||!Objects.equals(phone,snapshot.user().getUsername()))throw new Bridge.Unavailable();
        ObjectNode out=JsonSerialization.mapper.createObjectNode();
        out.put("protocol_version",2);out.put("purpose",request.purpose().name());out.put("original_redirect_uri",actual);
        out.set("facts",snapshot.facts());out.put("provider_email_verified",verified);return out;
    }
    private MigrationReadinessObservation() { }
}
