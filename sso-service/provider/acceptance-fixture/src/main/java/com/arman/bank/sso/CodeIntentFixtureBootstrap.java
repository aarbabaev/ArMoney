package com.arman.bank.sso;

import java.util.*;
import java.util.concurrent.*;

/** Explicit disposable-image bootstrap. Never reads process configuration or provisions identities. */
public final class CodeIntentFixtureBootstrap implements AutoCloseable {
    interface Owner extends AutoCloseable { void install(); void maintenance(); void close(); }
    interface Maintenance extends AutoCloseable { void start(Runnable task); void stop(); boolean await() throws InterruptedException; default void close(){stop();try{if(!await())throw new IllegalStateException("Fixture bootstrap unavailable");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Fixture bootstrap unavailable");}} }
    interface Factory {
        AutoCloseable resources(Map<String,String> configuration,Map<String,byte[]> material);
        AutoCloseable transport(CodeIntentFixtureSupport.Settings settings);
        Owner support(CodeIntentFixtureSupport.Settings settings,AutoCloseable resources,AutoCloseable transport);
        Maintenance maintenance();
    }
    private static CodeIntentFixtureBootstrap installed;
    private final Owner support;
    private final Maintenance maintenance;
    private volatile boolean active,closed;

    public static Optional<CodeIntentFixtureBootstrap> start(Map<String,String> explicitConfiguration,
            Map<String,byte[]> completeLocalMaterial,CodeIntentFixtureSupport.Settings settings){
        return start(explicitConfiguration,completeLocalMaterial,settings,new Factory(){
            public AutoCloseable resources(Map<String,String> config,Map<String,byte[]> material){return CodeIntentResources.open(config,material).orElseThrow();}
            public AutoCloseable transport(CodeIntentFixtureSupport.Settings s){return new CodeIntentFixtureTransport(s.providerKey(),s.peerKey());}
            public Owner support(CodeIntentFixtureSupport.Settings s,AutoCloseable r,AutoCloseable t){
                var value=new CodeIntentFixtureSupport(s,(CodeIntentResources)r,(CodeIntentFixtureTransport)t);
                return new Owner(){public void install(){CodeIntentFixtureSupport.install(value);}public void maintenance(){value.maintenance();}public void close(){value.close();}};
            }
            public Maintenance maintenance(){return new ScheduledMaintenance();}
        });
    }
    static synchronized Optional<CodeIntentFixtureBootstrap> start(Map<String,String> explicitConfiguration,
            Map<String,byte[]> completeLocalMaterial,CodeIntentFixtureSupport.Settings settings,Factory factory){
        AutoCloseable resources=null,transport=null;Owner support=null;Maintenance maintenance=null;
        try{
            var config=Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(explicitConfiguration)));
            Map<String,byte[]> copied=new HashMap<>();Objects.requireNonNull(completeLocalMaterial).forEach((k,v)->copied.put(k,v==null?null:v.clone()));
            var material=Collections.unmodifiableMap(copied);
            var parsed=CodeIntentConfiguration.from(config,material);
            if(!parsed.enabled())return Optional.empty();
            Objects.requireNonNull(settings);Objects.requireNonNull(factory);
            validate(config,material,parsed,settings);
            if(installed!=null)throw new IllegalStateException();
            resources=Objects.requireNonNull(factory.resources(config,material));
            transport=Objects.requireNonNull(factory.transport(settings));
            support=Objects.requireNonNull(factory.support(settings,resources,transport));
            // Ownership transferred: support alone now closes both children.
            resources=null;transport=null;
            maintenance=Objects.requireNonNull(factory.maintenance());
            var result=new CodeIntentFixtureBootstrap(support,maintenance);
            maintenance.start(result::tick);
            support.install(); // Last potentially failing startup operation; no partially ready controls.
            result.active=true;installed=result;
            return Optional.of(result);
        }catch(Throwable failure){
            cleanup(maintenance,failure);cleanup(support,failure);cleanup(transport,failure);cleanup(resources,failure);
            throw new IllegalStateException("Fixture bootstrap unavailable");
        }
    }
    private CodeIntentFixtureBootstrap(Owner support,Maintenance maintenance){this.support=support;this.maintenance=maintenance;}
    private static void validate(Map<String,String> config,Map<String,byte[]> material,CodeIntentConfiguration parsed,CodeIntentFixtureSupport.Settings s){
        if(!parsed.managedPairs().equals(Set.of(new CodeIntentPair(s.issuer(),s.clientId())))||!parsed.legacyPairs().isEmpty()
                ||!s.issuer().equals(config.get("SSO_ISSUER"))||!s.clientId().equals(config.get("SSO_VERIFICATION_CLIENT_IDS"))
                ||!"http://auth-service:8080".equals(config.get("ARMONEY_AUTH_BRIDGE_URL"))
                ||!s.bindingKey().equals(config.get("ARMONEY_PROVIDER_BINDING_KEY")))throw new IllegalArgumentException();
        // Known persisted synthetic subject is supplied by the image owner; UUID syntax is not persistence evidence.
        if(!UUID.fromString(s.subject()).toString().equals(s.subject()))throw new IllegalArgumentException();
        Map<String,String> inventory=new HashMap<>(config);
        bind(inventory,"ARMONEY_CODE_INTENT_FIXTURE_CONTROL_KEY",s.controllerKey());
        bind(inventory,"AUTH_CODE_INTENT_FIXTURE_PROVIDER_KEY",s.providerKey());
        bind(inventory,"ARMONEY_FIXTURE_PEER_KEY",s.peerKey());
        CodeIntentFixtureSupport.credential(inventory.get("AUTH_TO_SSO_STATUS_KEY"));
        // Reuse canonical raw/Base64 crosswise comparison for all locally supplied roles and retained materials.
        CodeIntentConfiguration.from(inventory,material);
    }
    private static void bind(Map<String,String> inventory,String name,String value){String existing=inventory.putIfAbsent(name,value);if(existing!=null&&!existing.equals(value))throw new IllegalArgumentException();}
    private static void cleanup(AutoCloseable value,Throwable primary){if(value!=null)try{value.close();}catch(Throwable cleanup){if(cleanup!=primary)primary.addSuppressed(cleanup);}}
    private void tick(){synchronized(this){if(!active||closed)return;}try{support.maintenance();}catch(Throwable failure){stop(false);}}
    /** Factory delegates only to an explicitly started owner; absence never auto-initializes anything. */
    static synchronized void postInit(){if(installed!=null)synchronized(installed){if(installed.closed)throw new IllegalStateException("Fixture bootstrap unavailable");}}
    static void closeInstalled(){CodeIntentFixtureBootstrap owner;synchronized(CodeIntentFixtureBootstrap.class){owner=installed;}if(owner!=null)owner.close();}
    private void stop(boolean join){
        boolean release;synchronized(this){release=!closed;closed=true;active=false;}
        Throwable failure=null;
        if(release){try{maintenance.stop();}catch(Throwable e){failure=e;}}
        if(join)try{if(!maintenance.await())failure=new IllegalStateException();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();failure=interrupted;}catch(Throwable e){failure=e;}
        if(release)try{support.close();}catch(Throwable e){if(failure==null)failure=e;else if(e!=failure)failure.addSuppressed(e);}
        synchronized(CodeIntentFixtureBootstrap.class){if(installed==this)installed=null;}
        if(failure!=null)throw new IllegalStateException("Fixture bootstrap unavailable");
    }
    @Override public void close(){stop(true);}
    @Override public String toString(){return "CodeIntentFixtureBootstrap[REDACTED]";}
    private static final class ScheduledMaintenance implements Maintenance {
        private final ScheduledThreadPoolExecutor executor=new ScheduledThreadPoolExecutor(1,r->{var thread=new Thread(r,"armoney-code-intent-fixture-maintenance");thread.setDaemon(true);return thread;});
        ScheduledMaintenance(){executor.setRemoveOnCancelPolicy(true);executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);}
        public void start(Runnable task){executor.scheduleWithFixedDelay(task,1,1,TimeUnit.SECONDS);}
        public void stop(){executor.shutdownNow();}
        public boolean await()throws InterruptedException{return executor.awaitTermination(2,TimeUnit.SECONDS);}
    }
}
