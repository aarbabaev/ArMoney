package com.arman.bank.authservice.infrastructure;
import com.arman.bank.runtime.Database;
import java.util.*;
/** Default-off resource ownership. SMTP/profile HTTP never run during construction or capture. */
public final class LoginNotificationResources implements AutoCloseable {
    private final HttpLoginNotificationProfiles profiles;private final LoginNotificationWorker worker;private boolean closed;
    private LoginNotificationResources(HttpLoginNotificationProfiles profiles,LoginNotificationWorker worker){this.profiles=profiles;this.worker=worker;}
    public static Optional<LoginNotificationResources> open(Database database,LoginNotificationConfiguration config,ManagedBankingKeys keys,LegacyMigrationManagedProofCrypto migration){
        Objects.requireNonNull(config);if(!config.enabled)return Optional.empty();
        var profiles=new HttpLoginNotificationProfiles(config.users,config.serviceKey);
        try{var smtp=new MailtrapSmtp(config.email);var outbox=new PostgresLoginNotificationOutbox(database,keys,migration);
            var worker=new LoginNotificationWorker(outbox,profiles,claim->{var email=LoginEmail.render(claim.occurredAt(),claim.displayName());return smtp.send(claim.recipient(),email.subject(),email.text(),email.html());});
            return Optional.of(new LoginNotificationResources(profiles,worker));
        }catch(RuntimeException|Error failure){profiles.close();throw failure;}
    }
    public synchronized void start(){if(closed)throw new IllegalStateException("Notice resources closed");worker.start();}
    @Override public synchronized void close(){if(closed)return;closed=true;try{worker.close();}finally{profiles.close();}}
}
