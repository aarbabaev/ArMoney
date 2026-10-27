package com.arman.bank.authservice.domain;

import java.time.Instant;
import java.util.*;

/** Closed browser command vocabulary. These data cannot establish migration authority. */
public final class LegacyMigrationBrowserContinuation {
    private LegacyMigrationBrowserContinuation(){}
    public static final int FLOW_SLOTS=32,GLOBAL_SLOTS=100000;
    public enum Purpose { STAGE,EMAIL_ISSUE,EMAIL_CONSUME,PHONE_ISSUE,PHONE_CONSUME,DECISION,PUBLICATION,FINAL_PREPARE,FINAL }
    public record Form(UUID commandId,Purpose purpose,UUID challengeId,Instant eligibleAt,int attemptsRemaining,UUID finalCommandId){
        public Form {
            Objects.requireNonNull(commandId);Objects.requireNonNull(purpose);
            boolean consume=purpose==Purpose.EMAIL_CONSUME||purpose==Purpose.PHONE_CONSUME;
            if(consume!=(challengeId!=null)||attemptsRemaining<0||attemptsRemaining>5||(purpose==Purpose.FINAL)!=(finalCommandId!=null))throw new IllegalArgumentException("Invalid continuation command");
        }
        @Override public String toString(){return "MigrationContinuationForm[redacted]";}
    }
    public static void bundle(List<Form> forms){
        if(forms==null||forms.size()>2||forms.stream().anyMatch(Objects::isNull)||forms.stream().map(Form::purpose).distinct().count()!=forms.size()||forms.stream().map(Form::commandId).distinct().count()!=forms.size())throw new IllegalArgumentException("Invalid continuation bundle");
        if(forms.size()==2){Purpose a=forms.get(0).purpose(),b=forms.get(1).purpose();if(!((a==Purpose.EMAIL_CONSUME&&b==Purpose.EMAIL_ISSUE)||(a==Purpose.PHONE_CONSUME&&b==Purpose.PHONE_ISSUE)))throw new IllegalArgumentException("Invalid continuation siblings");}
    }
}
