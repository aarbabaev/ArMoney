package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LegacyMigrationFinalization.*;
import java.util.UUID;
import java.util.function.Function;

/** Genuine creator admission and separate immutable internal readback; no final promotion port. */
public interface LegacyMigrationFinalizationStore {
    <T>T inRequest(UUID migration,String ownerCapability,Function<Request,T> action);
    interface Request { Decision authorize(); }
    Decision decision(DecisionQuery query);
    Evidence evidence(EvidenceQuery query);
}
