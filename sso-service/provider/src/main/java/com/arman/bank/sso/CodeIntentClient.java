package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Unwired stage/key/confirm protocol. Never falls back to historical resolve. */
final class CodeIntentClient {
    @FunctionalInterface interface Transport {
        JsonNode post(CodeIntentHttp.Operation operation,Map<String,Object> fields,String credential,InvocationDeadline deadline);
    }
    record StageAck(UUID intent,String keyId,Instant issued,Instant expires) {
        @Override public String toString() { return "CodeIntentStageAck[REDACTED]"; }
    }
    private final Transport transport;
    private final String writer,lookup,confirmer;
    private final Clock clock;
    private final CodeIntentConfiguration configuration;
    CodeIntentClient(CodeIntentHttp transport,String writer,String lookup,String confirmer,Clock clock,CodeIntentConfiguration configuration) {
        this(transport::post,writer,lookup,confirmer,clock,configuration);
    }
    CodeIntentClient(Transport transport,String writer,String lookup,String confirmer,Clock clock,CodeIntentConfiguration configuration) {
        for(String key:new String[]{writer,lookup,confirmer}) if(key==null||key.length()<32||key.length()>256||key.chars().anyMatch(c->c<33||c>126)) throw new CodeIntentHttp.Failure();
        if(writer.equals(lookup)||writer.equals(confirmer)||lookup.equals(confirmer)) throw new CodeIntentHttp.Failure();
        this.transport=Objects.requireNonNull(transport); this.writer=writer; this.lookup=lookup; this.confirmer=confirmer; this.clock=Objects.requireNonNull(clock);
        this.configuration=Objects.requireNonNull(configuration);
    }
    StageAck stage(LoginProof.Write write,InvocationDeadline deadline) {
        try {
            deadline.requireOpen();
            requireManaged(write.binding());
            if(!"interactive".equals(write.kind())||!clock.instant().isBefore(write.expires())) throw new CodeIntentHttp.Failure();
            JsonNode node=transport.post(CodeIntentHttp.Operation.STAGE,write.fields(),writer,deadline);
            LoginProof.fields(node,Set.of("intent_id","code_key_id","state","issued_at","expires_at"));
            var ack=new StageAck(LoginProof.uuid(LoginProof.text(node,"intent_id")),LoginProof.text(node,"code_key_id"),LoginProof.time(LoginProof.text(node,"issued_at")),LoginProof.time(LoginProof.text(node,"expires_at")));
            if(!"STAGED".equals(LoginProof.text(node,"state"))||!write.keyId().equals(ack.keyId())||!write.issued().equals(ack.issued())||!write.expires().equals(ack.expires())||!clock.instant().isBefore(ack.expires())) throw new CodeIntentHttp.Failure();
            deadline.requireOpen(); return ack;
        } catch(RuntimeException unavailable) { deadline.cancelOwnedExchange(); throw new CodeIntentHttp.Failure(); }
    }
    LoginProof.Snapshot lookupAndConfirm(CodeKeys keys,LoginProof.Binding binding,String validatedCodeId,Instant codeExpiry,InvocationDeadline deadline) {
        try {
            deadline.requireOpen(); LoginProof.uuid(validatedCodeId);
            requireManaged(binding);
            if(!clock.instant().isBefore(codeExpiry)||keys.keys().isEmpty()||keys.keys().size()>2) throw new CodeIntentHttp.Failure();
            Map<String,String> offered=new LinkedHashMap<>(); List<Map<String,String>> candidates=new ArrayList<>();
            for(var key:keys.keys()) {
                deadline.requireOpen(); String digest=keys.digest(key,binding,validatedCodeId);
                if(offered.put(key.id(),digest)!=null||new HashSet<>(offered.values()).size()!=offered.size()) throw new CodeIntentHttp.Failure();
                candidates.add(Map.of("code_key_id",key.id(),"code_digest",digest));
            }
            var request=binding.fields(); request.put("candidates",candidates);
            JsonNode selected=transport.post(CodeIntentHttp.Operation.KEY,request,lookup,deadline);
            LoginProof.fields(selected,Set.of("code_key_id")); String keyId=LoginProof.text(selected,"code_key_id");
            if(!offered.containsKey(keyId)) throw new CodeIntentHttp.Failure();
            deadline.requireOpen(); if(!clock.instant().isBefore(codeExpiry)) throw new CodeIntentHttp.Failure();
            requireManaged(binding);
            var confirmation=binding.fields(); confirmation.put("code_key_id",keyId); confirmation.put("code_digest",offered.get(keyId));
            var proof=LoginProof.snapshot(transport.post(CodeIntentHttp.Operation.CONFIRM,confirmation,confirmer,deadline),false,null);
            Instant now=clock.instant();
            if(!"interactive".equals(proof.kind())||!proof.eligible()||!proof.expires().equals(codeExpiry)||!now.isBefore(proof.expires())||proof.issued().isAfter(now.plusSeconds(30))) throw new CodeIntentHttp.Failure();
            deadline.requireOpen(); return proof;
        } catch(RuntimeException unavailable) { deadline.cancelOwnedExchange(); throw new CodeIntentHttp.Failure(); }
    }
    private void requireManaged(LoginProof.Binding binding) {
        if(!configuration.managed(binding.issuer(),binding.client())) throw new CodeIntentHttp.Failure();
    }
}
