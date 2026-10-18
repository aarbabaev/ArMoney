package com.arman.bank.authservice.domain;

import java.util.*;
import java.time.Instant;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Migration-only possession evidence; absent User projection is NOT normal verification authority. */
public final class LegacyMigrationOtp {
    private LegacyMigrationOtp(){}
    public enum Purpose { EMAIL, PHONE }
    public enum Error { BAD_INPUT, UNAUTHORIZED, CONFLICT, RATE_LIMITED, UNAVAILABLE }
    public static final class Failure extends RuntimeException {
        private final Error error;
        public Failure(Error error){super("migration_otp_rejected");this.error=error;}
        public Error error(){return error;}
    }
    public record Seed(UUID migrationId,UUID stageOperationId,UUID commandId,UUID decisionCommandId,UUID identityId,
                       String phone,long emailVersion,String emailDigest,long policyVersion,Instant createdAt,Instant intentDeadline,Instant accountDeadline) {
        public Seed {
            try {
                if(createdAt==null||intentDeadline==null||accountDeadline==null||createdAt.isBefore(Instant.EPOCH)
                    ||!accountDeadline.isAfter(createdAt)||accountDeadline.isAfter(createdAt.plusSeconds(120))
                    ||!intentDeadline.isAfter(createdAt)||intentDeadline.isAfter(createdAt.plusSeconds(900))
                    ||accountDeadline.isAfter(intentDeadline))throw new Failure(Error.BAD_INPUT);
            }catch(java.time.DateTimeException|ArithmeticException invalid){throw new Failure(Error.BAD_INPUT);}
        }
        @Override public String toString(){return "MigrationStageSeed[redacted]";}
    }
    /** Only an authenticated owning adapter may supply this exchange. No production supplier is wired here. */
    public record StageExchange(Map<String,Object> request,Map<String,Object> acknowledgement) {
        public StageExchange {request=Collections.unmodifiableMap(new LinkedHashMap<>(request));acknowledgement=Collections.unmodifiableMap(new LinkedHashMap<>(acknowledgement));}
        @Override public String toString(){return "MigrationStageExchange[redacted]";}
    }
    public static final List<String> REQUEST=List.of("protocol_version","migration_id","stage_operation_id","command_id","decision_command_id","identity_id","profile_id","canonical_phone","email_contact_version","email_digest","policy_version","created_at","authorization_deadline","payload_hash");
    public static final List<String> ACK=List.of("protocol_version","migration_id","stage_operation_id","identity_id","profile_id","baseline_generation","contact_mutation_revision","projection_present","projection_revision","phone_contact_version","verification_epoch","email_contact_version","email_digest","baseline_hash","authorization_deadline","payload_hash","stage_ack_hash");
    public static void validateStage(Seed seed,StageExchange exchange){
        if(seed==null||exchange==null)fail(Error.UNAVAILABLE);
        var request=exchange.request();var ack=exchange.acknowledgement();exact(request,REQUEST);exact(ack,ACK);
        for(String key:List.of("migration_id","stage_operation_id","command_id","decision_command_id","identity_id","profile_id"))uuid(request.get(key));
        for(String key:List.of("migration_id","stage_operation_id","identity_id","profile_id"))uuid(ack.get(key));
        if(number(request.get("protocol_version"),false)!=1||number(ack.get("protocol_version"),false)!=1)fail(Error.CONFLICT);
        if(!seed.migrationId().toString().equals(request.get("migration_id"))||!seed.stageOperationId().toString().equals(request.get("stage_operation_id"))
            ||!seed.commandId().toString().equals(request.get("command_id"))||!seed.decisionCommandId().toString().equals(request.get("decision_command_id"))
            ||!seed.identityId().toString().equals(request.get("identity_id"))||!seed.phone().equals(request.get("canonical_phone"))
            ||seed.emailVersion()!=number(request.get("email_contact_version"),false)||!seed.emailDigest().equals(request.get("email_digest"))
            ||seed.policyVersion()!=number(request.get("policy_version"),false)||seed.createdAt().toEpochMilli()!=number(request.get("created_at"),true)
            ||seed.intentDeadline().toEpochMilli()!=number(request.get("authorization_deadline"),true))fail(Error.CONFLICT);
        if(!hash("armoney-user-migration-stage-v1",REQUEST.subList(0,REQUEST.size()-1),request).equals(request.get("payload_hash")))fail(Error.CONFLICT);
        for(String key:List.of("protocol_version","migration_id","stage_operation_id","identity_id","profile_id","email_contact_version","email_digest","authorization_deadline","payload_hash"))
            if(!Objects.equals(String.valueOf(request.get(key)),String.valueOf(ack.get(key))))fail(Error.CONFLICT);
        number(ack.get("baseline_generation"),false);number(ack.get("contact_mutation_revision"),true);digest(ack.get("baseline_hash"));digest(ack.get("stage_ack_hash"));
        if(!(ack.get("projection_present") instanceof Boolean))fail(Error.BAD_INPUT);
        for(String key:List.of("projection_revision","phone_contact_version","verification_epoch")){
            if(Boolean.TRUE.equals(ack.get("projection_present")))number(ack.get(key),false);else if(ack.get(key)!=null)fail(Error.CONFLICT);
        }
        if(!hash("armoney-user-migration-stage-ack-v1",ACK.subList(0,ACK.size()-1),ack).equals(ack.get("stage_ack_hash")))fail(Error.CONFLICT);
    }
    public record Result(UUID challengeId,UUID receiptId,String state,Instant expiresAt,Instant resendAt,int attemptsRemaining,Error error) {
        public static Result rejected(Error error){return new Result(null,null,"REJECTED",null,null,0,error);}
    }
    public static String hash(String domain,List<String> fields,Map<String,Object> values){
        var all=new ArrayList<String>();all.add(domain);for(String field:fields)all.add(String.valueOf(values.get(field)));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tuple(all.toArray(String[]::new))));}
        catch(NoSuchAlgorithmException unavailable){throw new Failure(Error.UNAVAILABLE);}
    }
    public static byte[] tuple(String... values){
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);for(String value:values){byte[] raw=Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);if(raw.length>12288)fail(Error.BAD_INPUT);out.writeInt(raw.length);out.write(raw);}return bytes.toByteArray();}
        catch(IOException unavailable){throw new Failure(Error.UNAVAILABLE);}
    }
    public static long number(Object value,boolean zero){if(!(value instanceof Long||value instanceof Integer))throw new Failure(Error.BAD_INPUT);long n=((Number)value).longValue();if(n<(zero?0:1))fail(Error.BAD_INPUT);return n;}
    public static UUID uuid(Object value){if(!(value instanceof String s))throw new Failure(Error.BAD_INPUT);try{UUID id=UUID.fromString(s);if(!id.toString().equals(s)||id.equals(new UUID(0,0)))fail(Error.BAD_INPUT);return id;}catch(IllegalArgumentException bad){throw new Failure(Error.BAD_INPUT);}}
    public static void digest(Object value){if(!(value instanceof String s)||!s.matches("[0-9a-f]{64}"))fail(Error.BAD_INPUT);}
    private static void exact(Map<String,Object> value,List<String> keys){if(value.size()!=keys.size()||!value.keySet().equals(new HashSet<>(keys)))fail(Error.BAD_INPUT);}
    public static void fail(Error error){throw new Failure(error);}
}
