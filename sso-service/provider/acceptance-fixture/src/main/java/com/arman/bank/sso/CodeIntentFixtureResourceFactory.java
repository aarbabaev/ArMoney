package com.arman.bank.sso;

import org.keycloak.Config;
import org.keycloak.models.*;
import org.keycloak.services.resource.*;
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager;

/** Bootstrap installation and SPI packaging belong only to the separately reviewed disposable image. */
public final class CodeIntentFixtureResourceFactory implements RealmResourceProviderFactory {
    @Override public String getId(){return "armoney-code-intent-fixture";}
    @Override public RealmResourceProvider create(KeycloakSession session){return new RealmResourceProvider(){public Object getResource(){
        try{
            var context=CurrentRequestManager.get();
            if(context==null)throw new CodeIntentFixtureSupport.Rejected(503);
            boolean peer=session.getContext().getUri().getPath().endsWith("/membership");
            authenticate(context.serverRequest().getAllRequestHeaders(peer?"X-ArMoney-Fixture-Peer-Key":"X-ArMoney-Code-Intent-Fixture-Control-Key"),peer);
            return new CodeIntentFixtureResource(session,CodeIntentFixtureBodyDeadline.arm(context));
        }catch(CodeIntentFixtureSupport.Rejected failure){throw denied(failure.status);}
        catch(RuntimeException failure){throw denied(503);}
    }public void close(){}};}
    static void authenticate(java.util.List<String> values,boolean peer){if(values==null||values.size()!=1||!CodeIntentFixtureSupport.current().authenticate(values.getFirst(),peer))throw new CodeIntentFixtureSupport.Rejected(401);}
    private static jakarta.ws.rs.WebApplicationException denied(int status){return new jakarta.ws.rs.WebApplicationException(jakarta.ws.rs.core.Response.status(status).type("application/json").header("Cache-Control","no-store").build());}
    @Override public void init(Config.Scope scope){}
    @Override public void postInit(KeycloakSessionFactory factory){CodeIntentFixtureBootstrap.postInit();}
    @Override public void close(){CodeIntentFixtureBootstrap.closeInstalled();}
}
