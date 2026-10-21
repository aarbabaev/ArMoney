package com.arman.bank.authservice.infrastructure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Configuration only. No database, HTTP client, route, scheduler, schema check or activation. */
public final class CodeConfirmationConfiguration {
    public static final String OBSERVER_PATH="/sso/realms/armoney/armoney-status/code-session";
    public final boolean enabled;
    private final CodeIntentPairScopes scopes;
    private final CodeIntentConfiguration stage;
    private final String actor,confirmKey,lookupKey,statusKey;
    private final URI observerOrigin;
    private final byte[] proofRetentionKey;
    public CodeConfirmationConfiguration(Map<String,String> environment){this(environment,Map.of());}
    /** Material owned by an explicitly injected local component may not have environment names.
     * This checks provided bytes, never invents provider key aliases or proves remote ring completeness. */
    public CodeConfirmationConfiguration(Map<String,String> environment,Map<String,byte[]> localMaterials){
        Objects.requireNonNull(environment);
        Objects.requireNonNull(localMaterials);
        enabled=flag(environment,"AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED");
        var globalScopes=new CodeIntentPairScopes(value(environment,"ARMONEY_SSO_MANAGED_PAIRS"),value(environment,"ARMONEY_SSO_LEGACY_PAIRS"));
        if(!enabled){scopes=globalScopes;stage=null;actor=confirmKey=lookupKey=statusKey=null;observerOrigin=null;proofRetentionKey=null;return;}
        for(String name:List.of("AUTH_SSO_CODE_INTENT_STAGE_ENABLED","AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED",
            "AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED","AUTH_REGISTRATION_ELIGIBILITY_GRANTS_ENABLED","AUTH_VERIFICATION_PROJECTION_ENABLED"))if(!flag(environment,name))throw invalid();
        if(flag(environment,"AUTH_SSO_LOGIN_PROOF_ENABLED"))throw invalid();
        if(globalScopes.managed().isEmpty())throw invalid();
        String issuer=required(environment,"SSO_ISSUER");
        var verificationClients=csv(required(environment,"SSO_VERIFICATION_CLIENT_IDS"));
        var tokenClients=Set.of("armoney-web","armoney-ios","armoney-android");
        for(var pair:globalScopes.managed())if(!issuer.equals(pair.issuer())||!verificationClients.contains(pair.clientId())||!tokenClients.contains(pair.clientId()))throw invalid();
        String accountClient="armoney-migration-account";
        if(verificationClients.contains(accountClient))throw invalid();
        var nativeLegacy=new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        for(var pair:globalScopes.legacy()){
            if(!issuer.equals(pair.issuer()))throw invalid();
            if(tokenClients.contains(pair.clientId()))nativeLegacy.addObject().put("issuer",pair.issuer()).put("client_id",pair.clientId());
            else if(!accountClient.equals(pair.clientId())||!flag(environment,"AUTH_MIGRATION_BROWSER_ENABLED")
                    ||!accountClient.equals(environment.get("AUTH_MIGRATION_LEGACY_CLIENT_ID"))
                    ||environment.get("AUTH_MIGRATION_MANAGED_CLIENT_ID")==null
                    ||!tokenClients.contains(environment.get("AUTH_MIGRATION_MANAGED_CLIENT_ID"))
                    ||globalScopes.classify(issuer,environment.get("AUTH_MIGRATION_MANAGED_CLIENT_ID"))!=CodeIntentPairScopes.Classification.MANAGED)throw invalid();
        }
        // Global classification remains intact elsewhere. C2 routes/observation receive native banking pairs only.
        scopes=new CodeIntentPairScopes(value(environment,"ARMONEY_SSO_MANAGED_PAIRS"),nativeLegacy.toString());
        if(!"/sso/realms/armoney".equals(URI.create(issuer).getRawPath()))throw invalid();
        try{observerOrigin=URI.create(required(environment,"SSO_ENROLLMENT_STATUS_ORIGIN"));HttpVerificationObservations.origin(observerOrigin,false);}
        catch(IllegalArgumentException bad){throw invalid();}
        var effective=new HashMap<>(environment);
        adapt(effective,"SSO_CODE_INTENT_ISSUERS",scopes.managedIssuers());
        adapt(effective,"SSO_CODE_INTENT_CLIENTS",scopes.managedClients());
        var codeIds=csv(required(environment,"SSO_CODE_INTENT_CODE_KEY_IDS"));
        if(codeIds.size()>2||codeIds.stream().anyMatch(id->!id.matches("[A-Za-z0-9_-]{1,32}")))throw invalid();
        actor=required(environment,"SSO_LOGIN_PROOF_WRITER_ID");
        confirmKey=required(environment,"SSO_LOGIN_PROOF_CONFIRM_KEY");lookupKey=required(environment,"SSO_CODE_INTENT_LOOKUP_KEY");
        LoginProofRoutes.credential(confirmKey);LoginProofRoutes.credential(lookupKey);
        statusKey=required(environment,"AUTH_TO_SSO_STATUS_KEY");
        byte[] parsedProofKey=proofKey(required(environment,"SSO_LOGIN_PROOF_RETENTION_KEY"));
        try{
            separate(environment,localMaterials);
            stage=new CodeIntentConfiguration(effective);
            // Validates independently selected legacy bridge/enforcement without opening any resources.
            new LoginProofConfiguration(environment);
            proofRetentionKey=parsedProofKey;
        }catch(RuntimeException invalid){Arrays.fill(parsedProofKey,(byte)0);throw invalid;}
    }
    public CodeIntentPairScopes scopes(){return scopes;}
    public URI observerEndpoint(){if(!enabled)throw invalid();return observerOrigin.resolve(OBSERVER_PATH);}
    String actor(){return actor;}
    String confirmationKey(){return confirmKey;}
    String lookupKey(){return lookupKey;}
    String statusKey(){return statusKey;}
    CodeIntentConfiguration stageConfiguration(){return stage;}
    /** Original V7 recipient digest key; no derived replacement or silent historical rehash. */
    byte[] proofRetentionKey(){if(!enabled)throw invalid();return proofRetentionKey.clone();}
    private static byte[] proofKey(String value){
        if(value.length()!=44)throw invalid();
        byte[] decoded;
        try{decoded=Base64.getDecoder().decode(value);}catch(IllegalArgumentException bad){throw invalid();}
        if(decoded.length!=32||!Base64.getEncoder().encodeToString(decoded).equals(value)){Arrays.fill(decoded,(byte)0);throw invalid();}
        return decoded;
    }
    private static void adapt(Map<String,String> values,String name,Set<String> projection){
        if(values.containsKey(name)&&!csv(required(values,name)).equals(projection))throw invalid();
        values.put(name,String.join(",",new TreeSet<>(projection)));
    }
    private static Set<String> csv(String raw){
        if(raw.length()>16384)throw invalid();
        var items=raw.split(",",-1);var values=new HashSet<>(Arrays.asList(items));
        if(values.size()>32||values.size()!=items.length||values.contains("")||values.stream().anyMatch(x->!x.equals(x.strip())))throw invalid();return values;
    }
    private static boolean flag(Map<String,String> env,String name){String value=env.containsKey(name)?env.get(name):"false";if(value==null||!Set.of("true","false").contains(value))throw invalid();return "true".equals(value);}
    private static String value(Map<String,String> env,String name){return env.containsKey(name)?env.get(name):"[]";}
    private static String required(Map<String,String> env,String name){String value=env.get(name);if(value==null||value.isBlank())throw invalid();return value;}
    private static void separate(Map<String,String> env,Map<String,byte[]> supplied){
        var materials=new ArrayList<String>();
        for(var item:env.entrySet()){
            String name=item.getKey(),value=item.getValue();
            // These two fixed same-issuer endpoints are routing metadata, not secret roles.
            if(Set.of("AUTH_MIGRATION_LEGACY_TOKEN_ENDPOINT","AUTH_MIGRATION_MANAGED_TOKEN_ENDPOINT").contains(name))continue;
            if(value==null||value.isEmpty()||name.endsWith("_ID")||name.endsWith("_IDS"))continue;
            if(!(name.contains("KEY")||name.contains("SECRET")||name.contains("TOKEN")||name.contains("PASSWORD")))continue;
            if(name.endsWith("_PREVIOUS_KEYS")){
                for(String member:value.split(",",-1)){var pair=member.split(":",2);if(pair.length!=2||pair[0].isEmpty())throw invalid();materials.add(pair[1]);}
            }else materials.add(value);
        }
        for(int i=0;i<materials.size();i++)for(int j=i+1;j<materials.size();j++)if(same(materials.get(i),materials.get(j)))throw invalid();
        var local=new ArrayList<byte[]>();
        if(supplied.size()>32)throw invalid();
        try{
            for(var item:supplied.entrySet()){
                if(item.getKey()==null||!item.getKey().matches("[A-Za-z0-9_.-]{1,128}")||item.getValue()==null||item.getValue().length<1||item.getValue().length>1024)throw invalid();
                byte[] value=item.getValue().clone();
                local.add(value);
                for(int i=0;i<local.size()-1;i++)if(sameBytes(local.get(i),value))throw invalid();
                for(String material:materials)if(sameBytes(material.getBytes(StandardCharsets.UTF_8),value))throw invalid();
            }
        }finally{for(byte[] value:local)Arrays.fill(value,(byte)0);}
    }
    private static byte[] decoded(byte[] value){
        for(byte item:value)if(item<0)return null; // Arbitrary opaque bytes are not coerced to text.
        try{byte[] bytes=Base64.getDecoder().decode(value);return bytes.length==32&&Arrays.equals(Base64.getEncoder().encode(bytes),value)?bytes:null;}
        catch(IllegalArgumentException bad){return null;}
    }
    private static boolean same(String first,String second){
        return sameBytes(first.getBytes(StandardCharsets.UTF_8),second.getBytes(StandardCharsets.UTF_8));
    }
    private static boolean sameBytes(byte[] a,byte[] b){
        byte[] da=decoded(a),db=decoded(b);
        try{return MessageDigest.isEqual(a,b)||(da!=null&&MessageDigest.isEqual(da,b))||(db!=null&&MessageDigest.isEqual(a,db))||(da!=null&&db!=null&&MessageDigest.isEqual(da,db));}
        finally{if(da!=null)Arrays.fill(da,(byte)0);if(db!=null)Arrays.fill(db,(byte)0);}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid code confirmation configuration");}
    @Override public String toString(){return "CodeConfirmationConfiguration[redacted]";}
}
