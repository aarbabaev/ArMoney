package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LegacyMigrationOtp.*;
import java.util.*;

/** No Main/routes/factory activation. Missing genuine stage adapter is fail-closed. */
public final class LegacyMigrationOtpService {
    private final LegacyMigrationOtpStore store;
    private final LegacyMigrationOtpStore.StagePort stage;
    public LegacyMigrationOtpService(LegacyMigrationOtpStore store){this(store,LegacyMigrationOtpStore.unavailableStage());}
    public LegacyMigrationOtpService(LegacyMigrationOtpStore store,LegacyMigrationOtpStore.StagePort stage){this.store=Objects.requireNonNull(store);this.stage=Objects.requireNonNull(stage);}
    public void stage(UUID migration,String capability){store.inRequest(migration,capability,request->{var seed=request.stageSeed();var response=stage.stage(seed);request.attachStage(response);return null;});}
    public Result issue(UUID migration,String capability,UUID command,Purpose purpose){return store.inRequest(migration,capability,request->request.issue(command,purpose));}
    public Result consume(UUID migration,String capability,UUID command,UUID challenge,Purpose purpose,String code){return store.inRequest(migration,capability,request->request.consume(command,challenge,purpose,code));}
    public void allocateRecovery(UUID migration){throw new com.arman.bank.authservice.domain.LegacyMigrationOtp.Failure(com.arman.bank.authservice.domain.LegacyMigrationOtp.Error.UNAVAILABLE);}
}
