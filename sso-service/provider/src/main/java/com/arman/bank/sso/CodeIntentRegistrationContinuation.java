package com.arman.bank.sso;

import org.keycloak.authentication.AuthenticationProcessor;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.protocol.AuthorizationEndpointBase;
import org.keycloak.services.Urls;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Stops at a live required-action challenge, before session attachment/code generation. */
final class CodeIntentRegistrationContinuation {
    static void show(RequiredActionContext context){show(context,Bridge.required("ARMONEY_PROVIDER_BINDING_KEY"),CodeIntentRegistrationGuard::permits);}
    /** Pure collaborator seam for synthetic model tests; production calls the genuine creation guard. */
    static void show(RequiredActionContext context,String key,java.util.function.Predicate<RequiredActionContext> creation){
        if(!creation.test(context))throw new Bridge.Unavailable();
        var auth=context.getAuthenticationSession();var realm=context.getRealm();var user=context.getUser();
        if(user.getRequiredActionsStream().anyMatch(CodeIntentRegistrationContinuation::customAction))throw new Bridge.Unavailable();
        String issuer=Urls.realmIssuer(context.getUriInfo().getBaseUri(),realm.getName());
        String digest=SessionBinding.derive(key,realm.getId(),auth.getParentSession().getId(),auth.getTabId(),auth.getClient().getId());
        var binding=new EnrollmentObservation.Binding(issuer,user.getId(),auth.getClient().getClientId(),auth.getParentSession().getId(),auth.getTabId(),digest);
        var observation=EnrollmentObservation.observe(context.getSession(),binding,key);
        if(!Boolean.TRUE.equals(observation.get("enabled")))throw new Bridge.Unavailable();
        var root=context.getSession().authenticationSessions().getRootAuthenticationSession(realm,binding.rootId());
        var cached=root==null?null:root.getAuthenticationSession(auth.getClient(),binding.tabId());
        if(cached==null||!CodeIntentRegistrationGuard.matches(key,realm.getId(),cached,user.getId()))throw new Bridge.Unavailable();
        // Explicit user choice restarts AUTHENTICATE, never the original registration route.
        auth.removeClientNote(AuthorizationEndpointBase.APP_INITIATED_FLOW);
        var restart=Urls.realmLoginRestartPage(context.getUriInfo().getBaseUri(),realm.getName(),auth.getClient().getClientId(),auth.getTabId(),AuthenticationProcessor.getClientData(context.getSession(),auth),true);
        var cache=new CacheControl();cache.setNoStore(true);
        try(var info=context.getSession().getProvider(LoginFormsProvider.class).setAuthenticationSession(auth)
                .setInfo("Registration verified. Sign in to continue.").setAttribute("actionUri",restart.toString()).createInfoPage()){
            if(info.getMediaType()==null||!info.getMediaType().isCompatible(MediaType.TEXT_HTML_TYPE))throw new Bridge.Unavailable();
            context.challenge(Response.fromResponse(info).cacheControl(cache).build());
        }
        // No context.success(): CHALLENGE prevents attachSession from deleting the original tab.
    }
    private static boolean customAction(String action){return "armoney-verify-email-code".equals(action)||"armoney-verify-phone-code".equals(action);}
    private CodeIntentRegistrationContinuation(){}
}
