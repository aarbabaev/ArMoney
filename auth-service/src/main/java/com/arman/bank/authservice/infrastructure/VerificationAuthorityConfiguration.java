package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.IdentityVerificationService;
import com.arman.bank.authservice.domain.IdentityVerification.Policy;
import com.arman.bank.runtime.Database;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Independent default-off staging bridge. No projection/proof positive supplier is wired. */
public final class VerificationAuthorityConfiguration {
    public final boolean enabled;
    private final String bridgeKey,statusKey;
    private final URI origin;
    private final Policy policy;
    private final VerificationCrypto crypto;
    private final byte[] retentionKey;
    public VerificationAuthorityConfiguration(Map<String,String> environment){
        String flag=environment.getOrDefault("AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED","false");
        if(!Set.of("true","false").contains(flag))invalid();enabled=flag.equals("true");
        if(!enabled){bridgeKey=statusKey=null;origin=null;policy=null;crypto=null;retentionKey=null;return;}
        if(!"false".equals(environment.getOrDefault("AUTH_VERIFICATION_BRIDGE_ENABLED","false")))invalid();
        bridgeKey=required(environment,"SSO_VERIFICATION_BRIDGE_KEY");statusKey=required(environment,"AUTH_TO_SSO_STATUS_KEY");
        HttpVerificationObservations.credential(bridgeKey);HttpVerificationObservations.credential(statusKey);
        if(bridgeKey.length()>256)invalid();
        String retention=required(environment,"AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY");retentionKey=key(retention);
        String otp=required(environment,"VERIFICATION_OTP_HMAC_KEY"),encryption=required(environment,"VERIFICATION_DELIVERY_ENCRYPTION_KEY");
        byte[] otpKey=key(otp),encryptionKey=key(encryption);
        List<String> owned=List.of(bridgeKey,statusKey,retention,otp,encryption);
        for(int i=0;i<owned.size();i++)for(int j=i+1;j<owned.size();j++)if(same(owned.get(i),owned.get(j)))invalid();
        for(String name:List.of("INTERNAL_AUTH_KEY","SSO_LOGIN_PROOF_WRITE_KEY","SSO_LOGIN_PROOF_RESOLVE_KEY","SSO_LOGIN_PROOF_RETENTION_KEY",
                "SSO_MIGRATION_PROOF_WRITE_KEY","SSO_MIGRATION_PROOF_RESOLVE_KEY","SSO_MIGRATION_PROOF_RETENTION_KEY","SSO_LOGIN_CODE_HMAC_KEY",
                "ARMONEY_LOGIN_CODE_HMAC_KEY","ARMONEY_PROVIDER_BINDING_KEY","SSO_CLIENT_SECRET","AUTH_TO_USER_VERIFICATION_PROJECTION_KEY",
                "AUTH_VERIFICATION_PROJECTION_RETENTION_KEY","VERIFICATION_SMS_SEND_TOKEN","SMS_SANDBOX_WRITE_TOKEN","SMS_SANDBOX_READ_TOKEN","MAILTRAP_SMTP_PASSWORD")){
            String other=environment.get(name);if(other!=null)for(String value:owned)if(same(value,other))invalid();
        }
        String issuer=required(environment,"SSO_ISSUER");
        if(issuer.length()>512)invalid();
        String configured=required(environment,"SSO_VERIFICATION_CLIENT_IDS");String[] parts=configured.split(",",-1);
        Set<String> clients=new HashSet<>(Arrays.asList(parts));if(clients.size()!=parts.length)invalid();
        try{policy=Policy.defaults(Set.of(issuer),clients,Set.of("sso-provider"));}catch(RuntimeException failure){throw new IllegalArgumentException("Invalid verification authority configuration");}
        try{origin=URI.create(required(environment,"SSO_ENROLLMENT_STATUS_ORIGIN"));HttpVerificationObservations.origin(origin,false);}catch(RuntimeException failure){throw new IllegalArgumentException("Invalid verification authority configuration");}
        String keyId=required(environment,"VERIFICATION_KEY_ID");crypto=new VerificationCrypto(keyId,Map.of(keyId,otpKey),Map.of(keyId,encryptionKey));
        Arrays.fill(otpKey,(byte)0);Arrays.fill(encryptionKey,(byte)0);
    }
    /** Off returns null before touching a database, allocating stores, routes or external clients. */
    public Resources open(Database database){
        if(!enabled)return null;Objects.requireNonNull(database);
        var observer=new HttpVerificationObservations(origin,statusKey,policy);
        try{
            var store=new PostgresIdentityVerificationStore(database,crypto,retentionKey,policy,null);
            var routes=new VerificationRoutes(new IdentityVerificationService(store,observer,policy),bridgeKey);
            return new Resources(routes,observer);
        }catch(RuntimeException failure){observer.close();throw failure;}
    }
    public static final class Resources implements AutoCloseable {
        private final VerificationRoutes routes;private final HttpVerificationObservations observer;
        private Resources(VerificationRoutes routes,HttpVerificationObservations observer){this.routes=routes;this.observer=observer;}
        public VerificationRoutes routes(){return routes;}
        @Override public void close(){observer.close();}
        @Override public String toString(){return "VerificationAuthorityResources[redacted]";}
    }
    private static String required(Map<String,String> environment,String name){String value=environment.get(name);if(value==null||value.isBlank())invalid();return value;}
    private static byte[] key(String value){try{byte[] bytes=Base64.getDecoder().decode(value);if(bytes.length!=32||!Base64.getEncoder().encodeToString(bytes).equals(value))invalid();return bytes;}catch(RuntimeException failure){throw new IllegalArgumentException("Invalid verification authority configuration");}}
    private static byte[] decoded(String value){try{return key(value);}catch(IllegalArgumentException invalid){return null;}}
    private static boolean same(String a,String b){byte[] x=a.getBytes(StandardCharsets.UTF_8),y=b.getBytes(StandardCharsets.UTF_8),dx=decoded(a),dy=decoded(b);try{return MessageDigest.isEqual(x,y)||(dx!=null&&MessageDigest.isEqual(dx,y))||(dy!=null&&MessageDigest.isEqual(x,dy))||(dx!=null&&dy!=null&&MessageDigest.isEqual(dx,dy));}finally{if(dx!=null)Arrays.fill(dx,(byte)0);if(dy!=null)Arrays.fill(dy,(byte)0);}}
    private static void invalid(){throw new IllegalArgumentException("Invalid verification authority configuration");}
    @Override public String toString(){return "VerificationAuthorityConfiguration[redacted]";}
}
