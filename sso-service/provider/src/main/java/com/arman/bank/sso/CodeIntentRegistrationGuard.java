package com.arman.bank.sso;

import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.sessions.AuthenticationSessionModel;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A creation marker is registration scope only; it never establishes a password event. */
final class CodeIntentRegistrationGuard {
    private static final String NOTE="armoney.registration-creation";
    static boolean selected(String realm,String issuer,String client){
        if(!Bridge.enabled()||!"armoney".equals(realm)||!Objects.equals(System.getenv("SSO_ISSUER"),issuer)
                ||!Set.of("armoney-android","armoney-ios","armoney-web").contains(client))return false;
        String clients=System.getenv("SSO_VERIFICATION_CLIENT_IDS");return clients!=null&&Arrays.asList(clients.split(",",-1)).contains(client);
    }
    static void mark(String realmId,AuthenticationSessionModel auth,String userId){auth.setAuthNote(NOTE,value(realmId,auth,userId));}
    static boolean permits(RequiredActionContext context){
        try{
            String issuer=org.keycloak.services.Urls.realmIssuer(context.getUriInfo().getBaseUri(),context.getRealm().getName());
            if(!selected(context.getRealm().getName(),issuer,context.getAuthenticationSession().getClient().getClientId()))return false;
            return matches(context.getRealm().getId(),context.getAuthenticationSession(),context.getUser().getId());
        }catch(RuntimeException unavailable){return false;}
    }
    static boolean matches(String realmId,AuthenticationSessionModel auth,String userId){
        return matches(Bridge.required("ARMONEY_PROVIDER_BINDING_KEY"),realmId,auth,userId);
    }
    static boolean matches(String key,String realmId,AuthenticationSessionModel auth,String userId){
        String actual=auth.getAuthNote(NOTE);if(actual==null)return false;
        try{return MessageDigest.isEqual(SessionBinding.decode(actual),SessionBinding.decode(value(key,realmId,auth,userId)));}
        catch(RuntimeException malformed){return false;}
    }
    private static String value(String realmId,AuthenticationSessionModel auth,String userId){
        return value(Bridge.required("ARMONEY_PROVIDER_BINDING_KEY"),realmId,auth,userId);
    }
    static String value(String key,String realmId,AuthenticationSessionModel auth,String userId){return SessionBinding.derive(key,"registration-creation",realmId,auth.getParentSession().getId(),auth.getTabId(),auth.getClient().getId(),userId);}
    private CodeIntentRegistrationGuard(){}
}
