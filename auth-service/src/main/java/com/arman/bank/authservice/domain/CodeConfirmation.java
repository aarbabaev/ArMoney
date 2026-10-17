package com.arman.bank.authservice.domain;

import java.time.*;
import java.util.Objects;
import java.util.Locale;

/** Internal confirmation facts/results only. No ACTIVE state, token, banking session or notice. */
public final class CodeConfirmation {
    private CodeConfirmation() {}
    public record Facts(LoginProof.Binding binding,String username,String email,String phone,boolean enabled,
                        boolean userSessionActive,boolean clientSessionActive,Instant observedAt,Instant validUntil){
        @Override public String toString(){return "CodeSessionFacts[redacted]";}
    }
    public record Outcome(LoginProof.Snapshot snapshot,boolean deniedCommitted){
        public Outcome{if(deniedCommitted==(snapshot!=null))throw new IllegalArgumentException("Invalid confirmation outcome");}
        public static Outcome denied(){return new Outcome(null,true);}
        public static Outcome confirmed(LoginProof.Snapshot snapshot){return new Outcome(Objects.requireNonNull(snapshot),false);}
    }
    /** Same-clock provider duration, conservatively anchored to ORIGINAL DB start. No clock-rate proof. */
    public static Instant deadline(LoginProof.Binding binding,Facts facts,Instant start,Instant received){
        try{
            if(facts==null||start==null||received==null||!binding.equals(facts.binding())||!bounded(facts.username(),255)
                ||(facts.email()!=null&&(!facts.email().equals(facts.email().strip().toLowerCase(Locale.ROOT))||facts.email().length()>254
                    ||!facts.email().matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")))
                ||(facts.phone()!=null&&!facts.phone().matches("\\+9715[024568][0-9]{7}"))||received.isBefore(start)
                ||Duration.between(start,received).compareTo(Duration.ofSeconds(4))>0||facts.observedAt()==null||facts.validUntil()==null
                ||facts.observedAt().getNano()!=0||facts.validUntil().getNano()!=0)throw unavailable();
            long delta=Duration.between(facts.observedAt(),facts.validUntil()).getSeconds();
            if(delta<1||delta>5)throw unavailable();
            Instant end=start.plusSeconds(delta-1);
            if(!received.isBefore(end))throw unavailable();
            return end;
        }catch(DateTimeException|ArithmeticException invalid){throw unavailable();}
    }
    public static void fresh(Instant start,Instant deadline,Instant now){
        if(now.isBefore(start)||!now.isBefore(deadline))throw unavailable();
    }
    public static LoginProof.Failure unavailable(){return new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);}
    private static boolean bounded(String text,int length){return text!=null&&!text.isBlank()&&text.length()<=length
        &&text.codePoints().noneMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff));}
}
