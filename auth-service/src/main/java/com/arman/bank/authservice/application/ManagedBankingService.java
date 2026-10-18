package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.ManagedBankingAccess.*;
import com.arman.bank.authservice.domain.LoginProof;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Dormant dedicated consumer. No profile provisioning, legacy fallback or provider hook activation. */
public final class ManagedBankingService {
    @FunctionalInterface public interface PairScope { boolean managed(String issuer,String client); }
    private final ManagedBankingStore store;
    private final CodeConfirmationObservations observations;
    private final PairScope pairs;
    private final boolean enabled;
    private final SecureRandom random=new SecureRandom();
    public ManagedBankingService(boolean enabled,ManagedBankingStore store,CodeConfirmationObservations observations,PairScope pairs){
        this.enabled=enabled;this.store=Objects.requireNonNull(store);this.observations=Objects.requireNonNull(observations);this.pairs=Objects.requireNonNull(pairs);
    }
    public boolean enabled(){return enabled;}
    public boolean manages(String issuer,String client){return pairs.managed(issuer,client);}
    public Session exchange(String bearer,SsoTokens.Principal principal){
        requireEnabled();
        var context=principal.tokenContext();
        var client=present(context.clientId());var sid=present(context.sessionId());
        var issued=present(context.issuedAt());var expires=present(context.expiresAt());var proof=present(context.proof());
        if(!pairs.managed(principal.issuer(),client)||!"interactive".equals(proof.kind())||proof.eventId()==null
                ||!principal.emailVerified()||principal.email()==null||!AuthService.canonicalPhone(principal.phoneNumber())
                ||bearer==null||bearer.length()>8192||!bearer.matches("[A-Za-z0-9._~+/-]+=*")||!issued.isBefore(expires))deny();
        var token=new Token(new LoginProof.Binding(principal.issuer(),principal.subject(),client,sid),proof.eventId(),issued,expires,
                principal.email(),principal.phoneNumber(),bearer);
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String opaque=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var result=store.inRequest(request->{
            var admission=request.admitExchange(token);
            var facts=observations.observe(admission.binding(),admission.startedAt());
            return request.exchange(admission,facts,AuthService.digest(opaque));
        });
        if(result.deniedCommitted())deny();
        return new Session(opaque,result.expiresAt());
    }
    public UUID validate(String authorization){
        requireEnabled();String digest=digest(authorization);
        var result=store.inRequest(request->{
            var admission=request.admitValidation(digest);
            return request.validate(admission,observations.observe(admission.binding(),admission.startedAt()));
        });
        if(result.deniedCommitted())deny();return result.identityId();
    }
    public void logout(String authorization){requireEnabled();store.revoke(digest(authorization));}
    private static String digest(String authorization){
        if(authorization==null||!authorization.matches("(?i:Bearer) [A-Za-z0-9_-]{43}"))deny();
        return AuthService.digest(authorization.substring(7));
    }
    private void requireEnabled(){if(!enabled)deny();}
    private static <T>T present(SsoTokens.Metadata<T> metadata){
        if(metadata.status()!=SsoTokens.MetadataStatus.PRESENT_VALID_FORMAT)deny();return metadata.value();
    }
    private static void deny(){throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED);}
}
