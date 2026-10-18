package com.arman.bank.authservice.infrastructure;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** JCE AES-256-GCM custody, not JOSE or signed-provider verification. Persist ciphertext before any redirect.
 * Database UNIQUE(key_id,nonce) must reject collisions; there is no current-key fallback. */
public final class LegacyMigrationCrypto {
    public record Context(UUID attemptId,String purpose,String issuer,String client,String redirect,String challenge,Instant created,Instant deadline) {}
    public record Encrypted(String keyId,byte[] nonce,byte[] ciphertext){
        public Encrypted {nonce=nonce.clone();ciphertext=ciphertext.clone();}
        @Override public byte[] nonce(){return nonce.clone();}
        @Override public byte[] ciphertext(){return ciphertext.clone();}
        @Override public String toString(){return "LegacyVerifierCustody[redacted]";}
    }
    private final String current;
    private final Map<String,byte[]> encryption;
    private final String digestCurrent;
    private final Map<String,byte[]> digests;
    private final SecureRandom random=new SecureRandom();
    public LegacyMigrationCrypto(String current,Map<String,byte[]> encryption,byte[] digest){
        this(current,encryption,"digest-v1",Map.of("digest-v1",digest));
    }
    public LegacyMigrationCrypto(String current,Map<String,byte[]> encryption,String digestCurrent,Map<String,byte[]> digests){
        if(current==null||encryption==null||encryption.isEmpty()||encryption.size()>8||!encryption.containsKey(current)
                ||digestCurrent==null||digests==null||digests.isEmpty()||digests.size()>8||!digests.containsKey(digestCurrent))throw invalid();
        var digestCopy=new HashMap<String,byte[]>();
        digests.forEach((id,key)->{
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,32}")||key==null||key.length!=32||digestCopy.values().stream().anyMatch(other->MessageDigest.isEqual(other,key)))throw invalid();
            digestCopy.put(id,key.clone());
        });
        var copy=new HashMap<String,byte[]>();
        encryption.forEach((id,key)->{
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,32}")||key==null||key.length!=32||digestCopy.values().stream().anyMatch(other->MessageDigest.isEqual(other,key))
                    ||copy.values().stream().anyMatch(prior->MessageDigest.isEqual(prior,key)))throw invalid();
            copy.put(id,key.clone());
        });this.current=current;this.encryption=Map.copyOf(copy);this.digestCurrent=digestCurrent;this.digests=Map.copyOf(digestCopy);
    }
    public String digestKeyId(){return digestCurrent;}
    public byte[] capability(UUID migration,UUID owner,String token){return capability(digestCurrent,migration,owner,token);}
    public byte[] capability(String keyId,UUID migration,UUID owner,String token){return hmac(keyId,"armoney-legacy-migration-owner-capability-v1",migration.toString(),owner.toString(),token);}
    public byte[] command(UUID owner,UUID command,UUID migration,long ownerGeneration,long credentialGeneration,long emailGeneration){
        return hmac(digestCurrent,"armoney-legacy-migration-command-v1","LOCAL_PASSWORD",owner.toString(),Long.toString(ownerGeneration),Long.toString(credentialGeneration),Long.toString(emailGeneration),migration.toString(),command.toString());
    }
    public byte[] commandOidc(UUID owner,UUID command,UUID migration,long ownerGeneration,long associationGeneration,long credentialGeneration,long emailGeneration,String issuer,String subject,String receiptHash){
        if(owner==null||command==null||migration==null||ownerGeneration<0||associationGeneration<0||credentialGeneration<0||emailGeneration<0||issuer==null||subject==null||receiptHash==null||!receiptHash.matches("[0-9a-f]{64}"))throw unavailable();
        return hmac(digestCurrent,"armoney-legacy-migration-command-v1","LEGACY_OIDC_REAUTH",owner.toString(),Long.toString(ownerGeneration),Long.toString(associationGeneration),Long.toString(credentialGeneration),Long.toString(emailGeneration),migration.toString(),command.toString(),issuer,subject,receiptHash);
    }
    private byte[] hmac(String keyId,String... fields){
        byte[] key=digests.get(keyId);if(key==null)throw unavailable();
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(tuple(fields));}
        catch(GeneralSecurityException bad){throw unavailable();}
    }
    public Encrypted encrypt(Context context,String verifier){
        validate(context,verifier);byte[] nonce=new byte[12];random.nextBytes(nonce);byte[] plain=verifier.getBytes(StandardCharsets.US_ASCII);
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryption.get(current),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(context));return new Encrypted(current,nonce,cipher.doFinal(plain));}
        catch(GeneralSecurityException bad){throw unavailable();}finally{Arrays.fill(plain,(byte)0);}
    }
    public String decrypt(Context context,Encrypted encrypted){
        if(encrypted==null||encrypted.nonce().length!=12||encrypted.ciphertext().length<59||encrypted.ciphertext().length>256||!encryption.containsKey(encrypted.keyId()))throw unavailable();
        byte[] plain=null;
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryption.get(encrypted.keyId()),"AES"),new GCMParameterSpec(128,encrypted.nonce()));cipher.updateAAD(aad(context));plain=cipher.doFinal(encrypted.ciphertext());String verifier=new String(plain,StandardCharsets.US_ASCII);validate(context,verifier);return verifier;}
        catch(GeneralSecurityException|IllegalArgumentException bad){throw unavailable();}finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    private static void validate(Context context,String verifier){
        if(context==null||context.attemptId()==null||!Set.of("LEGACY_ACCOUNT","MANAGED_BINDING").contains(context.purpose())||context.created()==null||context.deadline()==null||!context.created().isBefore(context.deadline())
                ||verifier==null||!verifier.matches("[A-Za-z0-9._~-]{43,128}"))throw unavailable();
        try{String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));if(!challenge.equals(context.challenge()))throw unavailable();}
        catch(NoSuchAlgorithmException bad){throw unavailable();}
    }
    private static byte[] aad(Context c){return tuple("armoney-migration-pkce-custody-v1","1",c.attemptId().toString(),c.purpose(),c.issuer(),c.client(),c.redirect(),c.challenge(),Long.toString(c.created().toEpochMilli()),Long.toString(c.deadline().toEpochMilli()));}
    private static byte[] tuple(String... fields){
        try{var out=new ByteArrayOutputStream();var data=new DataOutputStream(out);for(String field:fields){byte[] raw=Objects.requireNonNull(field).getBytes(StandardCharsets.UTF_8);if(raw.length>4096)throw unavailable();data.writeInt(raw.length);data.write(raw);}return out.toByteArray();}
        catch(IOException bad){throw unavailable();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid legacy migration keys");}
    private static IllegalStateException unavailable(){return new IllegalStateException("Legacy migration custody unavailable");}
    @Override public String toString(){return "LegacyMigrationCrypto[redacted]";}
}
