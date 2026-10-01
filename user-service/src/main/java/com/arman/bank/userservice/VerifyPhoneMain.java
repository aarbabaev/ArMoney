package com.arman.bank.userservice;

import com.arman.bank.runtime.Database;
import com.arman.bank.runtime.ServiceRuntime;
import com.arman.bank.userservice.infrastructure.PostgresProfiles;
import java.util.UUID;

/** Explicit operator action after an external ownership check; never called by the service. */
public final class VerifyPhoneMain {
    private VerifyPhoneMain() {}
    public static void main(String[] args) {
        if (args.length != 5 || !"--confirm-out-of-band".equals(args[4])) {
            System.err.println("Usage: VerifyPhoneMain IDENTITY EXPECTED_E164_PHONE OPERATOR_REFERENCE EVIDENCE_REFERENCE --confirm-out-of-band");
            System.exit(2);
        }
        try {
            var identity = UUID.fromString(args[0]);
            if (!identity.toString().equalsIgnoreCase(args[0])) throw new IllegalArgumentException();
            try (var db = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"))) {
                new PostgresProfiles(db).verifyPendingPhone(identity, args[1], args[2], args[3]);
            }
            System.out.println("Pending phone verified; audit event recorded.");
        } catch (Exception e) {
            // Do not leak SQL, phone numbers, connection strings or credentials in operator output.
            System.err.println("Verification refused or unavailable. Check expected pending state, uniqueness and database access.");
            System.exit(1);
        }
    }
}
