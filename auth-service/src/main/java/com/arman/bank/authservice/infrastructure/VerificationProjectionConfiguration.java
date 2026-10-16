package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.VerificationProjection.*;
import com.arman.bank.authservice.domain.VerificationProjection.Error;
import com.arman.bank.runtime.Database;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Default-off lifecycle. Enabling this tracker does not create a trusted authority supplier. */
public final class VerificationProjectionConfiguration {
    public final boolean enabled;
    private final String writer,credential;
    private final byte[] retentionKey;
    private final URI base;
    private final Policy policy;
    public VerificationProjectionConfiguration(Map<String,String> environment){
        String flag=environment.getOrDefault("AUTH_VERIFICATION_PROJECTION_ENABLED","false");
        if(!Set.of("true","false").contains(flag))throw new IllegalArgumentException("Invalid projection flag");
        enabled=flag.equals("true");
        if(!enabled){writer=credential=null;retentionKey=null;base=null;policy=null;return;}
        writer=required(environment,"AUTH_VERIFICATION_PROJECTION_WRITER_ID");policy=Policy.defaults(Set.of(writer));
        credential=required(environment,"AUTH_TO_USER_VERIFICATION_PROJECTION_KEY");HttpVerificationProjection.credential(credential);
        String encoded=required(environment,"AUTH_VERIFICATION_PROJECTION_RETENTION_KEY");retentionKey=key(encoded);
        if(same(credential,encoded))throw new IllegalArgumentException("Projection requires independently provisioned keys");
        for(String name:List.of("INTERNAL_AUTH_KEY","SSO_VERIFICATION_BRIDGE_KEY","AUTH_TO_SSO_STATUS_KEY","SSO_LOGIN_PROOF_WRITE_KEY",
                "SSO_LOGIN_PROOF_RESOLVE_KEY","SSO_LOGIN_PROOF_RETENTION_KEY","VERIFICATION_OTP_HMAC_KEY","VERIFICATION_DELIVERY_ENCRYPTION_KEY",
                "ARMONEY_PROVIDER_BINDING_KEY","SSO_CLIENT_SECRET","SSO_MIGRATION_PROOF_WRITE_KEY","SSO_MIGRATION_PROOF_RESOLVE_KEY",
                "SSO_LOGIN_CODE_HMAC_KEY","ARMONEY_LOGIN_CODE_HMAC_KEY")){
            String other=environment.get(name);if(other!=null&&(same(credential,other)||same(encoded,other)))throw new IllegalArgumentException("Projection requires independent credentials");
        }
        base=URI.create(required(environment,"USER_BASE_URL"));HttpVerificationProjection.origin(base);
    }
    public PostgresVerificationProjectionStore store(Database database){
        if(!enabled)return null;
        return new PostgresVerificationProjectionStore(database,policy,retentionKey,sql->(identity,now)->{throw new Failure(Error.UNAVAILABLE);});
    }
    PostgresVerificationProjectionStore store(Database database,java.util.function.Function<org.jooq.DSLContext,com.arman.bank.authservice.application.VerificationProjectionStore.AuthorityPort> authority){
        if(!enabled)throw new IllegalArgumentException("Completion requires projection configuration");
        return new PostgresVerificationProjectionStore(database,policy,retentionKey,authority);
    }
    String writer(){return writer;}
    VerificationProjectionWorker worker(PostgresVerificationProjectionStore store,PostgresIdentityVerificationStore authority){
        if(!enabled)throw new IllegalArgumentException("Completion requires projection configuration");
        var transport=new HttpVerificationProjection(base,credential);
        try{return new VerificationProjectionWorker(store,transport,writer,Clock.systemUTC(),(claim,result)->authority.attach(writer,claim,result));}
        catch(RuntimeException failure){transport.close();throw failure;}
    }
    public VerificationProjectionWorker worker(Database database){
        if(!enabled)return null;
        var store=store(database);var transport=new HttpVerificationProjection(base,credential);
        try{return new VerificationProjectionWorker(store,transport,writer,Clock.systemUTC());}catch(RuntimeException failure){transport.close();throw failure;}
    }
    private static String required(Map<String,String> env,String name){String value=env.get(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+name);return value;}
    private static byte[] key(String value){try{byte[] bytes=Base64.getDecoder().decode(value);if(bytes.length!=32||!Base64.getEncoder().encodeToString(bytes).equals(value))throw new IllegalArgumentException();return bytes;}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid projection retention key");}}
    private static boolean same(String a,String b){
        byte[] x=a.getBytes(StandardCharsets.UTF_8),y=b.getBytes(StandardCharsets.UTF_8),dx=decoded(a),dy=decoded(b);
        return MessageDigest.isEqual(x,y)||(dx!=null&&MessageDigest.isEqual(dx,y))||(dy!=null&&MessageDigest.isEqual(x,dy))||(dx!=null&&dy!=null&&MessageDigest.isEqual(dx,dy));
    }
    private static byte[] decoded(String value){try{return key(value);}catch(IllegalArgumentException invalid){return null;}}
    @Override public String toString(){return "VerificationProjectionConfiguration[redacted]";}
}
