package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LegacyMigrationOtp;
import com.arman.bank.authservice.domain.LegacyMigrationOtp.Purpose;
import java.util.*;
import java.time.Instant;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Migration-specific HMAC/AES-GCM; no registration enum/version substitution. */
public final class LegacyMigrationOtpCrypto {
    public record Envelope(UUID deliveryId,UUID challengeId,UUID candidateId,Purpose purpose,String bindingHash,String contactDigest,Instant expiresAt) {}
    public record Encrypted(String keyId,byte[] nonce,byte[] ciphertext){
        public Encrypted{nonce=nonce.clone();ciphertext=ciphertext.clone();}
        @Override public byte[] nonce(){return nonce.clone();}
        @Override public byte[] ciphertext(){return ciphertext.clone();}
        @Override public String toString(){return "MigrationOtpCiphertext[redacted]";}
    }
    public record Payload(String destination,String code){@Override public String toString(){return "MigrationOtpPayload[redacted]";}}
    private final String current;
    private final Map<String,byte[]> hmac,encryption;
    private final SecureRandom random=new SecureRandom();
    public LegacyMigrationOtpCrypto(String current,Map<String,byte[]> hmac,Map<String,byte[]> encryption){
        this.current=Objects.requireNonNull(current);this.hmac=keys(hmac);this.encryption=keys(encryption);
        if(!this.hmac.containsKey(current)||!this.encryption.containsKey(current))throw unavailable();
        for(byte[] a:this.hmac.values())for(byte[] b:this.encryption.values())if(MessageDigest.isEqual(a,b))throw unavailable();
    }
    private static Map<String,byte[]> keys(Map<String,byte[]> input){
        if(input==null||input.isEmpty()||input.size()>8)throw unavailable();var result=new HashMap<String,byte[]>();
        input.forEach((id,key)->{if(id==null||!id.matches("[A-Za-z0-9_-]{1,32}")||key==null||key.length!=32||result.values().stream().anyMatch(value->MessageDigest.isEqual(value,key)))throw unavailable();result.put(id,key.clone());});return Map.copyOf(result);
    }
    public String keyId(){return current;}
    public String code(){return String.format(Locale.ROOT,"%06d",random.nextInt(1000000));}
    public byte[] hash(String key,String domain,String... fields){
        if(!Set.of("armoney-legacy-migration-otp-binding-v2","armoney-legacy-migration-otp-binding-v1","armoney-legacy-migration-otp-v1","armoney-legacy-migration-otp-contact-v1","armoney-legacy-migration-otp-command-v1","armoney-legacy-migration-otp-code-commitment-v1").contains(domain))throw unavailable();
        byte[] material=hmac.get(key);if(material==null)throw unavailable();
        var values=new ArrayList<String>();values.add(domain);values.addAll(Arrays.asList(fields));
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(material,"HmacSHA256"));return mac.doFinal(LegacyMigrationOtp.tuple(values.toArray(String[]::new)));}
        catch(GeneralSecurityException failure){throw unavailable();}
    }
    public byte[] codeDigest(String key,Envelope envelope,long generation,String code){validate(envelope);if(generation!=1||code==null||!code.matches("[0-9]{6}"))throw unavailable();return hash(key,"armoney-legacy-migration-otp-v1",envelope.bindingHash(),envelope.candidateId().toString(),envelope.purpose().name(),channel(envelope.purpose()),Long.toString(generation),envelope.challengeId().toString(),envelope.contactDigest(),Long.toString(envelope.expiresAt().toEpochMilli()),code);}
    public Encrypted encrypt(Envelope envelope,String destination,String code){
        validate(envelope);if(destination==null||destination.length()>512||code==null||!code.matches("[0-9]{6}"))throw unavailable();
        byte[] nonce=new byte[12];random.nextBytes(nonce);byte[] plain=LegacyMigrationOtp.tuple(destination,code);
        try{return new Encrypted(current,nonce,crypt(Cipher.ENCRYPT_MODE,current,nonce,aad(envelope),plain));}finally{Arrays.fill(plain,(byte)0);}
    }
    public Payload decrypt(Envelope envelope,Encrypted encrypted){
        validate(envelope);if(encrypted==null||encrypted.ciphertext().length>1024)throw unavailable();
        byte[] plain=crypt(Cipher.DECRYPT_MODE,encrypted.keyId(),encrypted.nonce(),aad(envelope),encrypted.ciphertext());
        try(var input=new DataInputStream(new ByteArrayInputStream(plain))){String destination=read(input),code=read(input);if(input.available()!=0||!code.matches("[0-9]{6}"))throw unavailable();return new Payload(destination,code);}
        catch(IOException bad){throw unavailable();}finally{Arrays.fill(plain,(byte)0);}
    }
    private byte[] crypt(int mode,String key,byte[] nonce,byte[] aad,byte[] bytes){
        byte[] material=encryption.get(key);if(material==null||nonce.length!=12)throw unavailable();
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(material,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad);return cipher.doFinal(bytes);}
        catch(GeneralSecurityException bad){throw unavailable();}
    }
    private static byte[] aad(Envelope e){return LegacyMigrationOtp.tuple("armoney-legacy-migration-otp-delivery-v1",e.deliveryId().toString(),e.bindingHash(),e.challengeId().toString(),e.candidateId().toString(),e.purpose().name(),channel(e.purpose()),"1",e.contactDigest(),Long.toString(e.expiresAt().toEpochMilli()));}
    private static String read(DataInputStream input)throws IOException{int length=input.readInt();if(length<1||length>512||length>input.available())throw new IOException();return new String(input.readNBytes(length),StandardCharsets.UTF_8);}
    public static String channel(Purpose purpose){return purpose==Purpose.EMAIL?"EMAIL":"LEGACY_MIGRATION_PHONE";}
    private static void validate(Envelope e){if(e==null||e.deliveryId()==null||e.challengeId()==null||e.candidateId()==null||e.purpose()==null||e.expiresAt()==null)throw unavailable();LegacyMigrationOtp.digest(e.bindingHash());LegacyMigrationOtp.digest(e.contactDigest());}
    private static LegacyMigrationOtp.Failure unavailable(){return new LegacyMigrationOtp.Failure(LegacyMigrationOtp.Error.UNAVAILABLE);}
    @Override public String toString(){return "LegacyMigrationOtpCrypto[redacted]";}
}
