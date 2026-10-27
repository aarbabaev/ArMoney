package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LegacyMigrationOtp.*;
import java.util.*;
import java.util.function.Function;

/** Initial only. No producer can allocate recovery evidence or authorize publication/final completion. */
public interface LegacyMigrationOtpStore {
    <T>T inRequest(UUID migrationId,String ownerCapability,Function<Request,T> action);
    interface Request {
        Seed stageSeed();
        void attachStage(StageExchange authenticatedExchange);
        Result issue(UUID commandId,Purpose purpose);
        Result consume(UUID commandId,UUID challengeId,Purpose purpose,String code);
    }
    @FunctionalInterface interface StagePort {
        /** Genuine authenticated User U2 stage; resolves real profile binding, never assumes profileId=identityId. */
        StageExchange stage(Seed seed);
    }
    /** Default has no provider/profile/User adapter. This is an explicit unavailable dependency. */
    static StagePort unavailableStage(){return seed->{throw new com.arman.bank.authservice.domain.LegacyMigrationOtp.Failure(com.arman.bank.authservice.domain.LegacyMigrationOtp.Error.UNAVAILABLE);};}
}
