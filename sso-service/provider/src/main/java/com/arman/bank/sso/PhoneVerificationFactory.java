package com.arman.bank.sso;

import org.keycloak.Config;
import org.keycloak.authentication.*;
import org.keycloak.models.*;

public final class PhoneVerificationFactory implements RequiredActionFactory {
    @Override public String getId() { return "armoney-verify-phone-code"; }
    @Override public String getDisplayText() { return "ArMoney phone code"; }
    @Override public RequiredActionProvider create(KeycloakSession session) { return new VerificationAction("REGISTRATION_PHONE", getId()); }
    @Override public void init(Config.Scope config) { if (Bridge.enabled()) { Bridge.configured(); SessionBinding.derive(Bridge.required("ARMONEY_PROVIDER_BINDING_KEY"), "startup-check"); } }
    @Override public void postInit(KeycloakSessionFactory factory) { }
    @Override public void close() { }
}
