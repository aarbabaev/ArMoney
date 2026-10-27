package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LegacyMigrationOtp.Purpose;
import java.util.Objects;

/** Borrows the approved transport; never closes it or fabricates a registration lease/version. */
public final class LegacyMigrationDeliveryTransport {
    private final VerificationDeliveryTransport transport;
    public LegacyMigrationDeliveryTransport(VerificationDeliveryTransport transport){this.transport=Objects.requireNonNull(transport);}
    public boolean send(PostgresLegacyMigrationOtpStore.DeliveryLease lease,LegacyMigrationOtpCrypto.Payload payload)throws InterruptedException{
        if(lease==null||payload==null||!payload.code().matches("[0-9]{6}"))return false;
        if(lease.envelope().purpose()==Purpose.EMAIL){
            String text="Your ArMoney account migration email verification code is "+payload.code()+". Use it only for your current migration. Do not share this code.";
            String html="<!doctype html><html lang='en'><body><h1>Verify your email for ArMoney</h1><p>Your migration verification code is <strong>"+payload.code()+"</strong>.</p><p>Use it only for your current migration. Do not share this code.</p></body></html>";
            return transport.sendEmail(payload.destination(),"ArMoney migration email verification",text,html);
        }
        if(lease.envelope().purpose()!=Purpose.PHONE)return false;
        String text="Your ArMoney account migration phone verification code is "+payload.code()+". Do not share this code.";
        return transport.sendSms(lease.deliveryId(),payload.destination(),text,VerificationDeliveryTransport.SmsPurpose.LEGACY_MIGRATION_PHONE);
    }
}
