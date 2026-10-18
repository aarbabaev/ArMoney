package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import org.keycloak.util.JsonSerialization;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure, unwired configuration. Allocates no HTTP/DB resources and reads no process environment. */
final class CodeIntentConfiguration {
    private static final Set<String> PUBLIC_CLIENTS=Set.of("armoney-ios","armoney-android","armoney-web");
    static final String MIGRATION_ACCOUNT_CLIENT="armoney-migration-account";
    private final boolean enabled;
    private final Set<CodeIntentPair> managed,legacy;
    private CodeIntentConfiguration(boolean enabled,Set<CodeIntentPair> managed,Set<CodeIntentPair> legacy) {
        this.enabled=enabled; this.managed=Set.copyOf(managed); this.legacy=Set.copyOf(legacy);
    }
    static CodeIntentConfiguration from(Map<String,String> values) { return from(values,Map.of()); }
    /** Additional locally held constructor/ring material, already decoded by its owning parser. */
    static CodeIntentConfiguration from(Map<String,String> values,Map<String,byte[]> localSecretMaterials) {
        Objects.requireNonNull(values); Objects.requireNonNull(localSecretMaterials);
        boolean enabled=flag(values,"ARMONEY_LOGIN_PROOF_ENABLED");
        for(String name:List.of("ARMONEY_SSO_MANAGED_PAIRS","ARMONEY_SSO_LEGACY_PAIRS")) if(values.containsKey(name)&&values.get(name)==null) CodeIntentPair.invalid();
        var managed=pairs(values.get("ARMONEY_SSO_MANAGED_PAIRS"));
        var legacy=pairs(values.get("ARMONEY_SSO_LEGACY_PAIRS"));
        if(managed.stream().anyMatch(legacy::contains)) CodeIntentPair.invalid();
        var all=new HashSet<>(managed); all.addAll(legacy);
        if(all.stream().map(CodeIntentPair::issuer).distinct().count()>16 || all.stream().map(CodeIntentPair::clientId).distinct().count()>32) CodeIntentPair.invalid();
        independent(values,localSecretMaterials);
        if(!enabled) return new CodeIntentConfiguration(false,managed,legacy);
        if(managed.isEmpty()) CodeIntentPair.invalid();
        String issuer=required(values,"SSO_ISSUER");
        var verification=csv(required(values,"SSO_VERIFICATION_CLIENT_IDS"),16,"[A-Za-z0-9_-]{1,128}");
        if(!PUBLIC_CLIENTS.containsAll(verification)) CodeIntentPair.invalid();
        for(var pair:managed)if(!PUBLIC_CLIENTS.contains(pair.clientId()))CodeIntentPair.invalid();
        for(var pair:all) if(!issuer.equals(pair.issuer()) || !(PUBLIC_CLIENTS.contains(pair.clientId())||legacy.contains(pair)&&MIGRATION_ACCOUNT_CLIENT.equals(pair.clientId()))) CodeIntentPair.invalid();
        for(var pair:managed) if(!verification.contains(pair.clientId())) CodeIntentPair.invalid();
        if(!"/sso/realms/armoney".equals(CodeIntentPair.parseUri(issuer).getPath())) CodeIntentPair.invalid();
        base(required(values,"ARMONEY_AUTH_BRIDGE_URL"));
        for(String name:List.of("SSO_LOGIN_PROOF_WRITE_KEY","SSO_LOGIN_PROOF_CONFIRM_KEY","SSO_CODE_INTENT_LOOKUP_KEY")) credential(required(values,name));
        String writer=required(values,"SSO_LOGIN_PROOF_WRITER_ID");
        if(!csv(required(values,"SSO_CODE_INTENT_ACTORS"),16,"[A-Za-z0-9_-]{1,128}").contains(writer)) CodeIntentPair.invalid();
        var keyIds=csv(required(values,"SSO_CODE_INTENT_CODE_KEY_IDS"),2,"[A-Za-z0-9_-]{1,32}");
        for(String id:keyIds) if(!localSecretMaterials.containsKey(id)||localSecretMaterials.get(id).length!=32) CodeIntentPair.invalid();
        String bindingKey=required(values,"ARMONEY_PROVIDER_BINDING_KEY");
        if(bindingKey.length()<32||bindingKey.length()>512||bindingKey.chars().anyMatch(c->c<33||c>126)) CodeIntentPair.invalid();
        projections(values,"SSO_CODE_INTENT_ISSUERS",managed.stream().map(CodeIntentPair::issuer).collect(java.util.stream.Collectors.toSet()),512);
        projections(values,"SSO_CODE_INTENT_CLIENTS",managed.stream().map(CodeIntentPair::clientId).collect(java.util.stream.Collectors.toSet()),128);
        return new CodeIntentConfiguration(true,managed,legacy);
    }
    boolean enabled() { return enabled; }
    Set<CodeIntentPair> managedPairs() { return managed; }
    Set<CodeIntentPair> legacyPairs() { return legacy; }
    boolean managed(String issuer,String client) { return enabled && managed.contains(new CodeIntentPair(issuer,client)); }
    private static boolean flag(Map<String,String> values,String name) {
        if(!values.containsKey(name)) return false;
        String value=values.get(name); if(!"true".equals(value)&&!"false".equals(value)) CodeIntentPair.invalid();
        return "true".equals(value);
    }
    private static Set<CodeIntentPair> pairs(String json) {
        if(json==null) return Set.of();
        // JSON structural whitespace is legal; unpaired Java surrogates are not UTF-8.
        for(int i=0;i<json.length();i++) if(Character.isSurrogate(json.charAt(i))) {
                char c=json.charAt(i); if(!Character.isHighSurrogate(c)||++i>=json.length()||!Character.isLowSurrogate(json.charAt(i))) CodeIntentPair.invalid();
        }
        if(json.getBytes(StandardCharsets.UTF_8).length>16384) CodeIntentPair.invalid();
        try {
            var mapper=JsonSerialization.mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root=mapper.readTree(json);
            if(root==null||!root.isArray()||root.size()>16) CodeIntentPair.invalid();
            Set<CodeIntentPair> result=new HashSet<>();
            for(JsonNode item:root) {
                if(!item.isObject()||item.size()!=2||!item.path("issuer").isTextual()||!item.path("client_id").isTextual()) CodeIntentPair.invalid();
                if(!result.add(new CodeIntentPair(item.get("issuer").textValue(),item.get("client_id").textValue()))) CodeIntentPair.invalid();
            }
            return Set.copyOf(result);
        } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid code-intent configuration"); }
    }
    private static String required(Map<String,String> values,String name) {
        String value=values.get(name); if(value==null||value.isEmpty()) CodeIntentPair.invalid(); return value;
    }
    private static Set<String> csv(String value,int maximum,String pattern) {
        Set<String> result=new HashSet<>();
        for(String item:value.split(",",-1)) if(!item.matches(pattern)||!result.add(item)) CodeIntentPair.invalid();
        if(result.isEmpty()||result.size()>maximum) CodeIntentPair.invalid(); return result;
    }
    private static void projections(Map<String,String> values,String name,Set<String> expected,int length) {
        if(!values.containsKey(name)) return;
        var actual=csv(required(values,name),16,"[^\\s\\p{Cntrl}]{1,"+length+"}");
        if(!actual.equals(expected)) CodeIntentPair.invalid();
    }
    private static void credential(String value) { if(value.length()<32||value.length()>256||value.chars().anyMatch(c->c<33||c>126)) CodeIntentPair.invalid(); }
    private static void base(String value) {
        URI uri=CodeIntentPair.parseUri(value);
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||!(uri.getPath().isEmpty()||uri.getPath().equals("/"))||uri.getPort()==0||uri.getPort() < -1||uri.getPort()>65535
                ||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&"auth-service".equals(uri.getHost()))) CodeIntentPair.invalid();
    }
    private static void independent(Map<String,String> values,Map<String,byte[]> additional) {
        List<byte[]> seen=new ArrayList<>();
        for(var entry:values.entrySet()) {
            String name=entry.getKey(); String value=entry.getValue();
            if(value!=null && (name.endsWith("_KEY")||name.endsWith("_PASSWORD")||name.endsWith("_SECRET")||name.endsWith("_TOKEN"))) addSecret(seen,value.getBytes(StandardCharsets.UTF_8));
        }
        for(var material:additional.entrySet()) {
            if(!CodeIntentPair.safe(material.getKey(),128)||material.getValue()==null||material.getValue().length==0||material.getValue().length>4096) CodeIntentPair.invalid();
            addSecret(seen,material.getValue().clone());
        }
    }
    private static void addSecret(List<byte[]> seen,byte[] raw) {
        List<byte[]> variants=new ArrayList<>(); variants.add(raw);
        String value=new String(raw,StandardCharsets.UTF_8);
        for(boolean url:List.of(false,true)) try {
            var decoder=url?Base64.getUrlDecoder():Base64.getDecoder();var encoder=url?Base64.getUrlEncoder():Base64.getEncoder();
            byte[] decoded=decoder.decode(value);
            if(decoded.length==32&&(encoder.encodeToString(decoded).equals(value)||encoder.withoutPadding().encodeToString(decoded).equals(value)))
                if(variants.stream().noneMatch(previous->MessageDigest.isEqual(previous,decoded)))variants.add(decoded);
        }catch(IllegalArgumentException notBase64){ }
        for(byte[] variant:variants) for(byte[] previous:seen) if(MessageDigest.isEqual(variant,previous)) CodeIntentPair.invalid();
        seen.addAll(variants);
    }
    @Override public String toString() { return "CodeIntentConfiguration[REDACTED]"; }
}
