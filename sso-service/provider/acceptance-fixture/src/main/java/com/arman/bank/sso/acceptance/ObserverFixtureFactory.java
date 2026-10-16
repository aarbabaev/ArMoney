package com.arman.bank.sso.acceptance;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/** Never package this factory in the production provider or production Keycloak image. */
public final class ObserverFixtureFactory implements RealmResourceProviderFactory {
    @Override public String getId() { return "armoney-observer-fixture"; }
    @Override public RealmResourceProvider create(KeycloakSession session) {
        return new RealmResourceProvider() {
            @Override public Object getResource() { return new ObserverFixtureResource(session); }
            @Override public void close() { }
        };
    }
    @Override public void init(Config.Scope config) { }
    @Override public void postInit(KeycloakSessionFactory factory) { }
    @Override public void close() { }
}
