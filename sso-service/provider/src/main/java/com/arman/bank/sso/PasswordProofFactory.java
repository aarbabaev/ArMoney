package com.arman.bank.sso;

import jakarta.ws.rs.core.MultivaluedMap;
import org.keycloak.authentication.*;
import org.keycloak.authentication.authenticators.browser.*;
import org.keycloak.models.KeycloakSession;
import java.util.UUID;

/** Records fresh password proof only. Does not expose a notice-eligible token claim. */
public final class PasswordProofFactory extends UsernamePasswordFormFactory {
    public static final String NOTE = "armoney.password-proof-event";
    public static final String TIME_NOTE = "armoney.password-proof-time";
    @Override public String getId() { return "armoney-password-proof"; }
    @Override public String getDisplayType() { return "ArMoney password proof"; }
    @Override public Authenticator create(KeycloakSession session) {
        // Deliberately use password-only parent: do not label passkey or cookie authentication password proof.
        return new UsernamePasswordForm() {
            @Override protected boolean validateForm(AuthenticationFlowContext context, MultivaluedMap<String, String> data) {
                if (!super.validateForm(context, data)) {
                    context.getAuthenticationSession().removeAuthNote(NOTE);
                    context.getAuthenticationSession().removeAuthNote(TIME_NOTE); return false;
                }
                if (context.getAuthenticationSession().getAuthNote(NOTE) == null) {
                    context.getAuthenticationSession().setAuthNote(NOTE, UUID.randomUUID().toString());
                    context.getAuthenticationSession().setAuthNote(TIME_NOTE, java.time.Instant.ofEpochSecond(java.time.Instant.now().getEpochSecond()).toString());
                }
                return true;
            }
        };
    }
}
