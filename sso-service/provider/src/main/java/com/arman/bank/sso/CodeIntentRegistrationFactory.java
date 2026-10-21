package com.arman.bank.sso;

import org.keycloak.authentication.FormAction;
import org.keycloak.authentication.forms.RegistrationUserCreation;
import org.keycloak.models.KeycloakSession;

/** Replaces only the user-creation execution in an explicitly selected registration flow. */
public final class CodeIntentRegistrationFactory extends RegistrationUserCreation {
    @Override public String getId(){return "armoney-registration-creation";}
    @Override public String getDisplayType(){return "ArMoney registration creation";}
    @Override public FormAction create(KeycloakSession session){return new CodeIntentRegistrationAction();}
}
