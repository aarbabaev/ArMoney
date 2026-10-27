package com.arman.bank.sso;

import org.keycloak.Config;
import org.keycloak.authentication.*;
import org.keycloak.models.*;
import org.keycloak.services.Urls;

/** Routes migration to stock signed-link verification; never sets the verification flag. */
public final class MigrationEmailVerificationFactory implements RequiredActionFactory {
    @Override public String getId(){return "armoney-migration-email-verification";}
    @Override public String getDisplayText(){return "ArMoney migration provider email verification";}
    @Override public RequiredActionProvider create(KeycloakSession session){
        return action(()->CodeIntentLoginProtocolFactory.owner(session).configuration());
    }
    static RequiredActionProvider action(java.util.function.Supplier<CodeIntentProviderConfiguration> settings){
        return new RequiredActionProvider(){
            @Override public void evaluateTriggers(RequiredActionContext context){
                var configuration=settings.get();
                if(configuration==null||!configuration.enabled())return;
                var auth=context.getAuthenticationSession();var user=context.getUser();
                if(auth==null||user==null||auth.getAuthenticatedUser()==null||!user.getId().equals(auth.getAuthenticatedUser().getId())
                        ||!"REQUIRED_ACTIONS".equals(auth.getAction())||!user.isEnabled()||user.isEmailVerified()
                        ||!EnrollmentObservation.canonicalEmail(user.getEmail()))return;
                if(CodeIntentRegistrationGuard.matches(configuration.environment().get("ARMONEY_PROVIDER_BINDING_KEY"),context.getRealm().getId(),auth,user.getId()))return;
                String issuer=Urls.realmIssuer(context.getUriInfo().getBaseUri(),context.getRealm().getName());
                // Unrelated stock/native flows remain unchanged; only exact manifest migration tuples trigger.
                CodeIntentProviderConfiguration.Purpose purpose;
                try{purpose=configuration.purpose(issuer,auth.getClient().getClientId(),auth.getRedirectUri());}
                catch(IllegalArgumentException unrelated){return;}
                if(!MigrationReadinessObservation.migration(purpose))return;
                MigrationReadinessObservation.requireClient(configuration,issuer,auth.getClient(),auth.getRedirectUri(),purpose);
                auth.addRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
            }
            @Override public void requiredActionChallenge(RequiredActionContext context){context.failure();}
            @Override public void processAction(RequiredActionContext context){context.failure();}
            @Override public void close(){}
        };
    }
    @Override public void init(Config.Scope config){}
    @Override public void postInit(KeycloakSessionFactory factory){}
    @Override public void close(){}
}
