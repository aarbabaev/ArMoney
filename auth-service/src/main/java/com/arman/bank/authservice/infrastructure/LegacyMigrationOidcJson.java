package com.arman.bank.authservice.infrastructure;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict byte-level policy screening precedes Nimbus JOSE processing. */
final class LegacyMigrationOidcJson {
    private static final ObjectMapper JSON=mapper(12288),TOKEN_JSON=mapper(16384);
    private static ObjectMapper mapper(int maxString){return new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(maxString).maxNumberLength(20).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    static JsonNode object(byte[] bytes, int limit) {
        return object(bytes,limit,JSON);
    }
    static JsonNode tokenResponse(byte[] bytes){return object(bytes,65536,TOKEN_JSON);}
    private static JsonNode object(byte[] bytes,int limit,ObjectMapper parserMapper){
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > limit || (bytes[0] & 255) == 239) throw denied();
            // REPORT rather than replacing malformed UTF-8; Jackson also rejects malformed JSON.
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
            try(JsonParser parser=parserMapper.getFactory().createParser(bytes)){
                while(parser.nextToken()!=null){
                    if(parser.currentName()!=null&&Set.of("auth_time","iat","exp","nbf").contains(parser.currentName())&&parser.currentToken().isNumeric()
                        &&!parser.getText().matches("0|[1-9][0-9]{0,18}"))throw denied();
                }
            }
            JsonNode value = parserMapper.readTree(bytes);
            if (value == null || !value.isObject()) throw denied();
            validStrings(value);
            return value;
        } catch (Exception ignored) { throw denied(); }
    }
    private static void validStrings(JsonNode node) {
        if(node.isTextual()) validString(node.textValue());
        else if(node.isObject()) node.fields().forEachRemaining(entry->{validString(entry.getKey());validStrings(entry.getValue());});
        else if(node.isArray()) node.elements().forEachRemaining(LegacyMigrationOidcJson::validStrings);
    }
    private static void validString(String s){
        for(int i=0;i<s.length();i++)if(Character.isSurrogate(s.charAt(i))){
            if(!Character.isHighSurrogate(s.charAt(i))||++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))throw denied();
        }
    }
    static byte[] segment(String raw) {
        try {
            if (raw == null || raw.isEmpty() || raw.length()>21846 || !raw.matches("[A-Za-z0-9_-]+")) throw denied();
            byte[] value=Base64.getUrlDecoder().decode(raw);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(value).equals(raw)) throw denied();
            return value;
        } catch (Exception ignored) { throw denied(); }
    }
    static String text(JsonNode object,String field,int max) {
        JsonNode value=object.get(field);
        if (value==null || !value.isTextual() || value.textValue().isEmpty() || value.textValue().length()>max
            || value.textValue().chars().anyMatch(Character::isISOControl)) throw denied();
        return value.textValue();
    }
    static long seconds(JsonNode object,String field) {
        JsonNode value=object.get(field);
        if(value==null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<0) throw denied();
        return value.longValue();
    }
    static void fields(JsonNode object,Set<String> allowed) {
        object.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw denied();});
    }
    static LegacyMigrationOidcClient.Unavailable denied(){return new LegacyMigrationOidcClient.Unavailable();}
    private LegacyMigrationOidcJson(){}
}
