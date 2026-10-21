package com.arman.bank.userservice.domain;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Private correlation values, never a grant or proof of non-migration. */
public final class LiveUserRevision {
    private LiveUserRevision() {}
    public static final List<String> USER = List.of("profile_id","shard_id","baseline_generation","contact_mutation_revision","projection_present","projection_revision","phone_contact_version","verification_epoch");
    public static final List<String> BASELINE = List.of("migration_id","decision_id","publication_revision","final_ack_hash");
    public static final List<String> AFTER = List.of("expected_version","version","phone_contact_version","verification_epoch","projection_payload_hash");
    public static final List<String> CURRENT = List.of("protocol_version","operation_id","identity_id","migration_id","decision_id","classification_generation","auth_epoch","auth_source_revision","payload_hash");
    public static final List<String> SUSPEND = List.of("protocol_version","operation_id","identity_id","baseline","before","after","payload_hash");
    public static final List<String> RESPONSE = List.of("protocol_version","operation_id","identity_id","payload_hash","outcome","binding","recorded_at_us");
    public static final List<String> BINDING = List.of("source_kind","migration_id","decision_id","classification_generation","previous_epoch","previous_source_revision","resulting_epoch","resulting_source_revision");
    public static Map<String,Object> user(Map<String,Object> input) {
        var m=ordered(input,USER); uuid(m.get("profile_id"));
        if(!(m.get("shard_id") instanceof String s)||!s.matches("[A-Za-z0-9_-]{1,64}")) throw malformed();
        integer(m.get("baseline_generation"),true); integer(m.get("contact_mutation_revision"),true);
        if(!(m.get("projection_present") instanceof Boolean present)) throw malformed();
        for(String k:List.of("projection_revision","phone_contact_version","verification_epoch")) {
            if(present) integer(m.get(k),false); else if(m.get(k)!=null) throw malformed();
        }
        return m;
    }
    public static Map<String,Object> baseline(Map<String,Object> input) {
        if(input==null) return null;
        var m=ordered(input,BASELINE); uuid(m.get("migration_id")); uuid(m.get("decision_id")); integer(m.get("publication_revision"),false); digest(m.get("final_ack_hash")); return m;
    }
    public static Map<String,Object> after(Map<String,Object> input) {
        var m=ordered(input,AFTER); long expected=integer(m.get("expected_version"),true);
        if(integer(m.get("version"),false)!=Math.addExact(expected,1L)) throw malformed();
        integer(m.get("phone_contact_version"),false); integer(m.get("verification_epoch"),false); digest(m.get("projection_payload_hash")); return m;
    }
    public static Map<String,Object> projection(VerificationProjection c) {
        var m=new LinkedHashMap<String,Object>(); m.put("expected_version",(long)c.expectedVersion()); m.put("version",(long)c.version());
        m.put("phone_contact_version",(long)c.phoneContactVersion()); m.put("verification_epoch",c.verificationEpoch()); m.put("projection_payload_hash",c.payloadHash()); return after(m);
    }
    public static Map<String,Object> currentRequest(String writer,Map<String,Object> input) {
        var m=ordered(input,CURRENT); common(m); uuid(m.get("migration_id")); uuid(m.get("decision_id"));
        for(String k:List.of("classification_generation","auth_epoch","auth_source_revision")) integer(m.get(k),false);
        checkHash("current-request",writer,m); return m;
    }
    public static Map<String,Object> suspension(String writer,UUID operation,UUID owner,Map<String,Object> baseline,Map<String,Object> before,Map<String,Object> after) {
        var m=new LinkedHashMap<String,Object>(); m.put("protocol_version",1L);m.put("operation_id",operation.toString());m.put("identity_id",owner.toString());
        m.put("baseline",baseline(baseline));m.put("before",before==null?null:user(before));m.put("after",after(after));
        m.put("payload_hash",hash("suspend-request",withWriter(writer,m)));return suspensionRequest(writer,m);
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> suspensionRequest(String writer,Map<String,Object> input) {
        var m=ordered(input,SUSPEND);common(m);
        m=mutable(m);m.put("baseline",baseline(object(m.get("baseline"),true)));m.put("before",m.get("before")==null?null:user(object(m.get("before"),false)));m.put("after",after(object(m.get("after"),false)));
        if(m.get("before")==null&&(m.get("baseline")!=null||integer(object(m.get("after"),false).get("expected_version"),true)!=0)) throw malformed();
        checkHash("suspend-request",writer,m);return immutable(m);
    }
    public static Map<String,Object> suspensionResponse(Map<String,Object> request,Map<String,Object> input) {
        var m=mutable(ordered(input,RESPONSE));common(m);integer(m.get("recorded_at_us"),true);
        for(String k:List.of("protocol_version","operation_id","identity_id","payload_hash")) if(!Objects.equals(m.get(k),request.get(k))) throw malformed();
        if("NOT_MIG".equals(m.get("outcome"))) { if(m.get("binding")!=null||request.get("baseline")!=null) throw malformed(); }
        else if("SUSPENDED".equals(m.get("outcome"))) {
            if(request.get("before")==null||request.get("baseline")==null) throw malformed();
            var b=ordered(object(m.get("binding"),false),BINDING);
            if(!"MIG".equals(b.get("source_kind"))) throw malformed();
            var baseline=object(request.get("baseline"),false);
            for(String k:List.of("migration_id","decision_id")) { uuid(b.get(k));if(!Objects.equals(b.get(k),baseline.get(k))) throw malformed(); }
            for(String k:BINDING.subList(3,BINDING.size())) integer(b.get(k),false);
            m.put("binding",b);
        } else throw malformed();
        return immutable(m);
    }
    public static LinkedHashMap<String,Object> withWriter(String writer,Map<String,Object> m) {
        if(writer==null||!writer.matches("[A-Za-z0-9._-]{1,64}")) throw malformed();
        var values=new LinkedHashMap<String,Object>();values.put("writer_id",writer);values.putAll(m);return values;
    }
    private static void checkHash(String domain,String writer,Map<String,Object> m) {
        digest(m.get("payload_hash"));var fields=mutable(m);fields.remove("payload_hash");if(!hash(domain,withWriter(writer,fields)).equals(m.get("payload_hash"))) throw malformed();
    }
    public static String hash(String domain,Map<String,Object> ordered) {
        try { var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.write("ArMoney-LiveUser-v1".getBytes(StandardCharsets.US_ASCII));out.writeByte(0);frame(out,domain);
            for(var e:ordered.entrySet()) {frame(out,e.getKey());typed(out,e.getValue());}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException|NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Live User hash unavailable"); }
    }
    private static void frame(DataOutputStream out,String text)throws IOException {byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>4096)throw malformed();out.writeInt(bytes.length);out.write(bytes);}
    private static void typed(DataOutputStream out,Object value)throws IOException {
        if(value==null)out.writeByte(0);
        else if(value instanceof String s){out.writeByte(1);frame(out,s);}
        else if(value instanceof Integer||value instanceof Long){out.writeByte(2);out.writeLong(((Number)value).longValue());}
        else if(value instanceof Boolean b){out.writeByte(3);out.writeByte(b?1:0);}
        else if(value instanceof Map<?,?> m){out.writeByte(4);out.writeInt(m.size());for(var e:m.entrySet()){if(!(e.getKey() instanceof String k))throw malformed();frame(out,k);typed(out,e.getValue());}}
        else throw malformed();
    }
    public static Map<String,Object> ordered(Map<String,Object> input,List<String> fields) {
        if(input==null||input.size()!=fields.size()||!input.keySet().equals(new HashSet<>(fields)))throw malformed();
        var result=new LinkedHashMap<String,Object>();for(String k:fields){Object v=input.get(k);result.put(k,v instanceof Integer?((Integer)v).longValue():v);}return immutable(result);
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value,boolean nullable){if(value==null&&nullable)return null;if(!(value instanceof Map<?,?>))throw malformed();return (Map<String,Object>)value;}
    private static void common(Map<String,Object> m){if(integer(m.get("protocol_version"),false)!=1)throw malformed();uuid(m.get("operation_id"));uuid(m.get("identity_id"));}
    public static UUID uuid(Object value){if(!(value instanceof String s))throw malformed();UUID id=UUID.fromString(s);if(!id.toString().equals(s)||id.equals(new UUID(0,0)))throw malformed();return id;}
    public static long integer(Object value,boolean zero){if(!(value instanceof Long||value instanceof Integer))throw malformed();long n=((Number)value).longValue();if(n<(zero?0:1))throw malformed();return n;}
    public static void digest(Object value){if(!(value instanceof String s)||!s.matches("[0-9a-f]{64}"))throw malformed();}
    private static LinkedHashMap<String,Object> mutable(Map<String,Object> m){return new LinkedHashMap<>(m);}
    public static Map<String,Object> immutable(Map<String,Object> m){return Collections.unmodifiableMap(new LinkedHashMap<>(m));}
    private static IllegalArgumentException malformed(){return new IllegalArgumentException("Live User value rejected");}
}
