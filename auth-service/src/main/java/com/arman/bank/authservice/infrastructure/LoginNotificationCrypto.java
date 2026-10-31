package com.arman.bank.authservice.infrastructure;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
/** Derived notice key only. Immutable AAD binds owner/event/current authority stamp. */
final class LoginNotificationCrypto {
    record Context(UUID id,String issuer,UUID event,UUID owner,String key,byte[] stamp,Instant occurred){
        @Override public String toString(){return "LoginNoticeContext[redacted]";}
    }
    record Payload(String subject,String recipient,String displayName){
        @Override public String toString(){return "LoginNoticePayload[redacted]";}
    }
    record Encrypted(byte[] nonce,byte[] ciphertext){@Override public String toString(){return "EncryptedLoginNotice[redacted]";}}
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ManagedBankingKeys keys;private final SecureRandom random=new SecureRandom();
    LoginNotificationCrypto(ManagedBankingKeys keys){this.keys=Objects.requireNonNull(keys);}
    Encrypted encrypt(Context context,Payload payload){
        byte[] nonce=new byte[12];random.nextBytes(nonce);byte[] bytes=null;
        try{bytes=JSON.writeValueAsBytes(Map.of("subject",payload.subject(),"recipient",payload.recipient(),"display_name",payload.displayName()==null?"":payload.displayName()));
            if(bytes.length>4000)throw rejected();return new Encrypted(nonce,crypt(Cipher.ENCRYPT_MODE,context,nonce,bytes));}
        catch(Exception failure){throw rejected();}finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    Payload decrypt(Context context,byte[] nonce,byte[] ciphertext){
        byte[] bytes=null;
        try{if(nonce==null||nonce.length!=12||ciphertext==null||ciphertext.length>4096)throw rejected();bytes=crypt(Cipher.DECRYPT_MODE,context,nonce,ciphertext);
            var json=JSON.readTree(bytes);if(!json.isObject()||json.size()!=3||!json.has("subject")||!json.has("recipient")||!json.has("display_name"))throw rejected();
            for(String field:List.of("subject","recipient","display_name"))if(!json.get(field).isTextual())throw rejected();
            String recipient=json.get("recipient").textValue(),subject=json.get("subject").textValue(),name=json.get("display_name").textValue();
            if(!EmailConfiguration.validEmail(recipient)||subject.isBlank()||subject.length()>255||subject.codePoints().anyMatch(Character::isISOControl))throw rejected();
            if(!name.isEmpty()&&!name.equals(LoginEmail.name(name)))throw rejected();return new Payload(subject,recipient,name.isEmpty()?null:name);
        }catch(Exception failure){throw rejected();}finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    }
    private byte[] crypt(int mode,Context c,byte[] nonce,byte[] input)throws Exception{
        byte[] key=keys.noticeKey(c.key());
        try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(c));return cipher.doFinal(input);}
        finally{Arrays.fill(key,(byte)0);}
    }
    private static byte[] aad(Context c)throws IOException{
        var bytes=new ByteArrayOutputStream();var output=new DataOutputStream(bytes);
        for(String value:List.of("armoney-login-notice-aead-v1",c.id().toString(),c.issuer(),c.event().toString(),c.owner().toString(),c.key(),HexFormat.of().formatHex(c.stamp()),c.occurred().toString())){byte[] text=value.getBytes(StandardCharsets.UTF_8);output.writeInt(text.length);output.write(text);}return bytes.toByteArray();
    }
    private static IllegalArgumentException rejected(){return new IllegalArgumentException("Login notice ciphertext unavailable");}
}
