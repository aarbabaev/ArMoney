package com.arman.bank.sso;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.util.JsonSerialization;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure production composition settings. No process environment, client, realm or account mutation. */
final class CodeIntentProviderConfiguration {
    static final String FLAG="ARMONEY_LOGIN_PROOF_ENABLED",KEYS="ARMONEY_CODE_INTENT_CODE_HMAC_KEYS",MANIFEST="ARMONEY_CODE_INTENT_PURPOSE_MANIFEST";
    enum Purpose { BANK_NATIVE,MIGRATION_ACCOUNT,MIGRATION_MANAGED,LEGACY_STOCK }
    record Target(String issuer,String client,String redirect){@Override public String toString(){return "CodeIntentTarget[redacted]";}}
    private static final Set<String> CLIENTS=Set.of("armoney-android","armoney-ios","armoney-web");
    private final boolean enabled;
    private final Map<String,String> environment;
    private final Map<String,byte[]> keys;
    private final Map<Target,Purpose> purposes;
    private final CodeIntentConfiguration policy;
    private CodeIntentProviderConfiguration(boolean enabled,Map<String,String> environment,Map<String,byte[]> keys,Map<Target,Purpose> purposes,CodeIntentConfiguration policy){this.enabled=enabled;this.environment=Map.copyOf(environment);this.keys=copy(keys);this.purposes=Map.copyOf(purposes);this.policy=policy;}
    static CodeIntentProviderConfiguration from(Map<String,String> input){
        try{
            Objects.requireNonNull(input);String flag=input.getOrDefault(FLAG,"false");
            if(!Set.of("true","false").contains(flag))throw invalid();
            if(!flag.equals("true"))return new CodeIntentProviderConfiguration(false,Map.of(),Map.of(),Map.of(),null);
            int bytes=0;for(var item:input.entrySet()){if(item.getKey()==null||item.getValue()==null)throw invalid();utf8(item.getKey(),65536);if(!item.getValue().isEmpty())utf8(item.getValue(),65536);bytes=Math.addExact(bytes,item.getKey().getBytes(StandardCharsets.UTF_8).length+item.getValue().getBytes(StandardCharsets.UTF_8).length);}if(bytes>65536)throw invalid();
            for(var item:input.entrySet())if(!item.getKey().equals(KEYS)&&!item.getValue().isEmpty()
                    &&List.of("_KEYS","_SECRETS","_TOKENS","_PASSWORDS","_KEYRING","_KEY_RING").stream().anyMatch(item.getKey()::endsWith))throw invalid();
            var keys=parseKeys(input.get(KEYS),input.get("SSO_CODE_INTENT_CODE_KEY_IDS"));
            CodeIntentConfiguration policy=CodeIntentConfiguration.from(input,keys);
            String issuer=input.get("SSO_ISSUER");
            if(!policy.enabled()||issuer==null||!URI.create(issuer).getRawPath().equals("/sso/realms/armoney"))throw invalid();
            var purposes=parseManifest(input.get(MANIFEST),issuer,policy);
            return new CodeIntentProviderConfiguration(true,input,keys,purposes,policy);
        }catch(RuntimeException rejected){throw invalid();}
    }
    boolean enabled(){return enabled;}
    boolean bankClient(String issuer,String client){return enabled&&Objects.equals(environment.get("SSO_ISSUER"),issuer)&&CLIENTS.contains(client);}
    boolean purposeClient(String issuer,String client){return enabled&&Objects.equals(environment.get("SSO_ISSUER"),issuer)&&(CLIENTS.contains(client)||CodeIntentConfiguration.MIGRATION_ACCOUNT_CLIENT.equals(client));}
    Purpose purpose(String issuer,String client,String originalRedirect){
        if(!enabled||!Objects.equals(environment.get("SSO_ISSUER"),issuer)||!(CLIENTS.contains(client)||CodeIntentConfiguration.MIGRATION_ACCOUNT_CLIENT.equals(client)))throw invalid();Purpose result=purposes.get(new Target(issuer,client,originalRedirect));if(result==null)throw invalid();return result;
    }
    CodeIntentConfiguration policy(){if(!enabled)throw invalid();return policy;}
    Map<String,String> environment(){if(!enabled)throw invalid();return environment;}
    Map<String,byte[]> keyMaterial(){if(!enabled)throw invalid();return copy(keys);}
    private static Map<String,byte[]> parseKeys(String raw,String ids){
        JsonNode tree=json(raw,4096);if(!tree.isObject()||tree.size()<1||tree.size()>2||ids==null)throw invalid();
        String[] ordered=ids.split(",",-1);var expected=new LinkedHashSet<>(Arrays.asList(ordered));
        if(ordered.length<1||ordered.length>2||expected.size()!=ordered.length||expected.stream().anyMatch(id->!id.matches("[A-Za-z0-9_-]{1,32}")))throw invalid();
        var result=new LinkedHashMap<String,byte[]>();var fields=tree.fields();while(fields.hasNext()){
            var entry=fields.next();if(!expected.contains(entry.getKey())||!entry.getValue().isTextual())throw invalid();String encoded=entry.getValue().textValue();
            if(encoded.length()!=44)throw invalid();byte[] decoded=Base64.getDecoder().decode(encoded);if(decoded.length!=32||!Base64.getEncoder().encodeToString(decoded).equals(encoded))throw invalid();
            for(byte[] previous:result.values())if(java.security.MessageDigest.isEqual(previous,decoded))throw invalid();result.put(entry.getKey(),decoded);
        }
        if(!result.keySet().equals(expected))throw invalid();return result;
    }
    private static Map<Target,Purpose> parseManifest(String raw,String issuer,CodeIntentConfiguration policy){
        JsonNode tree=json(raw,16384);if(!tree.isArray()||tree.size()<1||tree.size()>16)throw invalid();
        var result=new LinkedHashMap<Target,Purpose>();
        for(JsonNode item:tree){
            Set<String> names=new HashSet<>();item.fieldNames().forEachRemaining(names::add);
            if(!item.isObject()||!names.equals(Set.of("issuer","client_id","redirect_uri","purpose")))throw invalid();
            String i=text(item,"issuer",512),client=text(item,"client_id",128),redirect=text(item,"redirect_uri",2048);Purpose purpose=Purpose.valueOf(text(item,"purpose",32));
            if(!issuer.equals(i)||!(CLIENTS.contains(client)||CodeIntentConfiguration.MIGRATION_ACCOUNT_CLIENT.equals(client)))throw invalid();
            if(CodeIntentConfiguration.MIGRATION_ACCOUNT_CLIENT.equals(client)&&purpose!=Purpose.MIGRATION_ACCOUNT)throw invalid();var pair=new CodeIntentPair(i,client);
            if(purpose==Purpose.BANK_NATIVE||purpose==Purpose.MIGRATION_MANAGED){if(!policy.managedPairs().contains(pair))throw invalid();}
            else if(!policy.legacyPairs().contains(pair))throw invalid();
            redirect(client,redirect,purpose);
            if(result.putIfAbsent(new Target(i,client,redirect),purpose)!=null)throw invalid();
        }
        if(!result.containsValue(Purpose.BANK_NATIVE))throw invalid();return result;
    }
    private static void redirect(String client,String value,Purpose purpose){
        URI uri=URI.create(value);if(!uri.toASCIIString().equals(value)||!uri.normalize().equals(uri)||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawUserInfo()!=null||value.indexOf('*')>=0||value.indexOf('%')>=0)throw invalid();
        boolean migration=purpose==Purpose.MIGRATION_ACCOUNT||purpose==Purpose.MIGRATION_MANAGED;
        if(migration){String path=purpose==Purpose.MIGRATION_ACCOUNT?"/migration/legacy/account-oidc/callback":"/migration/managed/callback";if(!"https".equals(uri.getScheme())||uri.getHost()==null||!path.equals(uri.getRawPath()))throw invalid();}
        else if(client.equals("armoney-android")){if(!value.equals("com.armoney.android:/oauth/callback"))throw invalid();}
        else if(client.equals("armoney-ios")){if(!value.equals("com.armoney.ios:/oauth/callback"))throw invalid();}
        else if(!"https".equals(uri.getScheme())||uri.getHost()==null||!"/oauth/callback".equals(uri.getRawPath()))throw invalid();
        if(uri.getPort()==0||uri.getPort() < -1||uri.getPort()>65535)throw invalid();
    }
    private static JsonNode json(String raw,int limit){
        utf8(raw,limit);
        try{return JsonSerialization.mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);}
        catch(Exception rejected){throw invalid();}
    }
    private static String text(JsonNode object,String name,int limit){JsonNode value=object.get(name);if(value==null||!value.isTextual()||!CodeIntentPair.safe(value.textValue(),limit))throw invalid();return value.textValue();}
    private static void utf8(String value,int limit){if(value==null||value.isEmpty()||value.charAt(0)=='\ufeff'||value.getBytes(StandardCharsets.UTF_8).length>limit)throw invalid();for(int n=0;n<value.length();n++){char c=value.charAt(n);if(Character.isHighSurrogate(c)){if(++n>=value.length()||!Character.isLowSurrogate(value.charAt(n)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}}
    private static Map<String,byte[]> copy(Map<String,byte[]> input){var result=new LinkedHashMap<String,byte[]>();input.forEach((id,value)->result.put(id,value.clone()));return Collections.unmodifiableMap(result);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Code-intent provider configuration rejected");}
    @Override public String toString(){return "CodeIntentProviderConfiguration[redacted]";}
}
