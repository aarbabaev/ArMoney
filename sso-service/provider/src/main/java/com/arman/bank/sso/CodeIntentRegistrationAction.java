package com.arman.bank.sso;

import org.keycloak.authentication.FormContext;
import org.keycloak.authentication.forms.RegistrationUserCreation;
import org.keycloak.services.Urls;

/** Delegates actual Keycloak user creation; no credential success is inferred. */
final class CodeIntentRegistrationAction extends RegistrationUserCreation {
    @Override public void success(FormContext context){
        var original=context.getAuthenticationSession();
        boolean wasNew=context.getUser()==null;
        super.success(context);
        String issuer=Urls.realmIssuer(context.getUriInfo().getBaseUri(),context.getRealm().getName());
        if(!CodeIntentRegistrationGuard.selected(context.getRealm().getName(),issuer,original.getClient().getClientId()))return;
        var user=context.getUser();
        if(!wasNew||user==null||context.getAuthenticationSession()!=original
                ||context.getSession().users().getUserById(context.getRealm(),user.getId())==null)throw new IllegalStateException("Registration creation unavailable");
        CodeIntentRegistrationGuard.mark(context.getRealm().getId(),original,user.getId());
        // Deliberately no PasswordProofFactory notes: after verification, require a fresh ordinary login.
    }
}
