package com.arman.bank.authservice.infrastructure;

import com.arman.bank.runtime.Database;
import java.util.Map;

/** Independent default-off deny-only guard. It supplies no positive eligibility. */
public final class BankingEligibilityConfiguration {
    public final boolean enabled;
    public BankingEligibilityConfiguration(Map<String, String> environment) {
        String flag = environment.getOrDefault("AUTH_BANKING_ELIGIBILITY_ENFORCED", "false");
        if (!"true".equals(flag) && !"false".equals(flag))
            throw new IllegalArgumentException("Invalid banking eligibility flag");
        enabled = "true".equals(flag);
    }
    public PostgresAuthStore store(Database database) { return new PostgresAuthStore(database, enabled); }
}