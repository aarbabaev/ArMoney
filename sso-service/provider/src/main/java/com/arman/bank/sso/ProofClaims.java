package com.arman.bank.sso;

import java.util.Map;
import java.util.UUID;

final class ProofClaims {
    static final String EVENT = "armoney_login_event_id", KIND = "armoney_login_kind", ELIGIBLE = "armoney_login_notice_eligible";
    static void clear(Map<String,Object> values) { values.remove(EVENT); values.remove(KIND); values.remove(ELIGIBLE); }
    static void put(Map<String,Object> values, LoginProof.Snapshot proof) {
        clear(values); if (proof.event() != null) values.put(EVENT, proof.event().toString());
        values.put(KIND, proof.kind()); values.put(ELIGIBLE, proof.eligible());
    }
    static void preserve(Map<String,Object> original, Map<String,Object> output) {
        Object kind = original.get(KIND), eligible = original.get(ELIGIBLE), event = original.get(EVENT);
        boolean hasEvent=original.containsKey(EVENT);
        clear(output);
        if (!(eligible instanceof Boolean flag) || !(kind instanceof String type)) return;
        if (type.equals("reuse") && event == null && !hasEvent && !flag) {
            output.put(KIND,type); output.put(ELIGIBLE,false);
        } else if (type.equals("interactive") && event instanceof String id) {
            try { UUID uuid = LoginProof.uuid(id); output.put(EVENT,uuid.toString()); output.put(KIND,type); output.put(ELIGIBLE,flag); }
            catch (IllegalArgumentException invalid) { clear(output); }
        }
    }
    private ProofClaims() { }
}
