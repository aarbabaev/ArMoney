package com.arman.bank.authservice.infrastructure;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure default-OFF selection. Does not allocate clients, pools, workers or routes. */
public record MigrationUserRevisionConfiguration(boolean enabled,URI userOrigin,String currentWriter,String currentKey,
        String previousCurrentKey,String mutationWriter,String mutationKey) {
    public MigrationUserRevisionConfiguration {
        if(enabled){origin(userOrigin);writer(currentWriter);writer(mutationWriter);key(currentKey);key(mutationKey);
            if(previousCurrentKey!=null)key(previousCurrentKey);
            var seen=new HashSet<String>();add(seen,currentKey);if(previousCurrentKey!=null)add(seen,previousCurrentKey);add(seen,mutationKey);}
    }
    public static MigrationUserRevisionConfiguration from(Map<String,String> environment){
        try{
            String flag=environment.getOrDefault("AUTH_MIGRATION_USER_REVISION_ENABLED","false");
            if(!Set.of("true","false").contains(flag))throw invalid();
            if(flag.equals("false"))return new MigrationUserRevisionConfiguration(false,null,null,null,null,null,null);
            var selected=new MigrationUserRevisionConfiguration(true,URI.create(environment.get("USER_BASE_URL")),
                environment.get("AUTH_MIGRATION_USER_REVISION_WRITER"),environment.get("AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY"),
                environment.get("AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY_PREVIOUS"),environment.get("AUTH_MIGRATION_USER_MUTATION_WRITER_ID"),
                environment.get("USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY"));
            var own=new HashSet<String>();own.addAll(aliases(selected.currentKey));if(selected.previousCurrentKey!=null)own.addAll(aliases(selected.previousCurrentKey));own.addAll(aliases(selected.mutationKey));
            var roles=Set.of("AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY","AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY_PREVIOUS","USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY");
            for(var entry:environment.entrySet()){
                String name=entry.getKey();String value=entry.getValue();
                if(value==null||roles.contains(name)||!(name.contains("KEY")||name.contains("SECRET")||name.contains("PASSWORD")))continue;
                if(!Collections.disjoint(own,aliases(value)))throw invalid();
                if(name.endsWith("_PREVIOUS_KEYS")&&!value.isEmpty()){
                    if(!Set.of("SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS","SSO_CODE_INTENT_DETAIL_ENCRYPTION_PREVIOUS_KEYS","AUTH_MANAGED_BANKING_PREVIOUS_KEYS").contains(name))throw invalid();
                    String[] members=value.split(",",-1);if(members.length>8)throw invalid();var ids=new HashSet<String>();
                    for(String member:members){String[] pair=member.split(":",2);if(pair.length!=2||!pair[0].matches("[A-Za-z0-9_-]{1,32}")||!ids.add(pair[0]))throw invalid();
                        byte[] decoded=Base64.getDecoder().decode(pair[1]);if(decoded.length!=32||!Base64.getEncoder().encodeToString(decoded).equals(pair[1])||!Collections.disjoint(own,aliases(pair[1])))throw invalid();}
                }
                // Retained JSON rings must be inspected entry-by-entry, never skipped as key metadata.
                if(value.startsWith("{")||value.startsWith("[")){
                    if(value.length()>65536)throw invalid();var json=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);inspect(json,own,0);
                }
            }
            return selected;
        }catch(Exception rejected){throw invalid();}
    }
    private static void inspect(JsonNode node,Set<String> own,int depth){
        if(node==null||depth>8)throw invalid();if(node.isTextual()){if(!Collections.disjoint(own,aliases(node.textValue())))throw invalid();}
        else if(node.isContainerNode())for(JsonNode child:node)inspect(child,own,depth+1);
    }
    private static void origin(URI u){if(u==null||!"http://user-service:8080".equals(u.toString()))throw invalid();}
    private static void writer(String s){if(s==null||!s.matches("[A-Za-z0-9_-]{1,64}"))throw invalid();}
    private static void key(String s){if(s==null||!s.matches("[!-~]{32,256}"))throw invalid();}
    private static Set<String> aliases(String s){
        var result=new HashSet<String>();result.add(Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)));
        for(var decoder:List.of(Base64.getDecoder(),Base64.getUrlDecoder()))try{
            byte[] decoded=decoder.decode(s);
            if(s.equals(Base64.getEncoder().encodeToString(decoded))||s.equals(Base64.getEncoder().withoutPadding().encodeToString(decoded))
                    ||s.equals(Base64.getUrlEncoder().encodeToString(decoded))||s.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)))
                result.add(Base64.getEncoder().encodeToString(decoded));
        }catch(IllegalArgumentException ignored){}
        return result;
    }
    private static void add(Set<String> seen,String value){var aliases=aliases(value);if(!Collections.disjoint(seen,aliases))throw invalid();seen.addAll(aliases);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Migration User revision configuration rejected");}
    @Override public String toString(){return "MigrationUserRevisionConfiguration[enabled="+enabled+",credentials=redacted]";}
}
