package com.arman.bank.sso;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.keycloak.util.JsonSerialization;

/** Disposable-only explicit launch state; no environment access or identity provisioning. */
public final class CodeIntentFixtureLaunchConfiguration implements AutoCloseable {
    static final String ENABLED="ARMONEY_CODE_INTENT_FIXTURE_ENABLED";
    static final List<String> GATES=List.of(ENABLED,"CI","CODE_SESSION_ACCEPTANCE_ISOLATED","ARMONEY_LOGIN_PROOF_ENABLED");
    static final List<String> INPUTS=List.of("SSO_LOGIN_PROOF_WRITE_KEY","SSO_CODE_INTENT_LOOKUP_KEY","SSO_LOGIN_PROOF_CONFIRM_KEY","AUTH_TO_SSO_STATUS_KEY","ARMONEY_PROVIDER_BINDING_KEY","ARMONEY_CODE_INTENT_FIXTURE_CONTROL_KEY","AUTH_CODE_INTENT_FIXTURE_PROVIDER_KEY","ARMONEY_FIXTURE_PEER_KEY","SSO_ISSUER","SSO_VERIFICATION_CLIENT_IDS","ARMONEY_SSO_MANAGED_PAIRS","ARMONEY_SSO_LEGACY_PAIRS","ARMONEY_AUTH_BRIDGE_URL","SSO_LOGIN_PROOF_WRITER_ID","SSO_CODE_INTENT_ACTORS","SSO_CODE_INTENT_CODE_KEY_IDS","SSO_CODE_INTENT_ISSUERS","SSO_CODE_INTENT_CLIENTS","ARMONEY_CODE_INTENT_FIXTURE_SUBJECT","ARMONEY_CODE_INTENT_FIXTURE_LOCAL_MATERIAL");
    private enum State { NEW, OFF, CONFIGURED, STARTING, STARTED, FAILED, CLOSED }
    interface Starter { AutoCloseable start(Map<String,String> config,Map<String,byte[]> material,CodeIntentFixtureSupport.Settings settings); }
    private final Starter starter;
    private State state=State.NEW;
    private Map<String,String> identity,configuration;
    private Map<String,byte[]> material;
    private CodeIntentFixtureSupport.Settings settings;
    private AutoCloseable owner;
    public CodeIntentFixtureLaunchConfiguration(){this((config,material,settings)->CodeIntentFixtureBootstrap.start(config,material,settings).orElseThrow());}
    CodeIntentFixtureLaunchConfiguration(Starter starter){this.starter=Objects.requireNonNull(starter);}
    synchronized boolean selectOffGate(String enabled){
        if(state==State.CLOSED||state==State.FAILED||state==State.STARTING)throw invalid();
        if(state==State.OFF){if(!Objects.equals(identity.get(ENABLED),enabled))throw invalid();return true;}
        return false;
    }
    public synchronized Optional<CodeIntentFixtureLaunchConfiguration> configure(Map<String,String> explicitAllowlist){
        try{
            Objects.requireNonNull(explicitAllowlist);
            for(String name:explicitAllowlist.keySet())if(!GATES.contains(name)&&!INPUTS.contains(name))throw invalid();
            if(state!=State.NEW){if((state==State.OFF||state==State.CONFIGURED||state==State.STARTED)&&identity.equals(explicitAllowlist))return state==State.OFF?Optional.empty():Optional.of(this);throw invalid();}
            var supplied=Collections.unmodifiableMap(new HashMap<>(explicitAllowlist));
            String enabled=supplied.get(ENABLED);
            if(enabled==null&&!supplied.containsKey(ENABLED)||"false".equals(enabled)){identity=supplied;state=State.OFF;return Optional.empty();}
            if(!"true".equals(enabled))throw invalid();
            for(String name:GATES)if(!"true".equals(supplied.get(name)))throw invalid();
            for(var entry:supplied.entrySet()){scalar(entry.getKey());scalar(Objects.requireNonNull(entry.getValue()));}
            var decoded=decode(supplied.get("ARMONEY_CODE_INTENT_FIXTURE_LOCAL_MATERIAL"));
            var config=new HashMap<>(supplied);config.keySet().removeAll(List.of(ENABLED,"CI","CODE_SESSION_ACCEPTANCE_ISOLATED","ARMONEY_CODE_INTENT_FIXTURE_SUBJECT","ARMONEY_CODE_INTENT_FIXTURE_LOCAL_MATERIAL"));
            var s=new CodeIntentFixtureSupport.Settings(config.get("ARMONEY_CODE_INTENT_FIXTURE_CONTROL_KEY"),config.get("ARMONEY_FIXTURE_PEER_KEY"),config.get("AUTH_CODE_INTENT_FIXTURE_PROVIDER_KEY"),config.get("ARMONEY_PROVIDER_BINDING_KEY"),config.get("SSO_ISSUER"),supplied.get("ARMONEY_CODE_INTENT_FIXTURE_SUBJECT"),config.get("SSO_VERIFICATION_CLIENT_IDS"));
            var parsed=CodeIntentConfiguration.from(config,decoded);
            if(!parsed.enabled()||!parsed.managedPairs().equals(Set.of(new CodeIntentPair(s.issuer(),s.clientId())))||!parsed.legacyPairs().isEmpty()
                    ||!"http://auth-service:8080".equals(config.get("ARMONEY_AUTH_BRIDGE_URL"))||!UUID.fromString(s.subject()).toString().equals(s.subject()))throw invalid();
            CodeIntentFixtureSupport.credential(config.get("AUTH_TO_SSO_STATUS_KEY"));
            identity=supplied;configuration=Collections.unmodifiableMap(config);material=decoded;settings=s;state=State.CONFIGURED;return Optional.of(this);
        }catch(Throwable failure){if(state==State.NEW)state=State.FAILED;throw invalid();}
    }
    public synchronized void startConfigured(){
        if(state==State.OFF||state==State.STARTED)return;
        if(state!=State.CONFIGURED)throw invalid();
        state=State.STARTING;
        try{owner=Objects.requireNonNull(starter.start(configuration,copy(material),settings));state=State.STARTED;}
        catch(Throwable failure){state=State.FAILED;throw invalid();}
    }
    @Override public synchronized void close(){
        if(state==State.CLOSED)return;
        state=State.CLOSED;var closing=owner;owner=null;
        try{if(closing!=null)closing.close();}catch(Throwable failure){throw invalid();}
        finally{identity=null;configuration=null;material=null;settings=null;}
    }
    private static Map<String,byte[]> copy(Map<String,byte[]> source){var result=new HashMap<String,byte[]>();source.forEach((k,v)->result.put(k,v.clone()));return Collections.unmodifiableMap(result);}
    static Map<String,byte[]> decode(String json){
        try{
            scalar(Objects.requireNonNull(json));
            if(json.startsWith("\uFEFF")||json.indexOf('\0')>=0||json.getBytes(StandardCharsets.UTF_8).length>16384)throw invalid();
            var mapper=JsonSerialization.mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var root=mapper.readTree(json);if(root==null||!root.isObject()||root.size()==0||root.size()>64)throw invalid();
            Map<String,byte[]> result=new HashMap<>();var entries=root.fields();while(entries.hasNext()){
                var entry=entries.next();if(!entry.getKey().matches("[A-Za-z0-9._-]{1,64}")||!entry.getValue().isTextual())throw invalid();
                String encoded=entry.getValue().textValue();scalar(encoded);byte[] bytes=Base64.getDecoder().decode(encoded);
                if(bytes.length<1||bytes.length>4096||!Base64.getEncoder().encodeToString(bytes).equals(encoded))throw invalid();result.put(entry.getKey(),bytes);
            }return copy(result);
        }catch(Throwable failure){throw invalid();}
    }
    private static void scalar(String value){for(int i=0;i<value.length();i++)if(Character.isSurrogate(value.charAt(i))){if(!Character.isHighSurrogate(value.charAt(i))||++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}}
    static IllegalStateException invalid(){return new IllegalStateException("Fixture launch unavailable");}
    @Override public String toString(){return "CodeIntentFixtureLaunchConfiguration[REDACTED]";}
}
