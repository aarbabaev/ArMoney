package com.arman.bank.userservice.application;
import java.util.Map;
/** Authenticated durable peer outcome only; uncertainty must throw, never NOT_MIG. */
@FunctionalInterface public interface UserMutationSuspensionPort {
    Map<String,Object> suspend(String writer,Map<String,Object> originalRequest,long originalStartedNanos);
    static UserMutationSuspensionPort unavailable(){return (writer,request,start)->{throw new LiveUserCurrentStore.Rejected(503);};}
}
