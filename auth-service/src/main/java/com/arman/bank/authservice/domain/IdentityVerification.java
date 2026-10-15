package com.arman.bank.authservice.domain;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Staging evidence only. No ACTIVE state, banking principal or provider-success assertion. */
public final class IdentityVerification {
    private IdentityVerification() {}
    public enum State { PENDING_CONTACTS, PROJECTION_PENDING, PROJECTED, SUSPENDED, BLOCKED_MIGRATION }
    public record Flow(String issuer,String subject,String clientId,String rootSessionId,String tabId,String authenticationSessionId) {
        @Override public String toString(){return "VerificationFlow[redacted]";}
    }
    /** Supplied only by a trusted pre-token observation port; never decoded from an inbound bridge body. */
    public record Observation(Flow flow,String username,String email,String phone,boolean enabled,boolean sessionActive,
                              Instant sessionExpiresAt,Instant observationStartedAt) {
        @Override public String toString(){return "VerificationObservation[redacted]";}
    }
    public record Enrollment(UUID commandId,Flow flow,String phone,String email) {
        @Override public String toString(){return "VerificationEnrollment[redacted]";}
    }
    public record Issue(UUID commandId,UUID enrollmentId,Flow flow,Verification.Purpose purpose,int contactVersion) {}
    public record Consume(UUID commandId,UUID enrollmentId,UUID challengeId,Flow flow,Verification.Purpose purpose,int contactVersion,String code) {
        @Override public String toString(){return "VerificationConsume[redacted]";}
    }
    public record Policy(Set<String> issuers,Set<String> clients,Set<String> writers,int maxFlows,int maxBarriers,int maxCommands) {
        public Policy {
            issuers=Set.copyOf(issuers);clients=Set.copyOf(clients);writers=Set.copyOf(writers);
            if(issuers.isEmpty()||issuers.size()>16||clients.isEmpty()||clients.size()>32||writers.isEmpty()||writers.size()>16
                    ||maxFlows<1||maxFlows>40000||maxBarriers<1||maxBarriers>40000||maxCommands<1||maxCommands>1000000)throw new IllegalArgumentException("Invalid staging policy");
            for(String issuer:issuers){var uri=java.net.URI.create(issuer);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Invalid issuer");}
            for(String client:clients)if(!client.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid client");
            for(String writer:writers)if(!writer.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid writer");
        }
        public static Policy defaults(Set<String> issuers,Set<String> clients,Set<String> writers){return new Policy(issuers,clients,writers,40000,40000,1000000);}
    }
    public static void validate(Flow flow,Policy policy){
        if(flow==null||!bounded(flow.issuer(),512)||!bounded(flow.subject(),255)||!bounded(flow.clientId(),128)||!bounded(flow.rootSessionId(),255)
                ||!bounded(flow.tabId(),255)||flow.authenticationSessionId()==null||!flow.authenticationSessionId().matches("[A-Za-z0-9_-]{43}"))fail(Verification.Error.BAD_INPUT);
        try{byte[] bytes=Base64.getUrlDecoder().decode(flow.authenticationSessionId());if(bytes.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(flow.authenticationSessionId()))fail(Verification.Error.BAD_INPUT);}catch(IllegalArgumentException invalid){fail(Verification.Error.BAD_INPUT);}
        if(!policy.issuers().contains(flow.issuer())||!policy.clients().contains(flow.clientId()))fail(Verification.Error.BINDING);
    }
    public static void evidence(Flow expected,Observation observation,Instant now){
        if(observation==null)fail(Verification.Error.UNAVAILABLE);
        if(!Objects.equals(expected,observation.flow())||!observation.enabled()||!observation.sessionActive()
                ||observation.observationStartedAt()==null||observation.observationStartedAt().isAfter(now)
                ||observation.observationStartedAt().isBefore(now.minusSeconds(5))||observation.sessionExpiresAt()==null
                ||!now.isBefore(observation.sessionExpiresAt()))fail(Verification.Error.UNAVAILABLE);
        if(!Objects.equals(observation.username(),observation.phone())||observation.phone()==null||!observation.phone().matches("\\+9715[024568][0-9]{7}")
                ||observation.email()==null||observation.email().length()>254||!observation.email().equals(observation.email().strip().toLowerCase(Locale.ROOT))
                ||!observation.email().matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+"))fail(Verification.Error.BAD_INPUT);
    }
    public static byte[] contextFingerprint(Flow flow){return hash("armoney-flow-denial-v1",flow.issuer(),flow.subject(),flow.clientId(),flow.rootSessionId(),flow.tabId());}
    public static byte[] tuple(Flow flow){return encoded("armoney-flow-tuple-v1",flow.issuer(),flow.subject(),flow.clientId(),flow.rootSessionId(),flow.tabId(),flow.authenticationSessionId());}
    public static byte[] hash(String...fields){try{return MessageDigest.getInstance("SHA-256").digest(encoded(fields));}catch(NoSuchAlgorithmException unavailable){throw new Verification.Failure(Verification.Error.UNAVAILABLE);}}
    private static byte[] encoded(String...fields){try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);for(String field:fields){byte[] value=field.getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);}return bytes.toByteArray();}catch(IOException unavailable){throw new Verification.Failure(Verification.Error.UNAVAILABLE);}}
    private static boolean bounded(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max&&value.codePoints().noneMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff));}
    private static void fail(Verification.Error error){throw new Verification.Failure(error);}
}
