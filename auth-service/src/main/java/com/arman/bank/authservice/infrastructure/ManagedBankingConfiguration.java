package com.arman.bank.authservice.infrastructure;

import java.util.*;

/** Pure strict default-off configuration. No database/client/worker allocation or activation. */
public final class ManagedBankingConfiguration {
    public final boolean enabled;
    private final CodeIntentPairScopes scopes;
    private final ManagedBankingKeys keys;
    public ManagedBankingConfiguration(Map<String,String> environment){this(environment,Map.of());}
    public ManagedBankingConfiguration(Map<String,String> environment,Map<String,byte[]> locallyOwnedMaterials){
        Objects.requireNonNull(environment);Objects.requireNonNull(locallyOwnedMaterials);
        String flag=environment.getOrDefault("AUTH_MANAGED_BANKING_ENABLED","false");
        if(!"true".equals(flag)&&!"false".equals(flag))throw invalid();enabled="true".equals(flag);
        scopes=new CodeIntentPairScopes(environment.getOrDefault("ARMONEY_SSO_MANAGED_PAIRS","[]"),environment.getOrDefault("ARMONEY_SSO_LEGACY_PAIRS","[]"));
        if(!enabled){keys=null;return;}
        if(!"true".equals(environment.get("AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED"))||scopes.managed().isEmpty())throw invalid();
        String id=environment.get("AUTH_MANAGED_BANKING_KEY_ID"),current=environment.get("AUTH_MANAGED_BANKING_KEY");
        if(id==null||current==null)throw invalid();
        var ring=new HashMap<String,String>();ring.put(id,current);
        String previous=environment.getOrDefault("AUTH_MANAGED_BANKING_PREVIOUS_KEYS","");
        if(previous.length()>1024)throw invalid();
        if(!previous.isEmpty())for(String entry:previous.split(",",-1)){
            String[] member=entry.split(":",2);if(member.length!=2||ring.putIfAbsent(member[0],member[1])!=null)throw invalid();
        }
        keys=new ManagedBankingKeys(id,ring);
        // Delegate raw/canonical-decoded alias independence against every configured/injected local role.
        // Remove this ring's textual settings: each entry is supplied as a named decoded immutable key.
        var effective=new HashMap<>(environment);effective.remove("AUTH_MANAGED_BANKING_KEY_ID");effective.remove("AUTH_MANAGED_BANKING_KEY");effective.remove("AUTH_MANAGED_BANKING_PREVIOUS_KEYS");
        var materials=new HashMap<String,byte[]>();locallyOwnedMaterials.forEach((name,bytes)->{
            if(name==null||bytes==null)throw invalid();materials.put(name,bytes.clone());
        });
        ring.forEach((name,text)->{if(materials.putIfAbsent("managed."+name,Base64.getDecoder().decode(text))!=null)throw invalid();});
        try{new CodeConfirmationConfiguration(effective,materials);}
        finally{materials.values().forEach(value->Arrays.fill(value,(byte)0));}
    }
    public CodeIntentPairScopes scopes(){return scopes;}
    public ManagedBankingKeys keys(){if(!enabled)throw invalid();return keys;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid managed banking configuration");}
    @Override public String toString(){return "ManagedBankingConfiguration[redacted]";}
}
