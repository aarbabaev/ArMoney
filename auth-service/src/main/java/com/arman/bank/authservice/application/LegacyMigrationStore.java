package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LegacyMigration.*;
import java.util.UUID;

/** Only local password admission is implemented. Real migration receipts/U2/fresh proof are separate dependencies. */
public interface LegacyMigrationStore {
    /** Expected verifier is request-local comparison only, never stored in migration records. */
    Admission admitLocal(UUID identityId,String expectedVerifier,long expectedCredentialGeneration,long expectedOwnerGeneration,UUID commandId);
    Status status(UUID migrationId,String ownerCapability);
    /** SSO-only owners require the separately implemented signed, fresh exact-mapping proof adapter. */
    default Admission admitExistingSso(String issuer,String subject,ProofReceipt proof){throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);}
    /** No fabricated OIDC proof, receipt or final ACK can complete the foundation. */
    default void authorizePublication(UUID migrationId){throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);}
    default void complete(UUID migrationId){throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);}
}
