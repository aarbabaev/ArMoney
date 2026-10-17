package com.arman.bank.sso;

import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Unwired lifecycle owner. No process environment reads, SPI registration or authority. */
final class CodeIntentResources implements AutoCloseable {
    private final CodeIntentHttp transport;
    private final CodeIntentClient client;
    private final CodeKeys keys;
    private final AtomicBoolean closed=new AtomicBoolean();
    private CodeIntentResources(CodeIntentHttp transport,CodeIntentClient client,CodeKeys keys) {
        this.transport=transport; this.client=client; this.keys=keys;
    }
    /** Caller supplies every locally known current/retained secret; this cannot prove global completeness. */
    static Optional<CodeIntentResources> open(Map<String,String> environment,Map<String,byte[]> localMaterial) {
        return open(environment,localMaterial,CodeIntentHttp::new,null);
    }
    /** Package-private deterministic construction seam; returned transport ownership transfers to this owner. */
    static Optional<CodeIntentResources> open(Map<String,String> environment,Map<String,byte[]> localMaterial,
            Function<URI,CodeIntentHttp> transportFactory,
            BiFunction<CodeIntentHttp,CodeIntentConfiguration,CodeIntentClient> clientFactory) {
        CodeIntentHttp transport=null;
        try {
            var values=Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(environment)));
            Map<String,byte[]> copied=new HashMap<>();
            Objects.requireNonNull(localMaterial).forEach((name,material)->copied.put(name,material==null?null:material.clone()));
            var configuration=CodeIntentConfiguration.from(values,copied);
            if(!configuration.enabled()) return Optional.empty();
            String[] ids=values.get("SSO_CODE_INTENT_CODE_KEY_IDS").split(",",-1);
            CodeKeys keys=new CodeKeys(new CodeKeys.Key(ids[0],copied.get(ids[0])),ids.length==2?new CodeKeys.Key(ids[1],copied.get(ids[1])):null);
            transport=Objects.requireNonNull(transportFactory.apply(CodeIntentPair.parseUri(values.get("ARMONEY_AUTH_BRIDGE_URL"))));
            CodeIntentClient client=clientFactory==null
                    ?new CodeIntentClient(transport,values.get("SSO_LOGIN_PROOF_WRITE_KEY"),values.get("SSO_CODE_INTENT_LOOKUP_KEY"),
                        values.get("SSO_LOGIN_PROOF_CONFIRM_KEY"),Clock.systemUTC(),configuration)
                    :Objects.requireNonNull(clientFactory.apply(transport,configuration));
            return Optional.of(new CodeIntentResources(transport,client,keys));
        } catch(RuntimeException|Error failure) {
            if(transport!=null) try { transport.close(); } catch(RuntimeException|Error ignored) { }
            // Input/factory exceptions may contain URLs or secrets. Never preserve them.
            throw new IllegalArgumentException("Code-intent resources unavailable");
        }
    }
    CodeIntentClient client() { requireOpen(); return client; }
    CodeKeys keys() { requireOpen(); return keys; }
    private void requireOpen() { if(closed.get()) throw new IllegalStateException("Code-intent resources closed"); }
    @Override public void close() {
        if(closed.compareAndSet(false,true)) {
            try { transport.close(); }
            catch(RuntimeException|Error failure) { throw new IllegalStateException("Code-intent resources unavailable"); }
        }
    }
    @Override public String toString() { return "CodeIntentResources[REDACTED]"; }
}
