package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.runtime.Database;
import java.util.*;

/** Independent strict default-off factory. Main and production HTTP do not instantiate it. */
public final class CodeIntentConfiguration {
    public final boolean enabled;
    private final LoginProof.Policy policy;
    private final CodeIntentRetentionKeys keys;
    public CodeIntentConfiguration(Map<String,String> environment) {
        String flag=environment.getOrDefault("AUTH_SSO_CODE_INTENT_STAGE_ENABLED","false");
        if(!"true".equals(flag)&&!"false".equals(flag))throw new IllegalArgumentException("Invalid code intent flag");
        enabled=flag.equals("true");
        if(!enabled){policy=null;keys=null;return;}
        var authority=new VerificationAuthorityConfiguration(environment);
        if(!authority.completionEnabled||!authority.eligibilityGrantsEnabled)throw new IllegalArgumentException("Code intent authority configuration required");
        String current=required(environment,"SSO_LOGIN_PROOF_RETENTION_HMAC_KEY_ID");
        var ring=new HashMap<String,String>();
        ring.put(current,required(environment,"SSO_LOGIN_PROOF_RETENTION_HMAC_KEY"));
        String previous=environment.getOrDefault("SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS","");
        if(!previous.isEmpty())for(String entry:previous.split(",",-1)){
            String[] pair=entry.split(":",2);
            if(pair.length!=2||ring.putIfAbsent(pair[0],pair[1])!=null)throw new IllegalArgumentException("Invalid intent key ring");
        }
        keys=new CodeIntentRetentionKeys(current,ring);
        for(var entry:environment.entrySet())if(!entry.getKey().startsWith("SSO_LOGIN_PROOF_RETENTION_HMAC_")&&(entry.getKey().contains("KEY")||entry.getKey().contains("SECRET")||entry.getKey().contains("TOKEN")||entry.getKey().contains("PASSWORD"))
            &&ring.values().stream().anyMatch(value->sameKey(value,entry.getValue())))
            throw new IllegalArgumentException("Intent retention key must be separate");
        policy=LoginProof.Policy.defaults(values(environment,"SSO_CODE_INTENT_ISSUERS"),values(environment,"SSO_CODE_INTENT_CLIENTS"),
            values(environment,"SSO_CODE_INTENT_CODE_KEY_IDS"),values(environment,"SSO_CODE_INTENT_ACTORS"));
    }
    public Optional<Resources> open(Database database){
        if(!enabled)return Optional.empty();
        var store=new PostgresCodeIntentStore(Objects.requireNonNull(database),policy,keys);
        return Optional.of(new Resources(store,new CodeIntentCleanupWorker(store)));
    }
    public record Resources(PostgresCodeIntentStore store,CodeIntentCleanupWorker cleanupWorker) implements AutoCloseable {
        @Override public void close(){cleanupWorker.close();}
    }
    private static boolean sameKey(String canonical,String other){
        if(other==null)return false;
        byte[] key=Base64.getDecoder().decode(canonical);
        byte[] raw=other.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try{
            if(java.security.MessageDigest.isEqual(key,raw))return true;
            try{return java.security.MessageDigest.isEqual(key,Base64.getDecoder().decode(other));}
            catch(IllegalArgumentException notBase64){return false;}
        }finally{Arrays.fill(key,(byte)0);Arrays.fill(raw,(byte)0);}
    }
    private static Set<String> values(Map<String,String> env,String name){
        String[] values=required(env,name).split(",",-1);
        var result=new HashSet<>(Arrays.asList(values));
        if(result.size()!=values.length||result.contains(""))throw new IllegalArgumentException("Invalid code intent scope");
        return Set.copyOf(result);
    }
    private static String required(Map<String,String> env,String name){String value=env.get(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Incomplete code intent configuration");return value;}
}
