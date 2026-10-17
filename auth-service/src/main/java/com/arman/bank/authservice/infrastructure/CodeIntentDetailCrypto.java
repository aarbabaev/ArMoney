package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Short-lived opaque binding only. Neither ciphertext nor plaintext establishes authority. */
public final class CodeIntentDetailCrypto {
    private final String current;
    private final Map<String,byte[]> keys;
    private final Set<String> retired;
    private final SecureRandom random;
    private final java.util.concurrent.atomic.AtomicLong encryptionCount=new java.util.concurrent.atomic.AtomicLong();
    public CodeIntentDetailCrypto(String current,Map<String,String> encoded,Set<String> retired){
        this(current,encoded,retired,new SecureRandom());
    }
    // Package-private deterministic RNG seam for collision acceptance only; production factory uses CSPRNG.
    CodeIntentDetailCrypto(String current,Map<String,String> encoded,Set<String> retired,SecureRandom random){
        this.random=Objects.requireNonNull(random);
        if(current==null||encoded==null||encoded.isEmpty()||encoded.size()>8||!encoded.containsKey(current)||retired==null||retired.size()>1024||retired.contains(current))invalid();
        var copy=new TreeMap<String,byte[]>();var fingerprints=new HashSet<String>();
        encoded.forEach((id,text)->{
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,32}")||text==null)invalid();
            byte[] decoded;
            try{decoded=Base64.getDecoder().decode(text);}catch(IllegalArgumentException bad){throw new IllegalArgumentException("Invalid intent encryption configuration");}
            if(decoded.length!=32||!Base64.getEncoder().encodeToString(decoded).equals(text)||!fingerprints.add(HexFormat.of().formatHex(fingerprint(decoded))))invalid();
            copy.put(id,decoded);
        });
        for(String id:retired)if(id==null||!id.matches("[A-Za-z0-9_-]{1,32}"))invalid();
        this.current=current;this.keys=Collections.unmodifiableMap(copy);this.retired=Set.copyOf(retired);
    }
    long encryptionCount(){return encryptionCount.get();}
    public String currentId(){return current;}
    Set<String> keyIds(){return keys.keySet();}
    Set<String> retiredIds(){return retired;}
    boolean contains(String id){return keys.containsKey(id);}
    byte[] fingerprint(String id){byte[] value=keys.get(id);if(value==null)throw unavailable();return fingerprint(value);}
    byte[] nonce(){byte[] value=new byte[12];random.nextBytes(value);return value;}
    public byte[] encrypt(String keyId,byte[] nonce,String opaque,byte[] aad){
        validate(opaque);bounds(nonce,null);if(aad==null)throw unavailable();
        encryptionCount.incrementAndGet();
        byte[] plaintext=opaque.getBytes(StandardCharsets.UTF_8);
        try{return cipher(Cipher.ENCRYPT_MODE,keyId,nonce,aad).doFinal(plaintext);}
        catch(GeneralSecurityException failed){throw unavailable();}
        finally{Arrays.fill(plaintext,(byte)0);}
    }
    public String decrypt(String keyId,byte[] nonce,byte[] ciphertext,byte[] aad){
        if(ciphertext==null)throw unavailable();
        bounds(nonce,ciphertext);if(aad==null)throw unavailable();
        byte[] plaintext=null;
        try{
            plaintext=cipher(Cipher.DECRYPT_MODE,keyId,nonce,aad).doFinal(ciphertext);
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString();
            validate(value);return value;
        }catch(GeneralSecurityException|CharacterCodingException failed){throw unavailable();}
        finally{if(plaintext!=null)Arrays.fill(plaintext,(byte)0);}
    }
    public static byte[] aad(UUID intent,String retentionKey,byte[] binding,byte[] payload,String codeKey,Instant issued,Instant expiry){
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            for(String value:List.of("armoney-intent-auth-session-v1",intent.toString(),retentionKey,HexFormat.of().formatHex(binding),HexFormat.of().formatHex(payload),codeKey,issued.toString(),expiry.toString())){
                byte[] field=value.getBytes(StandardCharsets.UTF_8);out.writeInt(field.length);out.write(field);
            }
            return bytes.toByteArray();
        }catch(IOException failed){throw unavailable();}
    }
    private Cipher cipher(int mode,String id,byte[] nonce,byte[] aad)throws GeneralSecurityException{
        var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key(id),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad);return cipher;
    }
    private byte[] key(String id){byte[] value=keys.get(id);if(value==null||retired.contains(id))throw unavailable();return value;}
    private static byte[] fingerprint(byte[] key){try{return MessageDigest.getInstance("SHA-256").digest(key);}catch(NoSuchAlgorithmException failed){throw unavailable();}}
    private static void validate(String value){if(value==null||value.isBlank()||value.length()>255||value.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff)))throw unavailable();}
    private static void bounds(byte[] nonce,byte[] ciphertext){if(nonce==null||nonce.length!=12||(ciphertext!=null&&(ciphertext.length<16||ciphertext.length>1024)))throw unavailable();}
    private static LoginProof.Failure unavailable(){return new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);}
    private static void invalid(){throw new IllegalArgumentException("Invalid intent encryption configuration");}
    @Override public String toString(){return "CodeIntentDetailCrypto[redacted]";}
}
