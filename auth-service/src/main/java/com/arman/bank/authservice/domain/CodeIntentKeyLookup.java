package com.arman.bank.authservice.domain;

import java.net.URI;
import java.util.*;

/** Authorized original-key selection only; no authority, raw code or proof snapshot. */
public final class CodeIntentKeyLookup {
    private CodeIntentKeyLookup() {}
    public enum Error { BAD_INPUT, UNAUTHORIZED, CONFLICT, MISSING, RATE_LIMITED, UNAVAILABLE }
    public static final class Failure extends RuntimeException {
        private final Error error;
        public Failure(Error error){super("code_intent_request_rejected");this.error=Objects.requireNonNull(error);}
        public Error error(){return error;}
    }
    public record Candidate(String codeKeyId,String codeDigest){
        @Override public String toString(){return "CodeIntentKeyCandidate[redacted]";}
    }
    public record Input(String issuer,String subject,String clientId,String userSessionId,List<Candidate> candidates){
        public Input{if(candidates!=null)candidates=Collections.unmodifiableList(new ArrayList<>(candidates));}
        @Override public String toString(){return "CodeIntentKeyLookupInput[redacted]";}
    }
    public record Result(String codeKeyId){
        public Result{if(codeKeyId==null||!codeKeyId.matches("[A-Za-z0-9_-]{1,32}"))throw new Failure(Error.UNAVAILABLE);}
        @Override public String toString(){return "CodeIntentKeyLookupResult[redacted]";}
    }
    public static void validate(Input input,Set<String> allowedCodeKeys){
        if(input==null||!bounded(input.issuer(),512)||!bounded(input.subject(),255)||!bounded(input.clientId(),128)
            ||!input.clientId().matches("[A-Za-z0-9_-]{1,128}")||!bounded(input.userSessionId(),255)
            ||input.candidates()==null||input.candidates().isEmpty()||input.candidates().size()>2||allowedCodeKeys==null)bad();
        try{
            var uri=URI.create(input.issuer());
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null
                ||uri.getRawFragment()!=null||input.issuer().endsWith("/")||uri.getPort()==0||uri.getPort()>65535)bad();
        }catch(IllegalArgumentException invalid){bad();}
        var ids=new HashSet<String>();var digests=new HashSet<String>();
        for(var candidate:input.candidates()){
            if(candidate==null||candidate.codeKeyId()==null||!candidate.codeKeyId().matches("[A-Za-z0-9_-]{1,32}")
                ||!allowedCodeKeys.contains(candidate.codeKeyId())||candidate.codeDigest()==null||!candidate.codeDigest().matches("[0-9a-f]{64}")
                ||!ids.add(candidate.codeKeyId())||!digests.add(candidate.codeDigest()))bad();
        }
    }
    private static boolean bounded(String value,int maximum){return value!=null&&!value.isBlank()&&value.length()<=maximum
        &&value.codePoints().noneMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff));}
    private static void bad(){throw new Failure(Error.BAD_INPUT);}
}
