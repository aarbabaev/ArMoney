package com.arman.bank.authservice.infrastructure;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure, strict aggregate. Selection is not final-cutover readiness or authorization. */
public final class LegacyMigrationConfiguration {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String LIVE_FLAG="AUTH_MIGRATION_USER_REVISION_ENABLED";
    private static final Set<String> LIVE_SETTINGS=Set.of(LIVE_FLAG,"AUTH_MIGRATION_USER_REVISION_WRITER","AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY","AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY_PREVIOUS","AUTH_MIGRATION_USER_MUTATION_WRITER_ID","USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY");
    public final boolean enabled;
    final LegacyMigrationBrowserConfiguration browser;
    final LegacyMigrationBrowserIngressConfiguration ingress;
    final String actor, encryptionId,digestId,proofId,otpId;
    private final Map<String,byte[]> encryption,digests,proof,otpHmac,otpEncryption;
    final LegacyMigrationManagedProviderClient.Settings managed;
    final LegacyMigrationLegacyAccountProviderClient.Settings account;
    final ObservationSettings observation;
    final URI userOrigin,smsOrigin;
    final String readKey,stageKey,publicationKey,decisionKey,smsKey;
    final EmailConfiguration email;
    private LegacyMigrationConfiguration(){enabled=false;browser=null;ingress=null;actor=encryptionId=digestId=proofId=otpId=null;encryption=digests=proof=otpHmac=otpEncryption=Map.of();managed=null;account=null;observation=null;userOrigin=smsOrigin=null;readKey=stageKey=publicationKey=decisionKey=smsKey=null;email=null;}
    public static LegacyMigrationConfiguration from(Map<String,String> env,Map<String,byte[]> injected){
        try{return parse(env,injected);}catch(RuntimeException invalid){throw rejected();}
    }
    private static LegacyMigrationConfiguration parse(Map<String,String> env,Map<String,byte[]> injected){
        Objects.requireNonNull(env);Objects.requireNonNull(injected);int total=0;
        for(var e:env.entrySet()){if(e.getKey()==null||e.getValue()==null)throw rejected();total=Math.addExact(total,e.getKey().getBytes(StandardCharsets.UTF_8).length+e.getValue().getBytes(StandardCharsets.UTF_8).length);}
        if(total>65536)throw rejected();
        String flag=env.getOrDefault("AUTH_MIGRATION_BROWSER_ENABLED","false");if(!Set.of("true","false").contains(flag))throw rejected();
        if(env.keySet().stream().anyMatch(LIVE_SETTINGS::contains)){
            var revision=MigrationUserRevisionConfiguration.from(env);
            if(revision.enabled()&&!flag.equals("true")||!revision.enabled()&&env.keySet().stream().anyMatch(n->LIVE_SETTINGS.contains(n)&&!n.equals(LIVE_FLAG)))throw rejected();
        }
        var materials=new LinkedHashMap<String,byte[]>();
        if(injected.size()>128)throw rejected();int bytes=0;
        for(var e:injected.entrySet()){if(e.getKey()==null||e.getKey().isBlank()||e.getValue()==null||e.getValue().length<1||e.getValue().length>4096)throw rejected();bytes=Math.addExact(bytes,e.getValue().length);add(materials,"injected:"+e.getKey(),e.getValue());}
        if(bytes>65536)throw rejected();
        if(flag.equals("false")){if(env.keySet().stream().anyMatch(n->n.startsWith("AUTH_MIGRATION_")&&!n.equals("AUTH_MIGRATION_BROWSER_ENABLED")&&!LIVE_SETTINGS.contains(n)||n.equals("MIGRATION_PUBLIC_ORIGIN")||n.equals("MIGRATION_GATEWAY_TO_AUTH_KEY")||n.equals("AUTH_TO_USER_MIGRATION_PUBLICATION_KEY")||n.equals("USER_TO_AUTH_MIGRATION_DECISION_KEY")))throw rejected();return new LegacyMigrationConfiguration();}
        return new LegacyMigrationConfiguration(env,materials);
    }
    private LegacyMigrationConfiguration(Map<String,String> e,Map<String,byte[]> materials){
        enabled=true;
        actor=match(e,"AUTH_MIGRATION_ACTOR_ID","[A-Za-z0-9_-]{1,64}");
        encryptionId=id(e,"AUTH_MIGRATION_CUSTODY_CURRENT_KEY_ID");digestId=id(e,"AUTH_MIGRATION_DIGEST_CURRENT_KEY_ID");proofId=id(e,"AUTH_MIGRATION_PROOF_CURRENT_KEY_ID");otpId=id(e,"AUTH_MIGRATION_OTP_CURRENT_KEY_ID");
        encryption=ring(e,"AUTH_MIGRATION_CUSTODY_ENCRYPTION_KEYS",encryptionId,materials);digests=ring(e,"AUTH_MIGRATION_DIGEST_KEYS",digestId,materials);proof=ring(e,"AUTH_MIGRATION_PROOF_KEYS",proofId,materials);otpHmac=ring(e,"AUTH_MIGRATION_OTP_HMAC_KEYS",otpId,materials);otpEncryption=ring(e,"AUTH_MIGRATION_OTP_ENCRYPTION_KEYS",otpId,materials);
        for(var entry:e.entrySet()){
            String n=entry.getKey();
            // These are independently validated routing settings, not credential roles.
            // Both clients must use the same issuer's token endpoint.
            if(Set.of("AUTH_MIGRATION_LEGACY_TOKEN_ENDPOINT","AUTH_MIGRATION_MANAGED_TOKEN_ENDPOINT").contains(n))continue;
            if(n.startsWith("AUTH_MIGRATION_")&&n.endsWith("_KEYS")||n.endsWith("_KEY_ID")||n.endsWith("_KEY_IDS"))continue;
            if(n.endsWith("_PREVIOUS_KEYS")){
                if(!Set.of("SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS","SSO_CODE_INTENT_DETAIL_ENCRYPTION_PREVIOUS_KEYS","AUTH_MANAGED_BANKING_PREVIOUS_KEYS").contains(n))throw rejected();
                retained(n,entry.getValue(),materials);continue;
            }
            if(n.endsWith("_KEYS"))throw rejected();
            if(n.contains("KEY")||n.contains("SECRET")||n.contains("TOKEN")||n.contains("PASSWORD"))add(materials,"env:"+n,entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        ingress=LegacyMigrationBrowserIngressConfiguration.from(e);
        URI issuer=https(required(e,"SSO_ISSUER"));if(!issuer.getRawPath().equals("/sso/realms/armoney"))throw rejected();
        String legacy=match(e,"AUTH_MIGRATION_LEGACY_CLIENT_ID","[A-Za-z0-9_-]{1,128}"),target=match(e,"AUTH_MIGRATION_MANAGED_CLIENT_ID","[A-Za-z0-9_-]{1,128}");if(legacy.equals(target))throw rejected();
        var scopes=new CodeIntentPairScopes(required(e,"ARMONEY_SSO_MANAGED_PAIRS"),required(e,"ARMONEY_SSO_LEGACY_PAIRS"));
        if(scopes.classify(issuer.toString(),legacy)!=CodeIntentPairScopes.Classification.LEGACY||scopes.classify(issuer.toString(),target)!=CodeIntentPairScopes.Classification.MANAGED)throw rejected();
        URI la=endpoint(e,"AUTH_MIGRATION_LEGACY_AUTHORIZATION_ENDPOINT",issuer,"auth"),ma=endpoint(e,"AUTH_MIGRATION_MANAGED_AUTHORIZATION_ENDPOINT",issuer,"auth");
        URI lc=ingress.publicOrigin().resolve("/migration/legacy/account-oidc/callback"),mc=ingress.publicOrigin().resolve("/migration/managed/callback");
        account=new LegacyMigrationLegacyAccountProviderClient.Settings(issuer,endpoint(e,"AUTH_MIGRATION_LEGACY_TOKEN_ENDPOINT",issuer,"token"),endpoint(e,"AUTH_MIGRATION_LEGACY_JWKS_URI",issuer,"certs"),legacy,lc);
        managed=new LegacyMigrationManagedProviderClient.Settings(issuer,endpoint(e,"AUTH_MIGRATION_MANAGED_TOKEN_ENDPOINT",issuer,"token"),endpoint(e,"AUTH_MIGRATION_MANAGED_JWKS_URI",issuer,"certs"),target,mc);
        browser=new LegacyMigrationBrowserConfiguration(true,ingress.publicOrigin(),lc,mc,la,legacy,ma,target);
        URI origin=URI.create(required(e,"SSO_ENROLLMENT_STATUS_ORIGIN"));HttpVerificationObservations.origin(origin,false);
        var pairs=new ArrayList<Map<String,String>>();pairs.add(Map.of("issuer",issuer.toString(),"client_id",legacy));pairs.add(Map.of("issuer",issuer.toString(),"client_id",target));
        observation=new ObservationSettings(origin.resolve("/sso/realms/armoney/armoney-status/code-session"),credential(e,"AUTH_TO_SSO_STATUS_KEY"),scopes);
        userOrigin=URI.create(required(e,"USER_BASE_URL"));if(!userOrigin.toString().equals("http://user-service:8080"))throw rejected();
        readKey=credential(e,"INTERNAL_AUTH_KEY");stageKey=credential(e,"AUTH_TO_USER_MIGRATION_STAGE_KEY");
        publicationKey=credential(e,"AUTH_TO_USER_MIGRATION_PUBLICATION_KEY");decisionKey=credential(e,"USER_TO_AUTH_MIGRATION_DECISION_KEY");
        smsOrigin=VerificationDeliveryConfiguration.smsOrigin(required(e,"VERIFICATION_SMS_BASE_URL"));smsKey=match(e,"VERIFICATION_SMS_SEND_TOKEN","[A-Za-z0-9_-]{32,128}");
        var smtp=new HashMap<>(e);smtp.put("LOGIN_NOTIFICATIONS_ENABLED","true");email=new EmailConfiguration(smtp);
    }
    /** Transport-only scope, never application classification. No secret-bearing record rendering. */
    public static final class ObservationSettings {
        private final URI endpoint;private final String key;private final CodeIntentPairScopes scopes;
        private ObservationSettings(URI endpoint,String key,CodeIntentPairScopes scopes){
            new HttpCodeSessionObservations.ValidatedMigrationObservation(endpoint,key,scopes);
            this.endpoint=endpoint;this.key=key;this.scopes=scopes;
        }
        HttpCodeSessionObservations.ValidatedMigrationObservation validated(){return new HttpCodeSessionObservations.ValidatedMigrationObservation(endpoint,key,scopes);}
        URI endpoint(){return endpoint;}String key(){return key;}CodeIntentPairScopes scopes(){return scopes;}
        /** Explicit transport purpose union; does not change legacy/managed classification. */
        Set<CodeIntentPairScopes.Pair> allowedPairs(){var pairs=new HashSet<>(scopes.managed());pairs.addAll(scopes.legacy());return Set.copyOf(pairs);}
        @Override public String toString(){return "MigrationObservationSettings[redacted]";}
    }
    private static void retained(String name,String raw,Map<String,byte[]> inventory){
        if(raw.length()>4096)throw rejected();if(raw.isEmpty())return;
        var ids=new HashSet<String>();String[] entries=raw.split(",",-1);if(entries.length>8)throw rejected();
        for(String entry:entries){String[] pair=entry.split(":",2);if(pair.length!=2||!pair[0].matches("[A-Za-z0-9_-]{1,32}")||!ids.add(pair[0]))throw rejected();
            byte[] bytes=Base64.getDecoder().decode(pair[1]);if(bytes.length!=32||!Base64.getEncoder().encodeToString(bytes).equals(pair[1]))throw rejected();add(inventory,"env:"+name+":"+pair[0],bytes);
        }
    }
    private static Map<String,byte[]> ring(Map<String,String> e,String name,String current,Map<String,byte[]> inventory){
        String raw=required(e,name);if(raw.getBytes(StandardCharsets.UTF_8).length>4096||raw.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff))throw rejected();
        try{var tree=JSON.readTree(raw);if(!tree.isObject()||tree.size()<1||tree.size()>8)throw rejected();var result=new HashMap<String,byte[]>();var fields=tree.fields();while(fields.hasNext()){var f=fields.next();if(!f.getKey().matches("[A-Za-z0-9_-]{1,32}")||!f.getValue().isTextual())throw rejected();String encoded=f.getValue().textValue();byte[] value=Base64.getDecoder().decode(encoded);if(value.length!=32||!Base64.getEncoder().encodeToString(value).equals(encoded))throw rejected();add(inventory,name+":"+f.getKey(),value);result.put(f.getKey(),value.clone());}if(!result.containsKey(current))throw rejected();return Map.copyOf(result);}catch(java.io.IOException invalid){throw rejected();}
    }
    private static void add(Map<String,byte[]> inventory,String name,byte[] raw){var forms=forms(raw);for(byte[] prior:inventory.values())if(!Collections.disjoint(forms,forms(prior)))throw rejected();if(inventory.size()>=128)throw rejected();int total=raw.length;for(byte[] value:inventory.values())total=Math.addExact(total,value.length);if(total>65536)throw rejected();inventory.put(name,raw.clone());}
    private static Set<String> forms(byte[] raw){var values=new HashSet<String>();values.add(Base64.getEncoder().encodeToString(raw));String text=new String(raw,StandardCharsets.ISO_8859_1);for(var d:List.of(Base64.getDecoder(),Base64.getUrlDecoder()))try{byte[] decoded=d.decode(text);String std=Base64.getEncoder().encodeToString(decoded),url=Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);if(text.equals(std)||text.equals(url))values.add(std);}catch(IllegalArgumentException ignored){}return values;}
    private static String required(Map<String,String> e,String key){String value=e.get(key);if(value==null||value.isEmpty())throw rejected();return value;}
    private static String match(Map<String,String> e,String key,String pattern){String v=required(e,key);if(!v.matches(pattern))throw rejected();return v;}
    private static String id(Map<String,String> e,String key){return match(e,key,"[A-Za-z0-9_-]{1,32}");}
    private static String credential(Map<String,String> e,String key){return match(e,key,"[!-~]{32,256}");}
    private static URI https(String value){if(value.length()>512||value.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff)))throw rejected();URI u=URI.create(value);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||u.getPort()==0||u.getPort()>65535||!u.toASCIIString().equals(value))throw rejected();return u;}
    private static URI endpoint(Map<String,String> e,String key,URI issuer,String suffix){URI u=https(required(e,key));if(!u.equals(URI.create(issuer+"/protocol/openid-connect/"+suffix)))throw rejected();return u;}
    private static Map<String,byte[]> copy(Map<String,byte[]> keys){var c=new HashMap<String,byte[]>();keys.forEach((id,b)->c.put(id,b.clone()));return Map.copyOf(c);}
    Map<String,byte[]> encryption(){return copy(encryption);}Map<String,byte[]> digests(){return copy(digests);}Map<String,byte[]> proof(){return copy(proof);}Map<String,byte[]> otpHmac(){return copy(otpHmac);}Map<String,byte[]> otpEncryption(){return copy(otpEncryption);}
    private static IllegalArgumentException rejected(){return new IllegalArgumentException("Invalid legacy migration configuration");}
    @Override public String toString(){return "LegacyMigrationConfiguration[redacted]";}
}
