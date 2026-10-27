package com.arman.bank.sso;

import org.keycloak.Config;
import org.keycloak.email.*;
import org.keycloak.models.*;

/** Explicit selection only; stock remains the default when this provider is packaged. */
public final class StrictMailtrapEmailSenderFactory implements EmailSenderProviderFactory {
    @Override public String getId(){return "armoney-strict-mailtrap";}
    @Override public int order(){return 0;}
    @Override public EmailSenderProvider create(KeycloakSession session){return new StrictMailtrapEmailSender(session);}
    @Override public void init(Config.Scope config){}
    @Override public void postInit(KeycloakSessionFactory factory){}
    @Override public void close(){}
}
