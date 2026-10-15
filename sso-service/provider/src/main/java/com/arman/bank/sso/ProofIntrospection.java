package com.arman.bank.sso;

import org.keycloak.models.*;
import org.keycloak.protocol.oidc.AccessTokenIntrospectionProvider;
import org.keycloak.representations.AccessToken;

/** Unregistered: never derive historical proof from present session state. */
final class ProofIntrospection extends AccessTokenIntrospectionProvider<AccessToken> {
    private final boolean enabled;
    ProofIntrospection(KeycloakSession session, boolean enabled) { super(session); this.enabled = enabled; }
    @Override public AccessToken transformAccessToken(AccessToken original, UserSessionModel user) {
        AccessToken transformed = super.transformAccessToken(original,user);
        if (enabled && transformed != null) ProofClaims.preserve(original.getOtherClaims(),transformed.getOtherClaims());
        return transformed;
    }
}
