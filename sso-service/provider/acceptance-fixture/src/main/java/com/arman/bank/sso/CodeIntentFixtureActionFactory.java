package com.arman.bank.sso;

import org.keycloak.Config;
import org.keycloak.authentication.*;
import org.keycloak.models.*;

public final class CodeIntentFixtureActionFactory implements RequiredActionFactory {
    @Override public String getId(){return "armoney-code-intent-fixture-continuation";}
    @Override public String getDisplayText(){return "Disposable code-intent continuation";}
    @Override public RequiredActionProvider create(KeycloakSession session){return new CodeIntentFixtureAction();}
    @Override public void init(Config.Scope scope){}
    @Override public void postInit(KeycloakSessionFactory factory){}
    @Override public void close(){}
}
