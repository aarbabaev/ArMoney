package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.models.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock("keycloak-profile")
final class CodeSessionObservationTest {
    private static boolean ownsProfile;
    @org.junit.jupiter.api.BeforeAll static void initializePinnedProfile() {
        ownsProfile=org.keycloak.common.Profile.getInstance()==null;
        if(ownsProfile) org.keycloak.common.Profile.defaults();
        assertNotNull(org.keycloak.common.Profile.getInstance());
        assertTrue(org.keycloak.common.Profile.isFeatureEnabled(org.keycloak.common.Profile.Feature.PERSISTENT_USER_SESSIONS));
    }
    @org.junit.jupiter.api.AfterAll static void releasePinnedProfile() {
        if(ownsProfile) org.keycloak.common.Profile.reset();
    }
    @Test void exactLiveBindingSerializesTwelveFieldsAndDisabledNegativeWithExplicitNullContacts() throws Exception {
        Fixture f=new Fixture();
        var result=CodeSessionObservation.observe(f.session(),f.binding,Clock.systemUTC());
        assertEquals(12,result.size()); assertTrue(result.path("enabled").booleanValue());
        assertTrue(result.path("user_session_active").booleanValue()); assertTrue(result.path("client_session_active").booleanValue());
        f.user.put("isEnabled",false);
        result=CodeSessionObservation.observe(f.session(),f.binding,Clock.systemUTC());
        var serialized=Bridge.strictJson(org.keycloak.util.JsonSerialization.writeValueAsBytes(result));
        assertFalse(serialized.path("enabled").booleanValue());
        assertTrue(serialized.has("email") && serialized.get("email").isNull());
        assertTrue(serialized.has("phone_number") && serialized.get("phone_number").isNull());
        assertEquals(Set.of("issuer","subject","client_id","user_session_id","username","email","phone_number","enabled",
                "user_session_active","client_session_active","observed_at","valid_until"),keys(serialized));
    }
    @Test void wrongIdentityMembershipOfflineTransientAndExpiredSessionsNeverReturnObservation() {
        for(String fault:List.of("subject","client","offline","transient","expired","foreignSession","loggingOut","loggedOut","foreignClient","foreignClientUserSession","clientExpired")) {
            Fixture f=new Fixture();
            switch(fault) {
                case "subject" -> f.user.put("getId","other");
                case "client" -> f.us.put("getAuthenticatedClientSessionByClient",(Call)args->null);
                case "offline" -> f.us.put("isOffline",true);
                case "transient" -> f.us.put("getPersistenceState",UserSessionModel.SessionPersistenceState.TRANSIENT);
                case "expired" -> f.us.put("getLastSessionRefresh",1);
                case "foreignSession" -> f.us.put("getId","other");
                case "loggingOut" -> f.us.put("getState",UserSessionModel.State.LOGGING_OUT);
                case "loggedOut" -> f.us.put("getState",UserSessionModel.State.LOGGED_OUT);
                case "foreignClient" -> f.cs.put("getClient",model(ClientModel.class,Map.of("getId","foreign")));
                case "foreignClientUserSession" -> f.cs.put("getUserSession",model(UserSessionModel.class,Map.of("getId","foreign")));
                case "clientExpired" -> f.cs.put("getTimestamp",1);
            }
            assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.observe(f.session(),f.binding,Clock.systemUTC()),fault);
        }
    }
    @Test void onlyAllowlistedPublicClientIsObservable() {
        Fixture publicClient=new Fixture();
        assertEquals("armoney-ios",CodeSessionObservation.observe(publicClient.session(),publicClient.binding,Clock.systemUTC()).path("client_id").textValue());
        Fixture confidential=new Fixture(); confidential.publicClient=false;
        assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.observe(confidential.session(),confidential.binding,Clock.systemUTC()));
        Fixture unknown=new Fixture();
        var other=new CodeSessionObservation.Binding(unknown.binding.issuer(),"subject","other-public-client","session");
        assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.observe(unknown.session(),other,Clock.systemUTC()));
    }
    @Test void contactsMustBeCanonicalOrExplicitNull() {
        Fixture canonical=new Fixture(); canonical.user.put("getEmail","person@example.test");
        assertEquals("person@example.test",CodeSessionObservation.observe(canonical.session(),canonical.binding,Clock.systemUTC()).path("email").textValue());
        for(String email:List.of("","not-an-email","Person@example.test","person@EXAMPLE.test"," person@example.test")) {
            Fixture invalid=new Fixture(); invalid.user.put("getEmail",email);
            assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.observe(invalid.session(),invalid.binding,Clock.systemUTC()));
        }
    }
    private static Set<String> keys(com.fasterxml.jackson.databind.JsonNode node) { Set<String> keys=new HashSet<>(); node.fieldNames().forEachRemaining(keys::add); return keys; }
    static final class Fixture {
        final Map<String,Object> user=new HashMap<>(),us=new HashMap<>(),cs=new HashMap<>();
        boolean publicClient=true;
        final CodeSessionObservation.Binding binding=new CodeSessionObservation.Binding("https://issuer.example/realms/armoney","subject","armoney-ios","session");
        KeycloakSession session() {
            int now=org.keycloak.common.util.Time.currentTime();
            var realmValues=new HashMap<String,Object>();
            realmValues.putAll(Map.of("getId","realm","getName","armoney","isRememberMe",false,
                    "getSsoSessionMaxLifespan",1000,"getSsoSessionIdleTimeout",1000,"getClientSessionMaxLifespan",0,"getClientSessionIdleTimeout",0));
            var client=model(ClientModel.class,Map.of("getId","internal","getClientId","armoney-ios","isEnabled",true,"isPublicClient",publicClient,"getAttribute",(Call)args->null));
            realmValues.put("getClientByClientId",(Call)args->client);
            var realm=model(RealmModel.class,realmPolicy(realmValues));
            user.putIfAbsent("getId","subject"); user.putIfAbsent("getUsername","legacy-user"); user.putIfAbsent("isEnabled",true); if(!user.containsKey("getEmail")) user.put("getEmail",null);
            var userModel=model(UserModel.class,user);
            us.putIfAbsent("getId","session"); us.put("getRealm",realm); us.put("getUser",userModel);
            us.putIfAbsent("isOffline",false); us.put("isRememberMe",false); us.put("getNote",(Call)args->null);
            us.putIfAbsent("getState",UserSessionModel.State.LOGGED_IN);
            us.putIfAbsent("getPersistenceState",UserSessionModel.SessionPersistenceState.PERSISTENT);
            us.put("getStarted",now); us.putIfAbsent("getLastSessionRefresh",now);
            var userSession=model(UserSessionModel.class,us);
            cs.putIfAbsent("getClient",client); cs.putIfAbsent("getUserSession",userSession); cs.putIfAbsent("getStarted",now); cs.putIfAbsent("getTimestamp",now);
            var clientSession=model(AuthenticatedClientSessionModel.class,cs);
            us.putIfAbsent("getAuthenticatedClientSessionByClient",(Call)args->clientSession);
            var hostname=model(org.keycloak.urls.HostnameProvider.class,Map.of("getBaseUri",java.net.URI.create("https://issuer.example/")));
            var hostSession=model(KeycloakSession.class,Map.of("getProvider",hostname));
            var delegate=model(jakarta.ws.rs.core.UriInfo.class,Map.of("getBaseUri",java.net.URI.create("https://issuer.example/"),"getRequestUri",java.net.URI.create("https://issuer.example/")));
            var uri=new KeycloakUriInfo(hostSession,org.keycloak.urls.UrlType.FRONTEND,delegate);
            var context=model(KeycloakContext.class,Map.of("getRealm",realm,"getUri",uri));
            var users=model(UserProvider.class,Map.of("getUserById",(Call)args->userModel));
            var sessions=model(UserSessionProvider.class,Map.of("getUserSession",(Call)args->userSession));
            return model(KeycloakSession.class,Map.of("getContext",context,"users",users,"sessions",sessions));
        }
    }
    @Test void allFourBoundsFloorAndOnlyMaximumMinusOneIsUnbounded() {
        Instant now=Instant.ofEpochMilli(100_900);
        assertEquals(Instant.ofEpochSecond(105),CodeSessionObservation.boundedDeadline(now,-1,200_000,-1,200_000));
        for(int i=0;i<4;i++) {
            long[] bounds={200_000,200_000,200_000,200_000}; bounds[i]=103_999;
            assertEquals(Instant.ofEpochSecond(103),CodeSessionObservation.boundedDeadline(now,bounds[0],bounds[1],bounds[2],bounds[3]));
        }
        assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.boundedDeadline(now,-2,200_000,-1,200_000));
        assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.boundedDeadline(now,-1,-1,-1,200_000));
        assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.boundedDeadline(now,-1,200_000,-1,-1));
        assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.boundedDeadline(now,-1,100_999,-1,200_000));
    }
    @Test void pinnedUtilitiesHonorClientOverrideAndRememberMe() {
        var realm=model(RealmModel.class,realmPolicy(new HashMap<>(Map.of("getSsoSessionMaxLifespan",100,"getSsoSessionIdleTimeout",50,
                "getSsoSessionMaxLifespanRememberMe",200,"getSsoSessionIdleTimeoutRememberMe",150,
                "getClientSessionMaxLifespan",0,"getClientSessionIdleTimeout",0))));
        var client=model(ClientModel.class,Map.of("getAttribute",(Call)args-> switch((String)args[0]) {
            case "client.session.max.lifespan" -> "3";
            case "client.session.idle.timeout" -> "4";
            default -> null;
        }));
        var us=model(UserSessionModel.class,Map.of("isRememberMe",false,"getStarted",100,"getLastSessionRefresh",100));
        var cs=model(AuthenticatedClientSessionModel.class,Map.of("getStarted",100,"getTimestamp",100));
        assertEquals(Instant.ofEpochSecond(103),CodeSessionObservation.deadline(Instant.ofEpochSecond(101),realm,client,us,cs));
        var noOverride=model(ClientModel.class,Map.of("getAttribute",(Call)args->null));
        var remembered=model(UserSessionModel.class,Map.of("isRememberMe",true,"getStarted",100,"getLastSessionRefresh",100));
        assertEquals(Instant.ofEpochSecond(204),CodeSessionObservation.deadline(Instant.ofEpochSecond(199),realm,noOverride,remembered,cs));
        assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.deadline(Instant.ofEpochSecond(151),realm,noOverride,us,cs));
    }
    @Test void eachActualPinnedUtilityCanBeTheMinimumAndIdleHasNoGrace() {
        for(String minimum:List.of("userMax","userIdle","clientMax","clientIdle","rememberMax","rememberIdle")) {
            Map<String,Object> policy=new HashMap<>(Map.of("getSsoSessionMaxLifespan",100,"getSsoSessionIdleTimeout",100,
                    "getSsoSessionMaxLifespanRememberMe",100,"getSsoSessionIdleTimeoutRememberMe",100,
                    "getClientSessionMaxLifespan",0,"getClientSessionIdleTimeout",0));
            Map<String,String> attributes=new HashMap<>();
            boolean remember=minimum.startsWith("remember");
            switch(minimum) {
                case "userMax" -> policy.put("getSsoSessionMaxLifespan",3);
                case "userIdle" -> policy.put("getSsoSessionIdleTimeout",3);
                case "clientMax" -> attributes.put("client.session.max.lifespan","3");
                case "clientIdle" -> attributes.put("client.session.idle.timeout","3");
                case "rememberMax" -> { policy.put("getSsoSessionMaxLifespan",2); policy.put("getSsoSessionMaxLifespanRememberMe",3); }
                case "rememberIdle" -> { policy.put("getSsoSessionIdleTimeout",2); policy.put("getSsoSessionIdleTimeoutRememberMe",3); }
            }
            var realm=model(RealmModel.class,realmPolicy(policy));
            var client=model(ClientModel.class,Map.of("getAttribute",(Call)args->attributes.get(args[0])));
            var us=model(UserSessionModel.class,Map.of("isRememberMe",remember,"getStarted",1000,"getLastSessionRefresh",1000));
            var cs=model(AuthenticatedClientSessionModel.class,Map.of("getStarted",1000,"getTimestamp",1000));
            assertEquals(Instant.ofEpochSecond(1003),CodeSessionObservation.deadline(Instant.ofEpochSecond(1001),realm,client,us,cs),minimum);
            assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.deadline(Instant.ofEpochSecond(1003),realm,client,us,cs),minimum);
            assertThrows(Bridge.Unavailable.class,()->CodeSessionObservation.deadline(Instant.ofEpochSecond(1004),realm,client,us,cs),minimum);
        }
    }
    @Test void exactRequestRejectsUnknownNullWrongTypeAndOldShape() throws Exception {
        String good="{\"issuer\":\"https://issuer/realms/armoney\",\"subject\":\"subject\",\"client_id\":\"client\",\"user_session_id\":\"session\"}";
        assertEquals("client",CodeSessionObservation.parse(Bridge.strictJson(good.getBytes())).clientId());
        for(String bad:List.of(good.replace("\"client_id\":\"client\",",""),good.replace("\"client\"","null"),
                good.replace("\"client\"","true"),good.replace("\"client\"","\"with space\""),good.replace("}",",\"extra\":1}")))
            assertThrows(IllegalArgumentException.class,()->CodeSessionObservation.parse(Bridge.strictJson(bad.getBytes())));
    }
    interface Call { Object apply(Object[] args); }
    private static Map<String,Object> realmPolicy(Map<String,Object> configured) {
        // RealmExpiration.fromRealm eagerly reads offline and remember-me policy,
        // even for an online, non-remember-me observation. Keep the proxy strict.
        var policy=new HashMap<String,Object>();
        policy.put("getOfflineSessionIdleTimeout",2_592_000);
        policy.put("isOfflineSessionMaxLifespanEnabled",true);
        policy.put("getOfflineSessionMaxLifespan",5_184_000);
        policy.put("getSsoSessionIdleTimeoutRememberMe",0);
        policy.put("getSsoSessionMaxLifespanRememberMe",0);
        policy.putAll(configured);
        return policy;
    }
    @SuppressWarnings("unchecked") static <T> T model(Class<T> type,Map<String,Object> values) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)-> {
            if(!values.containsKey(m.getName())) throw new AssertionError("Unexpected model operation: "+m.getName());
            Object value=values.get(m.getName()); return value instanceof Call call?call.apply(a):value;
        });
    }
}
