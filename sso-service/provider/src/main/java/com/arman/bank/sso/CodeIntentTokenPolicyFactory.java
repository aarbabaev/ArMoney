package com.arman.bank.sso;

import org.keycloak.Config;
import org.keycloak.models.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.clientpolicy.executor.*;
import java.time.Clock;
import java.util.List;

/** Explicit realm client-policy attachment selects this executor; absence grants no banking proof. */
public final class CodeIntentTokenPolicyFactory implements ClientPolicyExecutorProviderFactory {
    @Override public ClientPolicyExecutorProvider create(KeycloakSession session){return new CodeIntentTokenPolicy(session,Clock.systemUTC());}
    @Override public String getId(){return CodeIntentTokenPolicy.ID;}
    @Override public String getHelpText(){return "Confirm the original consumed native authorization code before creating a banking proof.";}
    @Override public List<ProviderConfigProperty> getConfigProperties(){return List.of();}
    @Override public void init(Config.Scope scope){}
    @Override public void postInit(KeycloakSessionFactory factory){}
    @Override public void close(){}
}
