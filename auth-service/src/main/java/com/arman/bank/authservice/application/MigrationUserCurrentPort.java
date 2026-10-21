package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LiveUserRevision;
import java.time.Duration;
import java.util.*;

/** Authenticated fixed User PRIMARY current tuple. No credential, grant, clock or lease authority. */
@FunctionalInterface public interface MigrationUserCurrentPort {
    Observation observe(Query query,Duration originalRemainingBudget);
    record Query(UUID operationId,UUID identityId,UUID migrationId,UUID decisionId,long classificationGeneration,long epoch,long sourceRevision){
        public Query {if(operationId==null||identityId==null||migrationId==null||decisionId==null||classificationGeneration<1||epoch<1||sourceRevision<1)throw new IllegalArgumentException("User observation query rejected");}
        @Override public String toString(){return "MigrationUserCurrentQuery[redacted]";}
    }
    record Observation(Query query,Map<String,Object> current){
        public Observation {Objects.requireNonNull(query);current=LiveUserRevision.user(current);}
        @Override public String toString(){return "MigrationUserCurrentObservation[redacted]";}
    }
}
