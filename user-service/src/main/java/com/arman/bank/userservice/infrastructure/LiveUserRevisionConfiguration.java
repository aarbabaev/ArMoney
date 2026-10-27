package com.arman.bank.userservice.infrastructure;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Explicit default OFF. No route registration, source inference or client startup. */
public record LiveUserRevisionConfiguration(boolean enabled,Map<String,List<String>> currentWriters,URI authOrigin,String mutationWriter,String mutationKey) {
    public LiveUserRevisionConfiguration {
        var copy=new LinkedHashMap<String,List<String>>();if(currentWriters==null||currentWriters.size()>2)throw invalid();
        currentWriters.forEach((writer,keys)->{writer(writer);if(keys==null||keys.isEmpty()||keys.size()>2)throw invalid();keys.forEach(LiveUserRevisionConfiguration::key);copy.put(writer,List.copyOf(keys));});currentWriters=Map.copyOf(copy);
        if(enabled){if(currentWriters.isEmpty())throw invalid();writer(mutationWriter);key(mutationKey);origin(authOrigin);
            var known=new HashSet<String>();for(var keys:currentWriters.values())for(String k:keys)add(known,k);add(known,mutationKey);}
    }
    public static LiveUserRevisionConfiguration from(Map<String,String> env){
        String flag=env.getOrDefault("USER_LIVE_REVISION_ENABLED","false");if(!Set.of("true","false").contains(flag))throw invalid();
        if(flag.equals("false"))return new LiveUserRevisionConfiguration(false,Map.of(),null,null,null);
        String current=env.get("AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY"),previous=env.get("AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY_PREVIOUS"),mutation=env.get("USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY");
        var keys=new ArrayList<String>();keys.add(current);if(previous!=null)keys.add(previous);
        var config=new LiveUserRevisionConfiguration(true,Map.of(env.get("USER_LIVE_CURRENT_WRITER_ID"),keys),URI.create(env.get("AUTH_MIGRATION_PRIMARY_ORIGIN")),env.get("USER_LIVE_MUTATION_WRITER_ID"),mutation);
        var own=new HashSet<String>();keys.forEach(k->own.addAll(aliases(k)));own.addAll(aliases(mutation));
        for(var e:env.entrySet())if((e.getKey().contains("KEY")||e.getKey().contains("SECRET")||e.getKey().contains("PASSWORD"))&&!Set.of("AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY","AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY_PREVIOUS","USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY").contains(e.getKey())&&e.getValue()!=null&&!Collections.disjoint(own,aliases(e.getValue())))throw invalid();
        return config;
    }
    private static void writer(String s){if(s==null||!s.matches("[A-Za-z0-9._-]{1,64}"))throw invalid();}
    private static void key(String s){if(s==null||!s.matches("[!-~]{32,256}"))throw invalid();}
    private static void origin(URI u){if(u==null||!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/")))throw invalid();}
    private static Set<String> aliases(String key){var set=new HashSet<String>();set.add(Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)));for(var d:List.of(Base64.getDecoder(),Base64.getUrlDecoder()))try{set.add(Base64.getEncoder().encodeToString(d.decode(key)));}catch(IllegalArgumentException ignored){}return set;}
    private static void add(Set<String> known,String key){var aliases=aliases(key);if(!Collections.disjoint(known,aliases))throw invalid();known.addAll(aliases);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Live User configuration rejected");}
    @Override public String toString(){return "LiveUserRevisionConfiguration[enabled="+enabled+",credentials=redacted]";}
}
