package com.arman.bank.userservice.infrastructure;
import java.net.URI;
import java.util.*;
/** Explicit default-off resource configuration. Never starts workers or registers routes. */
public record MigrationBaselineConfiguration(boolean enabled,Map<String,List<String>> stageWriters,
 Map<String,List<String>> publicationWriters,URI authPrimary,String decisionKey) {
 public MigrationBaselineConfiguration {
  stageWriters=copy(stageWriters); publicationWriters=copy(publicationWriters);
  if(enabled&&(stageWriters.isEmpty()||!stageWriters.keySet().equals(publicationWriters.keySet()))) throw new IllegalArgumentException("Migration writer configuration");
  var keys=new HashSet<String>();
  for(var role:List.of(stageWriters,publicationWriters)) for(var values:role.values()) for(String key:values)
   addKey(keys,key);
  if(enabled) {
   validKey(decisionKey); addKey(keys,decisionKey);
   if(authPrimary==null||!Set.of("http","https").contains(authPrimary.getScheme())||authPrimary.getHost()==null
     ||authPrimary.getUserInfo()!=null||authPrimary.getQuery()!=null||authPrimary.getFragment()!=null
     ||!(authPrimary.getPath().isEmpty()||authPrimary.getPath().equals("/"))) throw new IllegalArgumentException("Fixed Auth PRIMARY origin required");
  }
 }
 private static Map<String,List<String>> copy(Map<String,List<String>> source) {
  if(source==null||source.size()>2) throw new IllegalArgumentException("Migration writers");
  var result=new LinkedHashMap<String,List<String>>();
  source.forEach((writer,keys)->{
   if(writer==null||!writer.matches("[A-Za-z0-9._-]{1,64}")||keys==null||keys.isEmpty()||keys.size()>2) throw new IllegalArgumentException("Migration writers");
   keys.forEach(MigrationBaselineConfiguration::validKey); result.put(writer,List.copyOf(keys));
  }); return Map.copyOf(result);
 }
 private static void validKey(String key) { if(key==null||!key.matches("[!-~]{32,256}")) throw new IllegalArgumentException("Migration key configuration"); }
 private static Set<String> aliases(String key) {
  var result=new HashSet<String>();
  result.add(Base64.getEncoder().encodeToString(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  for(var decoder:List.of(Base64.getDecoder(),Base64.getUrlDecoder())) {
   try { result.add(Base64.getEncoder().encodeToString(decoder.decode(key))); } catch(IllegalArgumentException ignored) { }
  }
  return result;
 }
 private static void addKey(Set<String> known,String key) {
  var values=aliases(key); if(values.stream().anyMatch(known::contains)) throw new IllegalArgumentException("Migration key alias");
  known.addAll(values);
 }
 public static MigrationBaselineConfiguration from(Map<String,String> env) {
  String enabled=env.getOrDefault("USER_MIGRATION_BASELINE_ENABLED","false");
  if(!Set.of("true","false").contains(enabled)) throw new IllegalArgumentException("Migration enabled flag");
  if(enabled.equals("false")) return new MigrationBaselineConfiguration(false,Map.of(),Map.of(),null,null);
  String writer=env.get("USER_MIGRATION_WRITER_ID");
  var stage=keys(env,"AUTH_TO_USER_MIGRATION_STAGE_KEY");
  var publication=keys(env,"AUTH_TO_USER_MIGRATION_PUBLICATION_KEY");
  String decision=env.get("USER_TO_AUTH_MIGRATION_DECISION_KEY");
  var own=new HashSet<String>(); own.addAll(stage); own.addAll(publication); own.add(decision);
  for(var e:env.entrySet()) if((e.getKey().contains("KEY")||e.getKey().contains("SECRET")||e.getKey().contains("PASSWORD"))
    &&!Set.of("AUTH_TO_USER_MIGRATION_STAGE_KEY","AUTH_TO_USER_MIGRATION_STAGE_KEY_PREVIOUS","AUTH_TO_USER_MIGRATION_PUBLICATION_KEY","AUTH_TO_USER_MIGRATION_PUBLICATION_KEY_PREVIOUS","USER_TO_AUTH_MIGRATION_DECISION_KEY").contains(e.getKey())
    &&e.getValue()!=null&&own.stream().filter(Objects::nonNull).anyMatch(key->!Collections.disjoint(aliases(key),aliases(e.getValue())))) throw new IllegalArgumentException("Migration credential must be distinct");
  String origin=env.get("AUTH_MIGRATION_PRIMARY_ORIGIN"); if(origin==null) throw new IllegalArgumentException("Missing Auth PRIMARY origin");
  return new MigrationBaselineConfiguration(true,Map.of(writer,stage),Map.of(writer,publication),URI.create(origin),decision);
 }
 private static List<String> keys(Map<String,String> env,String name) {
  var result=new ArrayList<String>(); result.add(env.get(name));
  if(env.containsKey(name+"_PREVIOUS")) result.add(env.get(name+"_PREVIOUS")); return result;
 }
 @Override public String toString() { return "MigrationBaselineConfiguration[enabled="+enabled+",credentials=redacted]"; }
}
