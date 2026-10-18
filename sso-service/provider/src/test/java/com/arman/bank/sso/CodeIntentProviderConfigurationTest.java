package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodeIntentProviderConfigurationTest {
    private static final String ISSUER="https://issuer.example/sso/realms/armoney";
    private static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    private static String pair(String client){return "{\"issuer\":\""+ISSUER+"\",\"client_id\":\""+client+"\"}";}
    private static String target(String client,String redirect,String purpose){return "{\"issuer\":\""+ISSUER+"\",\"client_id\":\""+client+"\",\"redirect_uri\":\""+redirect+"\",\"purpose\":\""+purpose+"\"}";}
    static Map<String,String> valid(){
        var env=new HashMap<String,String>();
        env.put("ARMONEY_LOGIN_PROOF_ENABLED","true");env.put("SSO_ISSUER",ISSUER);
        env.put("ARMONEY_SSO_MANAGED_PAIRS","["+pair("armoney-android")+"]");env.put("ARMONEY_SSO_LEGACY_PAIRS","["+pair("armoney-web")+"]");
        env.put("SSO_VERIFICATION_CLIENT_IDS","armoney-android");env.put("ARMONEY_AUTH_BRIDGE_URL","http://auth-service:8081");
        env.put("SSO_LOGIN_PROOF_WRITE_KEY","w".repeat(40));env.put("SSO_LOGIN_PROOF_CONFIRM_KEY","c".repeat(40));env.put("SSO_CODE_INTENT_LOOKUP_KEY","l".repeat(40));env.put("ARMONEY_PROVIDER_BINDING_KEY","b".repeat(40));
        env.put("SSO_LOGIN_PROOF_WRITER_ID","provider");env.put("SSO_CODE_INTENT_ACTORS","provider");env.put("SSO_CODE_INTENT_CODE_KEY_IDS","current");
        env.put(CodeIntentProviderConfiguration.KEYS,"{\"current\":\""+KEY+"\"}");
        env.put(CodeIntentProviderConfiguration.MANIFEST,"["+target("armoney-android","com.armoney.android:/oauth/callback","BANK_NATIVE")+","+target("armoney-android","https://bank.example/migration/managed/callback","MIGRATION_MANAGED")+","+target("armoney-web","https://bank.example/migration/legacy/account-oidc/callback","MIGRATION_ACCOUNT")+"]");return env;
    }
    @Test void offDoesNotParseOrRetainDependencies(){
        assertFalse(CodeIntentProviderConfiguration.from(Map.of()).enabled());
        assertFalse(CodeIntentProviderConfiguration.from(Map.of(CodeIntentProviderConfiguration.FLAG,"false",CodeIntentProviderConfiguration.KEYS,"invalid")).enabled());
        for(String flag:List.of("TRUE","1"," true","false ",""))assertThrows(IllegalArgumentException.class,()->CodeIntentProviderConfiguration.from(Map.of(CodeIntentProviderConfiguration.FLAG,flag)));
    }
    @Test void exactOriginalRedirectSeparatesManagedNativeAndMigration(){
        var env=valid();env.put("UNRELATED_EMPTY","");var config=CodeIntentProviderConfiguration.from(env);
        assertEquals(CodeIntentProviderConfiguration.Purpose.BANK_NATIVE,config.purpose(ISSUER,"armoney-android","com.armoney.android:/oauth/callback"));
        assertEquals(CodeIntentProviderConfiguration.Purpose.MIGRATION_MANAGED,config.purpose(ISSUER,"armoney-android","https://bank.example/migration/managed/callback"));
        assertEquals(CodeIntentProviderConfiguration.Purpose.MIGRATION_ACCOUNT,config.purpose(ISSUER,"armoney-web","https://bank.example/migration/legacy/account-oidc/callback"));
        assertFalse(config.bankClient(ISSUER+"/","armoney-android"));
        assertThrows(IllegalArgumentException.class,()->config.purpose(ISSUER,"armoney-android","https://bank.example/migration/managed/callback/"));
        byte[] returned=config.keyMaterial().get("current");returned[0]=7;assertEquals(0,config.keyMaterial().get("current")[0]);
        env.clear();assertTrue(config.enabled());assertThrows(UnsupportedOperationException.class,()->config.environment().clear());
    }
    @Test void ringRejectsDuplicateFieldsNoncanonicalMaterialAndCredentialAliases(){
        for(String ring:List.of("{}","null","[]","{\"current\":null}","{\"current\":\""+KEY+"\",\"current\":\""+KEY+"\"}","{\"current\":\""+KEY+"\"} true","{\"current\":\""+KEY.substring(0,43)+"\"}","{\"other\":\""+KEY+"\"}"))reject(CodeIntentProviderConfiguration.KEYS,ring);
        var env=valid();env.put("SSO_CODE_INTENT_CODE_KEY_IDS","current,previous");env.put(CodeIntentProviderConfiguration.KEYS,"{\"current\":\""+KEY+"\",\"previous\":\""+KEY+"\"}");assertThrows(IllegalArgumentException.class,()->CodeIntentProviderConfiguration.from(env));
        reject("SSO_LOGIN_PROOF_WRITE_KEY",KEY);
    }
    @Test void manifestRejectsDuplicateTrailingWrongTypesAndUnsupportedPurpose(){
        String entry=target("armoney-android","com.armoney.android:/oauth/callback","BANK_NATIVE");
        for(String manifest:List.of("null","{}","[]","[null]","["+entry+"] true","["+entry+","+entry+"]","["+entry.replace("\"purpose\":","\"extra\":0,\"purpose\":")+"]","["+entry.replace("\"BANK_NATIVE\"","true")+"]","["+entry.replace("\"BANK_NATIVE\"","\"bank_native\"")+"]","["+entry.replace("\"purpose\":","\"purpose\":\"BANK_NATIVE\",\"purpose\":")+"]"))reject(CodeIntentProviderConfiguration.MANIFEST,manifest);
    }
    @Test void redirectAndPairClassificationNeverNormalizeOrBroaden(){
        for(String redirect:List.of("com.armoney.android:/oauth/callback/","com.armoney.android:/oauth/callback?x=1","com.armoney.android:/oauth/%63allback","com.armoney.android:/oauth/../oauth/callback","com.armoney.android:/oauth/*","https://bank.example/oauth/callback"))reject(CodeIntentProviderConfiguration.MANIFEST,"["+target("armoney-android",redirect,"BANK_NATIVE")+"]");
        reject(CodeIntentProviderConfiguration.MANIFEST,"["+target("armoney-web","https://bank.example/oauth/callback","BANK_NATIVE")+"]");
        reject(CodeIntentProviderConfiguration.MANIFEST,"["+target("armoney-android","https://bank.example/migration/legacy/account-oidc/callback","MIGRATION_ACCOUNT")+"]");
    }
    @Test void invalidUtf8BoundsAndDiagnosticsNeverRetainInputs(){
        for(String value:List.of("\ufeff{}","\ud800"," ".repeat(16385)))reject(CodeIntentProviderConfiguration.MANIFEST,value);
        var env=valid();env.put(CodeIntentProviderConfiguration.MANIFEST,"secret-invalid-input");var failure=assertThrows(IllegalArgumentException.class,()->CodeIntentProviderConfiguration.from(env));
        assertEquals("Code-intent provider configuration rejected",failure.getMessage());assertNull(failure.getCause());assertFalse(failure.toString().contains("secret-invalid-input"));assertFalse(CodeIntentProviderConfiguration.from(valid()).toString().contains(KEY));
    }
    @Test void unknownPluralCredentialContainersFailClosedAndAllBase64AliasesReject(){
        for(String suffix:List.of("_KEYS","_SECRETS","_TOKENS","_PASSWORDS","_KEYRING","_KEY_RING")){
            reject("OTHER"+suffix,"unparsed-container");var env=valid();env.put("OTHER"+suffix,"");assertTrue(CodeIntentProviderConfiguration.from(env).enabled());
        }
        byte[] raw=new byte[32];Arrays.fill(raw,(byte)0xff);var env=valid();env.put(CodeIntentProviderConfiguration.KEYS,"{\"current\":\""+Base64.getEncoder().encodeToString(raw)+"\"}");
        for(var encoder:List.of(Base64.getEncoder(),Base64.getEncoder().withoutPadding(),Base64.getUrlEncoder(),Base64.getUrlEncoder().withoutPadding())){
            env.put("OTHER_KEY",encoder.encodeToString(raw));assertThrows(IllegalArgumentException.class,()->CodeIntentProviderConfiguration.from(env));
        }
    }
    @Test void dedicatedAccountClientHasOnlyExactMigrationAccountPurpose(){
        var env=accountEnvironment();var parsed=CodeIntentProviderConfiguration.from(env);
        assertEquals(CodeIntentProviderConfiguration.Purpose.MIGRATION_ACCOUNT,parsed.purpose(ISSUER,"armoney-migration-account","https://bank.example/migration/legacy/account-oidc/callback"));
        assertFalse(parsed.bankClient(ISSUER,"armoney-migration-account"));assertFalse(parsed.policy().managed(ISSUER,"armoney-migration-account"));
        assertTrue(parsed.purposeClient(ISSUER,"armoney-migration-account"));assertFalse(parsed.purposeClient(ISSUER+"/","armoney-migration-account"));
        for(String purpose:List.of("BANK_NATIVE","LEGACY_STOCK","MIGRATION_MANAGED")){
            var invalid=accountEnvironment();invalid.put(CodeIntentProviderConfiguration.MANIFEST,invalid.get(CodeIntentProviderConfiguration.MANIFEST).replace("\"purpose\":\"MIGRATION_ACCOUNT\"","\"purpose\":\""+purpose+"\""));assertThrows(IllegalArgumentException.class,()->CodeIntentProviderConfiguration.from(invalid));
        }
        assertThrows(IllegalArgumentException.class,()->parsed.purpose(ISSUER,"armoney-migration-account","https://bank.example/migration/legacy/account-oidc/callback/"));
    }
    static Map<String,String> accountEnvironment(){var env=valid();env.put("ARMONEY_SSO_LEGACY_PAIRS","["+pair("armoney-migration-account")+"]");env.put(CodeIntentProviderConfiguration.MANIFEST,"["+target("armoney-android","com.armoney.android:/oauth/callback","BANK_NATIVE")+","+target("armoney-migration-account","https://bank.example/migration/legacy/account-oidc/callback","MIGRATION_ACCOUNT")+"]");return env;}
    private static void reject(String key,String value){var env=valid();env.put(key,value);assertThrows(IllegalArgumentException.class,()->CodeIntentProviderConfiguration.from(env));}
}
