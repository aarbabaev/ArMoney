package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.keycloak.models.*;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.models.utils.SessionExpirationUtils;
import org.keycloak.services.Urls;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.util.JsonSerialization;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Read-only observations; neither a provider lease nor banking authority. */
final class CodeSessionObservation {
    private static final Set<String> CLIENTS=Set.of("armoney-ios","armoney-android","armoney-web");
    record Binding(String issuer,String subject,String clientId,String sessionId) { }
    static Binding parse(JsonNode input) {
        if(input==null || !input.isObject()) throw new IllegalArgumentException();
        Set<String> keys=new HashSet<>(); input.fieldNames().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("issuer","subject","client_id","user_session_id"))) throw new IllegalArgumentException();
        return new Binding(text(input,"issuer",512),text(input,"subject",255),text(input,"client_id",255),text(input,"user_session_id",255));
    }
    private static String text(JsonNode input,String name,int maximum) {
        JsonNode value=input.get(name);
        if(value==null || !value.isTextual()) throw new IllegalArgumentException();
        String text=value.textValue();
        if(text.isEmpty() || text.length()>maximum || text.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))) throw new IllegalArgumentException();
        return text;
    }
    static ObjectNode observe(KeycloakSession session,Binding binding,Clock clock) {
        Instant captured=clock.instant();
        CodeIntentProviderConfiguration accountConfiguration=null;
        if(CodeIntentConfiguration.MIGRATION_ACCOUNT_CLIENT.equals(binding.clientId())){
            try{accountConfiguration=CodeIntentLoginProtocolFactory.owner(session).configuration();}catch(RuntimeException unavailable){throw new Bridge.Unavailable();}
        }
        return observeCaptured(session,binding,captured,accountConfiguration);
    }
    /** Validated immutable configuration seam; never changes the original three-client observations. */
    static ObjectNode observe(KeycloakSession session,Binding binding,Clock clock,CodeIntentProviderConfiguration accountConfiguration) {
        Instant captured=clock.instant(); // Capture before any session validity/model checks.
        return observeCaptured(session,binding,captured,accountConfiguration);
    }
    private static ObjectNode observeCaptured(KeycloakSession session,Binding binding,Instant captured,CodeIntentProviderConfiguration accountConfiguration){
        RealmModel realm=session.getContext().getRealm();
        String issuer=Urls.realmIssuer(session.getContext().getUri().getBaseUri(),realm.getName());
        boolean account=CodeIntentConfiguration.MIGRATION_ACCOUNT_CLIENT.equals(binding.clientId());
        if(!issuer.equals(binding.issuer()) || !(CLIENTS.contains(binding.clientId())||account)) throw new Bridge.Unavailable();
        ClientModel client=realm.getClientByClientId(binding.clientId());
        UserModel user=session.users().getUserById(realm,binding.subject());
        UserSessionModel us=session.sessions().getUserSession(realm,binding.sessionId());
        if(client==null || !client.isEnabled() || !client.isPublicClient() || !binding.clientId().equals(client.getClientId()) || user==null || us==null
                || !realm.getId().equals(us.getRealm().getId()) || !binding.sessionId().equals(us.getId())
                || us.getUser()==null || !binding.subject().equals(us.getUser().getId())
                || us.isOffline() || us.getState()!=UserSessionModel.State.LOGGED_IN
                || !persistent(session,realm,us,binding)) throw new Bridge.Unavailable();
        AuthenticatedClientSessionModel cs=us.getAuthenticatedClientSessionByClient(client.getId());
        if(cs==null || cs.getClient()==null || !client.getId().equals(cs.getClient().getId())
                || cs.getUserSession()==null || !us.getId().equals(cs.getUserSession().getId())
                || !AuthenticationManager.isSessionValid(realm,us) || !AuthenticationManager.isClientSessionValid(realm,client,us,cs)) throw new Bridge.Unavailable();
        if(account)requireAccountClient(client,cs,issuer,accountConfiguration);
        Instant until=deadline(captured,realm,client,us,cs);
        String username=user.getUsername();
        if(username==null || username.isBlank() || username.length()>255 || username.chars().anyMatch(Character::isISOControl)) throw new Bridge.Unavailable();
        ObjectNode out=JsonSerialization.mapper.createObjectNode();
        out.put("issuer",issuer); out.put("subject",binding.subject()); out.put("client_id",binding.clientId()); out.put("user_session_id",binding.sessionId());
        out.put("username",username);
        String email=user.getEmail();
        if(email!=null && !EnrollmentObservation.canonicalEmail(email)) throw new Bridge.Unavailable();
        if(email==null) out.putNull("email"); else out.put("email",email);
        if(username.matches("\\+9715[024568][0-9]{7}")) out.put("phone_number",username); else out.putNull("phone_number");
        out.put("enabled",user.isEnabled()); out.put("user_session_active",true); out.put("client_session_active",true);
        out.put("observed_at",Instant.ofEpochSecond(captured.getEpochSecond()).toString()); out.put("valid_until",until.toString());
        return out;
    }
    private static void requireAccountClient(ClientModel client,AuthenticatedClientSessionModel session,String issuer,CodeIntentProviderConfiguration configuration){
        try{
            String redirect=session.getRedirectUri();
            if(configuration==null||!configuration.enabled()||configuration.purpose(issuer,client.getClientId(),redirect)!=CodeIntentProviderConfiguration.Purpose.MIGRATION_ACCOUNT
                    ||redirect==null||!Set.of(redirect).equals(client.getRedirectUris())||!client.isStandardFlowEnabled()||client.isImplicitFlowEnabled()
                    ||client.isDirectAccessGrantsEnabled()||client.isServiceAccountsEnabled()||!"S256".equals(client.getAttribute("pkce.code.challenge.method")))throw new Bridge.Unavailable();
        }catch(RuntimeException unavailable){throw new Bridge.Unavailable();}
    }
    private static boolean persistent(KeycloakSession session,RealmModel realm,UserSessionModel live,Binding binding) {
        var state=live.getPersistenceState();
        if(state==UserSessionModel.SessionPersistenceState.TRANSIENT) return false;
        if(state==UserSessionModel.SessionPersistenceState.PERSISTENT) return true;
        // Pinned persistent-provider reload adapters leave this local field null.
        // Null alone is not provenance: require the exact durable online tuple.
        try {
            var persister=session.getProvider(UserSessionPersisterProvider.class);
            if(persister==null) return false;
            var durable=persister.loadUserSession(realm,binding.sessionId(),false);
            return durable!=null && durable.getRealm()!=null && realm.getId().equals(durable.getRealm().getId())
                    && binding.sessionId().equals(durable.getId()) && durable.getUser()!=null
                    && binding.subject().equals(durable.getUser().getId()) && !durable.isOffline()
                    && durable.getState()==UserSessionModel.State.LOGGED_IN;
        } catch(RuntimeException unavailable) { return false; }
    }
    static Instant deadline(Instant captured,RealmModel realm,ClientModel client,UserSessionModel us,AuthenticatedClientSessionModel cs) {
        boolean remember=us.isRememberMe();
        long userStart=millis(us.getStarted()), userRefresh=millis(us.getLastSessionRefresh());
        long clientStart=millis(cs.getStarted()), clientRefresh=millis(cs.getTimestamp());
        long maximum=SessionExpirationUtils.calculateUserSessionMaxLifespanTimestamp(false,remember,userStart,realm);
        long idle=SessionExpirationUtils.calculateUserSessionIdleTimestamp(false,remember,userRefresh,realm);
        long clientMaximum=SessionExpirationUtils.calculateClientSessionMaxLifespanTimestamp(false,remember,clientStart,userStart,realm,client);
        long clientIdle=SessionExpirationUtils.calculateClientSessionIdleTimestamp(false,remember,clientRefresh,realm,client);
        return boundedDeadline(captured,maximum,idle,clientMaximum,clientIdle);
    }
    static Instant boundedDeadline(Instant captured,long maximum,long idle,long clientMaximum,long clientIdle) {
        long end=captured.plusSeconds(5).toEpochMilli();
        for(long value:new long[]{maximum,clientMaximum}) {
            if(value == -1) continue;
            if(value<=0) throw new Bridge.Unavailable();
            end=Math.min(end,value);
        }
        if(idle<=0 || clientIdle<=0) throw new Bridge.Unavailable();
        end=Math.min(end,Math.min(idle,clientIdle));
        Instant result=Instant.ofEpochSecond(Math.floorDiv(end,1000));
        if(!result.isAfter(captured)) throw new Bridge.Unavailable();
        return result;
    }
    private static long millis(int seconds) { if(seconds<=0) throw new Bridge.Unavailable(); return Math.multiplyExact((long)seconds,1000); }
    private CodeSessionObservation() { }
}
