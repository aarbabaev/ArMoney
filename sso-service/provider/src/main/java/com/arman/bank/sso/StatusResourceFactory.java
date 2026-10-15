package com.arman.bank.sso;

import org.keycloak.Config;
import org.keycloak.models.*;
import org.keycloak.services.resource.*;

public final class StatusResourceFactory implements RealmResourceProviderFactory {
    @Override public RealmResourceProvider create(KeycloakSession session) {
        return new RealmResourceProvider() {
            @Override public Object getResource() { return new StatusResource(session); }
            @Override public void close() { }
        };
    }
    @Override public String getId() { return "armoney-status"; }
    @Override public void init(Config.Scope config) { }
    @Override public void postInit(KeycloakSessionFactory factory) { }
    @Override public void close() { }
}
