package com.arman.bank.sso;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.LoginProtocol;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.OIDCLoginProtocolFactory;
import java.time.Clock;

/** Sole lifecycle owner of the production code-intent transport. OFF allocates no client. */
public final class CodeIntentLoginProtocolFactory extends OIDCLoginProtocolFactory {
    private CodeIntentProviderConfiguration configuration;
    private CodeIntentResources resources;
    private boolean closed;
    public CodeIntentLoginProtocolFactory(){}
    /** Package-private synthetic collaborator seam; the supplied resources transfer ownership. */
    CodeIntentLoginProtocolFactory(CodeIntentProviderConfiguration configuration,CodeIntentResources resources){this.configuration=java.util.Objects.requireNonNull(configuration);this.resources=resources;}
    @Override public void init(Config.Scope scope){super.init(scope);configuration=CodeIntentProviderConfiguration.from(System.getenv());}
    @Override public LoginProtocol create(KeycloakSession session){
        OIDCLoginProtocol ordinary=(OIDCLoginProtocol)super.create(session);
        return new CodeIntentLoginProtocol(ordinary.getConfig(),this,Clock.systemUTC()).setSession(session);
    }
    synchronized CodeIntentProviderConfiguration configuration(){if(closed||configuration==null)throw new IllegalStateException("Code-intent provider unavailable");return configuration;}
    synchronized CodeIntentResources resources(){
        var settings=configuration();if(!settings.enabled())throw new IllegalStateException("Code-intent provider unavailable");
        if(resources==null)resources=CodeIntentResources.open(settings.environment(),settings.keyMaterial()).orElseThrow(()->new IllegalStateException("Code-intent provider unavailable"));
        return resources;
    }
    static CodeIntentLoginProtocolFactory owner(KeycloakSession session){
        var factory=session.getKeycloakSessionFactory().getProviderFactory(LoginProtocol.class,"openid-connect");
        if(!(factory instanceof CodeIntentLoginProtocolFactory actual))throw new IllegalStateException("Code-intent provider unavailable");return actual;
    }
    @Override public int order(){return 1000;}
    @Override public synchronized void close(){
        if(closed)return;closed=true;
        try{if(resources!=null)resources.close();}finally{super.close();}
    }
}
