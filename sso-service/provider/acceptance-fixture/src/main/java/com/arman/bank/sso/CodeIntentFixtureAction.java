package com.arman.bank.sso;

import org.keycloak.authentication.*;

/** Native acceptance-only continuation; never interprets PROJECTED as production ACTIVE. */
public final class CodeIntentFixtureAction implements RequiredActionProvider {
    private static final String NOTE="armoney.fixture.experiment";
    @Override public void evaluateTriggers(RequiredActionContext context){} // Disposable realm explicitly selects this action.
    @Override public void requiredActionChallenge(RequiredActionContext context){
        try{String handle=CodeIntentFixtureSupport.current().bindFlow(context);context.getAuthenticationSession().setAuthNote(NOTE,handle);render(context);}
        catch(RuntimeException unavailable){context.failure();}
    }
    @Override public void processAction(RequiredActionContext context){
        try{
            var support=CodeIntentFixtureSupport.current();String handle=context.getAuthenticationSession().getAuthNote(NOTE);
            if(handle==null||!handle.equals(support.bindFlow(context)))throw new CodeIntentFixtureSupport.Rejected(409);
            String operation=context.getHttpRequest().getDecodedFormParameters().getFirst("operation");
            if("prepare".equals(operation)){support.prepare(handle);render(context);}
            else if("continue".equals(operation))support.continueNative(context,handle);
            else throw new CodeIntentFixtureSupport.Rejected(400);
        }catch(RuntimeException unavailable){context.failure();}
    }
    private void render(RequiredActionContext context){context.challenge(context.form().createForm("code-intent-continuation.ftl"));}
    @Override public void close(){}
}
