package com.arman.bank.authservice.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded exact configuration relation. Projections are validation aids, never authorization. */
public final class CodeIntentPairScopes {
    private static final ObjectMapper JSON=new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public enum Classification { MANAGED, LEGACY, UNKNOWN }
    public record Pair(String issuer,String clientId) {
        public Pair { validate(issuer,clientId); }
        @Override public String toString(){return "CodeIntentPair[redacted]";}
    }
    private final Set<Pair> managed,legacy;
    public CodeIntentPairScopes(String managedJson,String legacyJson){
        managed=parse(managedJson);legacy=parse(legacyJson);
        if(!Collections.disjoint(managed,legacy))throw invalid();
        var all=new HashSet<>(managed);all.addAll(legacy);
        if(all.stream().map(Pair::issuer).distinct().count()>16||all.stream().map(Pair::clientId).distinct().count()>32)throw invalid();
    }
    public Set<Pair> managed(){return managed;}
    public Set<Pair> legacy(){return legacy;}
    public Set<String> managedIssuers(){return projection(true);}
    public Set<String> managedClients(){return projection(false);}
    private Set<String> projection(boolean issuer){
        var values=new TreeSet<String>();for(var pair:managed)values.add(issuer?pair.issuer():pair.clientId());return Collections.unmodifiableSet(values);
    }
    public Classification classify(String issuer,String client){
        try{var pair=new Pair(issuer,client);return managed.contains(pair)?Classification.MANAGED:legacy.contains(pair)?Classification.LEGACY:Classification.UNKNOWN;}
        catch(IllegalArgumentException unknown){return Classification.UNKNOWN;}
    }
    public void requireManaged(String issuer,String client){if(classify(issuer,client)!=Classification.MANAGED)throw invalid();}
    private static Set<Pair> parse(String raw){
        if(raw==null)throw invalid();
        if(raw.getBytes(StandardCharsets.UTF_8).length>16384||raw.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff))throw invalid();
        try{
            var array=JSON.readTree(raw);
            if(array==null||!array.isArray()||array.size()>16)throw invalid();
            var values=new HashSet<Pair>();
            for(var node:array){
                if(!node.isObject()||node.size()!=2||!node.has("issuer")||!node.has("client_id")
                    ||!node.get("issuer").isTextual()||!node.get("client_id").isTextual())throw invalid();
                if(!values.add(new Pair(node.get("issuer").textValue(),node.get("client_id").textValue())))throw invalid();
            }
            return Set.copyOf(values);
        }catch(java.io.IOException|IllegalArgumentException bad){throw invalid();}
    }
    private static void validate(String issuer,String client){
        if(!bounded(issuer,512)||!bounded(client,128)||!client.matches("[A-Za-z0-9_-]{1,128}")||"armoney-migration".equals(client))throw invalid();
        try{var uri=URI.create(issuer);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
            ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||issuer.endsWith("/")||uri.getPort()==0||uri.getPort()>65535)throw invalid();}
        catch(IllegalArgumentException bad){throw invalid();}
    }
    private static boolean bounded(String text,int length){return text!=null&&!text.isBlank()&&text.length()<=length
        &&text.codePoints().noneMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff));}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid code intent pair scope");}
    @Override public String toString(){return "CodeIntentPairScopes[redacted]";}
}
