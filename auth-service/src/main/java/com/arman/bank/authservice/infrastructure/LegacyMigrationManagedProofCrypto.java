package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LegacyMigrationOtp;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Auth-only bounded retained HMAC ring. Original-key receipt/attempt hashes never silently rekey. */
public final class LegacyMigrationManagedProofCrypto {
    private final String current;
    private final Map<String,byte[]> keys;
    private final SecureRandom random=new SecureRandom();
    public LegacyMigrationManagedProofCrypto(String current,Map<String,byte[]> input){
        if(input==null||input.isEmpty()||input.size()>8||!input.containsKey(current))throw unavailable();
        var copy=new HashMap<String,byte[]>();
        input.forEach((id,value)->{if(id==null||!id.matches("[A-Za-z0-9_-]{1,32}")||value==null||value.length!=32||copy.values().stream().anyMatch(other->MessageDigest.isEqual(other,value)))throw unavailable();copy.put(id,value.clone());});
        this.current=current;this.keys=Map.copyOf(copy);
    }
    public String keyId(){return current;}
    Set<String> keyIds(){return keys.keySet();}
    String token(){byte[] value=new byte[32];random.nextBytes(value);try{return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}finally{Arrays.fill(value,(byte)0);}}
    byte[] hash(String key,String domain,String... fields){
        if(!Set.of("armoney-legacy-migration-user-release-v1","armoney-legacy-migration-browser-continuation-token-v1","armoney-legacy-migration-browser-continuation-command-v2","armoney-legacy-migration-final-ack-provenance-v1","armoney-legacy-migration-final-command-v1","armoney-legacy-migration-final-completion-v1","armoney-legacy-migration-publication-journal-v1","armoney-legacy-migration-email-candidate-v1","armoney-legacy-migration-email-form-v1","armoney-legacy-migration-owner-contact-transition-v1","armoney-legacy-migration-browser-cookie-v1","armoney-legacy-migration-browser-csrf-v1","armoney-legacy-migration-account-receipt-v1","armoney-legacy-migration-managed-receipt-v1","armoney-legacy-migration-target-v1","armoney-legacy-migration-managed-attempt-v1","armoney-legacy-migration-managed-browser-v1","armoney-legacy-migration-managed-state-v1","armoney-legacy-migration-managed-nonce-v1","armoney-legacy-migration-managed-callback-v1","armoney-legacy-migration-managed-account-v1").contains(domain))throw unavailable();
        byte[] material=keys.get(key);if(material==null)throw unavailable();
        var all=new ArrayList<String>();all.add(domain);all.addAll(Arrays.asList(fields));
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(material,"HmacSHA256"));return mac.doFinal(LegacyMigrationOtp.tuple(all.toArray(String[]::new)));}catch(GeneralSecurityException failure){throw unavailable();}
    }
    static String challenge(String verifier){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}catch(NoSuchAlgorithmException failure){throw unavailable();}}
    static com.arman.bank.authservice.domain.LegacyMigrationOtp.Failure unavailable(){return new com.arman.bank.authservice.domain.LegacyMigrationOtp.Failure(com.arman.bank.authservice.domain.LegacyMigrationOtp.Error.UNAVAILABLE);}
    @Override public String toString(){return "MigrationManagedProofKeys[redacted]";}
}
