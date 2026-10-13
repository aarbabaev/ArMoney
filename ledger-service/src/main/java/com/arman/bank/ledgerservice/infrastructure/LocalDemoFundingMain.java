package com.arman.bank.ledgerservice.infrastructure;

import com.arman.bank.runtime.Database;
import java.util.UUID;

/** Run manually with the ledger distribution classpath; credentials are environment-only. */
public final class LocalDemoFundingMain {
    private LocalDemoFundingMain() {}

    public static LocalDemoFunding.Command parse(String[] args) {
        if (args.length != 5) throw new IllegalArgumentException("Expected fundingId ownerId walletId accountId amountFils");
        return new LocalDemoFunding.Command(UUID.fromString(args[0]), UUID.fromString(args[1]),
            UUID.fromString(args[2]), UUID.fromString(args[3]), Long.parseLong(args[4]));
    }

    public static void main(String[] args) {
        try {
            var environment = System.getenv();
            LocalDemoFunding.requireLocalOptIn(environment);
            var command = parse(args);
            for (String key : new String[]{"DB_URL", "DB_USER", "DB_PASSWORD"}) {
                if (environment.get(key) == null || environment.get(key).isBlank())
                    throw new IllegalArgumentException("Database configuration required");
            }
            try (var db = new Database(environment.get("DB_URL"), environment.get("DB_USER"), environment.get("DB_PASSWORD"))) {
                String status = new LocalDemoFunding(db, environment).fund(command);
                System.out.printf("{\"funding_id\":\"%s\",\"account_id\":\"%s\",\"status\":\"%s\"}%n",
                    command.fundingId(), command.accountId(), status);
            }
        } catch (Exception failure) {
            // Database exception messages may contain connection or payload details.
            System.err.println("Local demo funding failed; check opt-in, configuration and command identity. Retry the same funding ID after uncertain outcomes.");
            System.exit(1);
        }
    }
}
