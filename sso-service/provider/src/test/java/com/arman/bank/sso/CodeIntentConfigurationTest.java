package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodeIntentConfigurationTest {
    private static final String ISSUER="https://issuer.example/sso/realms/armoney";
    private static String pair(String client) { return "{\"issuer\":\""+ISSUER+"\",\"client_id\":\""+client+"\"}"; }
    private static Map<String,String> valid() {
        var env=new HashMap<String,String>();
        env.put("ARMONEY_LOGIN_PROOF_ENABLED","true"); env.put("ARMONEY_SSO_MANAGED_PAIRS","["+pair("armoney-ios")+","+pair("armoney-android")+"]");
        env.put("SSO_ISSUER",ISSUER); env.put("SSO_VERIFICATION_CLIENT_IDS","armoney-ios,armoney-android");
        env.put("ARMONEY_AUTH_BRIDGE_URL","http://auth-service:8081");
        env.put("SSO_LOGIN_PROOF_WRITE_KEY","w".repeat(40)); env.put("SSO_LOGIN_PROOF_CONFIRM_KEY","c".repeat(40));
        env.put("SSO_CODE_INTENT_LOOKUP_KEY","l".repeat(40)); env.put("ARMONEY_PROVIDER_BINDING_KEY","b".repeat(40));
        env.put("SSO_LOGIN_PROOF_WRITER_ID","provider"); env.put("SSO_CODE_INTENT_ACTORS","provider,retained"); env.put("SSO_CODE_INTENT_CODE_KEY_IDS","current");
        return env;
    }
    private static Map<String,byte[]> material() { byte[] key=new byte[32]; Arrays.fill(key,(byte)7); return Map.of("current",key); }
    @Test void defaultsAllocateOnlyDormantConfigurationAndStrictFlagsReject() {
        assertFalse(CodeIntentConfiguration.from(Map.of()).enabled());
        for(String flag:List.of("TRUE","1"," true","false ","")) assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(Map.of("ARMONEY_LOGIN_PROOF_ENABLED",flag)));
    }
    @Test void dormantPairsStillRejectMalformedDuplicatesOverlapAndBounds() {
        for(String json:List.of("null","{}","[null]","[] true","["+pair("armoney-ios")+","+pair("armoney-ios")+"]",
                "[{\"issuer\":\""+ISSUER+"\",\"issuer\":\""+ISSUER+"\",\"client_id\":\"armoney-ios\"}]",
                "["+pair("with space")+"]","["+pair("armoney-ios").replace(ISSUER,ISSUER+"/")+"]"," ".repeat(16385)))
            assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(Map.of("ARMONEY_SSO_MANAGED_PAIRS",json)));
        assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(Map.of("ARMONEY_SSO_MANAGED_PAIRS","["+pair("armoney-ios")+"]","ARMONEY_SSO_LEGACY_PAIRS","["+pair("armoney-ios")+"]")));
    }
    @Test void enabledTwoClientPairsAreExactAndInputsAreNotMutated() {
        var env=valid(); var original=new HashMap<>(env);
        var result=CodeIntentConfiguration.from(env,material());
        assertTrue(result.enabled()); assertTrue(result.managed(ISSUER,"armoney-ios")); assertTrue(result.managed(ISSUER,"armoney-android"));
        assertFalse(result.managed(ISSUER,"armoney-web")); assertEquals(original,env);
        assertThrows(UnsupportedOperationException.class,()->result.managedPairs().clear());
        assertEquals("CodeIntentConfiguration[REDACTED]",result.toString());
    }
    @Test void enabledRequiresOwnMaterialScopeAndExactOldProjections() {
        for(String missing:List.of("SSO_ISSUER","SSO_VERIFICATION_CLIENT_IDS","SSO_LOGIN_PROOF_WRITE_KEY","SSO_LOGIN_PROOF_CONFIRM_KEY","SSO_CODE_INTENT_LOOKUP_KEY","SSO_LOGIN_PROOF_WRITER_ID","SSO_CODE_INTENT_ACTORS","SSO_CODE_INTENT_CODE_KEY_IDS","ARMONEY_PROVIDER_BINDING_KEY")) {
            var env=valid(); env.remove(missing); assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(env,material()),missing);
        }
        assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(valid()));
        var env=valid(); env.put("SSO_CODE_INTENT_CLIENTS","armoney-ios"); assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(env,material()));
        env.put("SSO_CODE_INTENT_CLIENTS","armoney-ios,armoney-android"); assertTrue(CodeIntentConfiguration.from(env,material()).enabled());
        env.put("SSO_ISSUER","https://other.example/sso/realms/armoney"); assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(env,material()));
    }
    @Test void rawAndDecodedLocalSecretsCannotAliasEvenWhenDormant() {
        var env=valid(); env.put("SSO_CODE_INTENT_LOOKUP_KEY",env.get("SSO_LOGIN_PROOF_WRITE_KEY"));
        assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(env,material()));
        var key=material().get("current");
        assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(Map.of("SYNTHETIC_KEY",Base64.getEncoder().encodeToString(key)),material()));
        assertFalse(CodeIntentConfiguration.from(Map.of(),material()).enabled());
    }
    @Test void malformedUrisNeverRetainInputOrCause() {
        String secret="https://user:synthetic-password@bad host/";
        var env=valid(); env.put("ARMONEY_AUTH_BRIDGE_URL",secret);
        var base=assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(env,material()));
        var pair=assertThrows(IllegalArgumentException.class,()->new CodeIntentPair(secret,"armoney-ios"));
        String malformed="https://user:synthetic-password@bad[host/";
        var syntax=assertThrows(IllegalArgumentException.class,()->new CodeIntentPair(malformed,"armoney-ios"));
        for(var failure:List.of(base,pair,syntax)) {
            assertEquals("Invalid code-intent configuration",failure.getMessage()); assertNull(failure.getCause());
            assertFalse(failure.toString().contains("synthetic-password")); assertFalse(failure.toString().contains("https://"));
        }
    }
    @Test void unicodeSpacesRejectWithoutRejectingOtherUnicodeText() {
        for(String space:List.of(" ","\t","\n","\u00a0","\u202f","\u2007")) {
            assertFalse(CodeIntentPair.safe("a"+space+"b",128));
            assertThrows(IllegalArgumentException.class,()->new CodeIntentPair(ISSUER+space,"armoney-ios"));
            assertThrows(IllegalArgumentException.class,()->new CodeIntentPair(ISSUER,"armoney"+space+"ios"));
        }
        assertTrue(CodeIntentPair.safe("café",128));
        assertEquals(ISSUER+"/café",new CodeIntentPair(ISSUER+"/café","armoney-ios").issuer());
    }
    @Test void injectedAndEnvironmentMaterialsNormalizeCrosswiseInBothDirections() {
        byte[] raw="z".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] encoded=Base64.getEncoder().encode(raw);
        String base64=new String(encoded,java.nio.charset.StandardCharsets.US_ASCII);
        assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(Map.of("SYNTHETIC_KEY","z".repeat(32)),Map.of("retained",encoded)));
        assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(Map.of("SYNTHETIC_KEY",base64),Map.of("retained",raw)));
        assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(Map.of(),Map.of("current",raw,"binding",encoded)));
        assertThrows(IllegalArgumentException.class,()->CodeIntentConfiguration.from(Map.of(),Map.of("current",encoded,"binding",raw)));
        assertFalse(CodeIntentConfiguration.from(Map.of(),Map.of("current",raw)).enabled());
    }
}
