package com.arman.bank.authservice.application;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Objects;

/** Browser-specific genuine producer facade; no route accepts identity/target/verifier/receipt facts. */
public interface LegacyMigrationBrowserService {
    Form form(String existingAttemptCookie);
    Account password(String formCookie,String csrf,String login,String password,UUID commandId);
    Redirect accountOidcStart(String formCookie,String csrf,UUID commandId,String existingAttemptCookie);
    Account accountOidcCallback(MigrationManagedProofPort.Callback callback);
    default Account emailCandidate(String accountCookie,String csrf,UUID commandId,String email){throw new Failure(Kind.UNAVAILABLE,false);}
    Redirect managedStart(String accountCookie,String csrf,UUID commandId,String existingAttemptCookie);
    Completion managedCallback(MigrationManagedProofPort.Callback callback);
    default VerificationPage managedVerification(MigrationManagedProofPort.Callback callback,String originalAccountCookie){throw new Failure(Kind.UNAVAILABLE,false);}
    default VerificationPage stage(String accountCookie,String csrf,UUID commandId){throw new Failure(Kind.UNAVAILABLE,false);}
    default VerificationPage issue(String accountCookie,String csrf,UUID commandId){throw new Failure(Kind.UNAVAILABLE,false);}
    default VerificationPage consume(String accountCookie,String csrf,UUID commandId,String code){throw new Failure(Kind.UNAVAILABLE,false);}
    default VerificationPage authorizePublication(String accountCookie,String csrf,UUID commandId){throw new Failure(Kind.UNAVAILABLE,false);}
    default VerificationPage publish(String accountCookie,String csrf,UUID commandId){throw new Failure(Kind.UNAVAILABLE,false);}
    default VerificationPage prepareFinal(String accountCookie,String csrf,UUID commandId){throw new Failure(Kind.UNAVAILABLE,false);}
    default VerificationPage finish(String accountCookie,String csrf,UUID commandId){throw new Failure(Kind.UNAVAILABLE,false);}
    default VerificationPage status(String accountCookie){throw new Failure(Kind.UNAVAILABLE,false);}
    sealed interface VerificationPage permits ActionBundle,ReadOnlyStatus {}
    record ActionBundle(UUID bundleId,List<ActionForm> forms,Instant originalExpiresAt) implements VerificationPage {
        public ActionBundle {Objects.requireNonNull(bundleId);forms=List.copyOf(forms);Objects.requireNonNull(originalExpiresAt);if(forms.isEmpty()||forms.size()>2||forms.stream().map(ActionForm::action).distinct().count()!=forms.size())throw new IllegalArgumentException("Invalid continuation bundle");}
        @Override public String toString(){return "MigrationActionBundle[redacted]";}
    }
    record ActionForm(Action action,String csrf,UUID commandId,Instant retryAt,int attemptsRemaining) {
        public ActionForm {Objects.requireNonNull(action);Objects.requireNonNull(commandId);if(csrf==null||!csrf.matches("[A-Za-z0-9_-]{43}")||attemptsRemaining<0||attemptsRemaining>5)throw new IllegalArgumentException("Invalid continuation form");}
        @Override public String toString(){return "MigrationActionForm[redacted]";}
    }
    record ReadOnlyStatus(Status status,Instant originalExpiresAt) implements VerificationPage {public ReadOnlyStatus{Objects.requireNonNull(status);Objects.requireNonNull(originalExpiresAt);}}
    enum Action { STAGE,EMAIL_ISSUE,EMAIL_CONSUME,PHONE_ISSUE,PHONE_CONSUME,DECISION,PUBLICATION,FINAL_PREPARE,FINAL }
    enum Status { PENDING_RESULT,RECOVERY_REQUIRED,COMPLETED }
    record Form(String formCookie,String csrf,UUID commandId,Instant expiresAt){@Override public String toString(){return "MigrationBrowserForm[redacted]";}}
    record Account(String accountCookie,String csrf,UUID commandId,Instant expiresAt,boolean emailCandidateRequired){public Account(String accountCookie,String csrf,UUID commandId,Instant expiresAt){this(accountCookie,csrf,commandId,expiresAt,false);}@Override public String toString(){return "MigrationBrowserAccount[redacted]";}}
    record Redirect(URI location,String attemptCookie,Instant expiresAt){@Override public String toString(){return "MigrationBrowserRedirect[redacted]";}}
    record Completion(String state,Instant expiresAt){@Override public String toString(){return "MigrationBrowserCompletion[redacted]";}}
    enum Kind { BAD_INPUT,UNAUTHORIZED,CONFLICT,RATE_LIMITED,UNAVAILABLE }
    final class Failure extends RuntimeException {
        private final Kind kind;private final boolean clearCookies;
        public Failure(Kind kind,boolean clearCookies){super("Migration request rejected",null,false,false);this.kind=kind;this.clearCookies=clearCookies;}
        public Kind kind(){return kind;}public boolean clearCookies(){return clearCookies;}
    }
}
