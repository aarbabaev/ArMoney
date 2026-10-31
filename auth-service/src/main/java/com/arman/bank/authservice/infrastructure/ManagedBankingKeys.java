package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.ManagedBankingAccess.Token;
import com.arman.bank.authservice.domain.LoginProof;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independent original-ID ring; no token material is retained or logged. */
public final class ManagedBankingKeys {
    private final String current;
    private final Map<String,byte[]> keys;
    public ManagedBankingKeys(String current,Map<String,String> encoded){
        if(current==null||encoded==null||encoded.isEmpty()||encoded.size()>8||!encoded.containsKey(current))throw invalid();
        var copy=new HashMap<String,byte[]>();
        encoded.forEach((id,text)->{
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,32}")||text==null)throw invalid();
            byte[] bytes;try{bytes=Base64.getDecoder().decode(text);}catch(IllegalArgumentException bad){throw invalid();}
            if(bytes.length!=32||!Base64.getEncoder().encodeToString(bytes).equals(text))throw invalid();
            if(copy.values().stream().anyMatch(value->java.security.MessageDigest.isEqual(value,bytes)))throw invalid();
            copy.put(id,bytes.clone());
        });this.current=current;this.keys=Map.copyOf(copy);
    }
    byte[] noticeKey(String id){
        byte[] root=keys.get(id);if(root==null)throw invalid();
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(root,"HmacSHA256"));return mac.doFinal("armoney-login-notice-aead-v1".getBytes(StandardCharsets.US_ASCII));}
        catch(java.security.GeneralSecurityException failed){throw new IllegalStateException("Notice key unavailable");}
    }
    public String currentId(){return current;}
    public void require(String id){if(!keys.containsKey(id))throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);}
    public byte[] fingerprint(String id,Token token){
        require(id);
        try{
            var buffer=new ByteArrayOutputStream();var out=new DataOutputStream(buffer);
            for(String value:List.of("armoney-managed-banking-token-v1",token.binding().issuer(),token.binding().subject(),token.binding().clientId(),token.binding().userSessionId(),token.eventId().toString(),token.bearer())){
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
            }
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(keys.get(id),"HmacSHA256"));return mac.doFinal(buffer.toByteArray());
        }catch(IOException|java.security.GeneralSecurityException failure){throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);}
    }
    /** Migration source fingerprint. Original registration fingerprint remains byte-for-byte unchanged. */
    public byte[] fingerprint(String id,Token token,UUID sourceId,long classification,long epoch,long sourceRevision,UUID proofId){
        require(id);if(sourceId==null||proofId==null||classification<1||epoch<1||sourceRevision<1)throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);
        try{
            var buffer=new ByteArrayOutputStream();var out=new DataOutputStream(buffer);
            for(String value:List.of("armoney-managed-session-event-source-v1","LEGACY_MIGRATION_DUAL_CONTACT",sourceId.toString(),Long.toString(classification),Long.toString(epoch),Long.toString(sourceRevision),
                token.binding().issuer(),token.binding().subject(),token.binding().clientId(),token.binding().userSessionId(),token.eventId().toString(),proofId.toString(),
                com.arman.bank.authservice.domain.LegacyMigrationContact.instant(token.issuedAt()),com.arman.bank.authservice.domain.LegacyMigrationContact.instant(token.expiresAt()))){
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
            }
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(keys.get(id),"HmacSHA256"));return mac.doFinal(buffer.toByteArray());
        }catch(IOException|java.security.GeneralSecurityException failure){throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid managed banking keys");}
    @Override public String toString(){return "ManagedBankingKeys[redacted]";}
}
