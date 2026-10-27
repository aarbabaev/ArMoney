package com.arman.bank.authservice.infrastructure;

import com.arman.bank.runtime.Database;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.arman.bank.authservice.infrastructure.ProductionProviderAcceptanceController.*;

/** Separate disposable test launcher. Must never be included in Auth's production distribution. */
public final class ProductionProviderAcceptanceMain {
    private ProductionProviderAcceptanceMain(){}
    static void validate(Map<String,String> env){
        try{validateConfiguration(env);}catch(RuntimeException rejected){throw new Rejected(503);}
    }
    private static void validateConfiguration(Map<String,String> env){
        for(String flag:List.of("CI","PRODUCTION_PROVIDER_ACCEPTANCE_ISOLATED","PRODUCTION_PROVIDER_ACCEPTANCE_FRESH_FIXTURE","PRODUCTION_PROVIDER_ACCEPTANCE_EXCLUSIVE_POLICY_WRITER"))
            if(!"true".equals(env.get(flag)))throw new Rejected(503);
        if(!"false".equals(env.get("AUTH_VERIFICATION_DELIVERY_ENABLED")))throw new Rejected(503);
        for(var expected:Map.of("DB_URL","jdbc:postgresql://auth-db:5432/bank","DB_USER","bank","SSO_ENROLLMENT_STATUS_ORIGIN","http://sso-service:8080","VERIFICATION_SMS_BASE_URL","http://sms-sandbox:8080").entrySet())
            if(!expected.getValue().equals(env.get(expected.getKey())))throw new Rejected(503);
        validateCredentials(env);
        var config=new CodeConfirmationConfiguration(env);if(!config.enabled)throw new Rejected(503);
        String issuer=ProductionProviderAcceptanceTransport.required(env,"SSO_ISSUER");
        if(!config.scopes().managedIssuers().contains(issuer))throw new Rejected(503);
    }
    private static final Set<String> FIXTURE_KEYS=Set.of("AUTH_PRODUCTION_ACCEPTANCE_KEY","AUTH_PRODUCTION_ACCEPTANCE_ADMIN_SECRET");
    private static final Set<String> CSV_RINGS=Set.of("SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS","SSO_CODE_INTENT_DETAIL_ENCRYPTION_PREVIOUS_KEYS","AUTH_MANAGED_BANKING_PREVIOUS_KEYS");
    private static final Set<String> JSON_RINGS=Set.of("AUTH_MIGRATION_CUSTODY_ENCRYPTION_KEYS","AUTH_MIGRATION_DIGEST_KEYS","AUTH_MIGRATION_PROOF_KEYS",
        "AUTH_MIGRATION_OTP_HMAC_KEYS","AUTH_MIGRATION_OTP_ENCRYPTION_KEYS","ARMONEY_CODE_INTENT_FIXTURE_LOCAL_MATERIAL","ARMONEY_CODE_INTENT_CODE_HMAC_KEYS");
    /** Pure, bounded startup inventory only. Does not instantiate production configuration or resources. */
    static void validateCredentials(Map<String,String> env){
        var inventory=new LinkedHashMap<String,List<byte[]>>();
        try{
            if(env==null||env.size()>512)throw new Rejected(503);
            int total=0;
            for(String own:FIXTURE_KEYS){String value=env.get(own);LoginProofRoutes.credential(value);}
            for(var entry:env.entrySet()){
                String name=entry.getKey(),value=entry.getValue();if(name==null||value==null)throw new Rejected(503);
                boolean credential=name.contains("KEY")||name.contains("TOKEN")||name.contains("SECRET")||name.contains("PASSWORD")||name.contains("CREDENTIAL")
                    ||name.endsWith("_MATERIAL")||name.endsWith("_MATERIALS")||name.endsWith("_RING")||name.endsWith("_RINGS")||name.endsWith("_KEYRING")||name.equals("MAILTRAP_SMTP_USERNAME");
                if(!credential||value.isEmpty())continue;
                total=Math.addExact(total,value.length());if(total>65536||value.length()>16384)throw new Rejected(503);
                if(name.endsWith("_KEY_ID")||name.endsWith("_KEY_IDS")){
                    String[] ids=value.split(",",-1);if(ids.length>32||(!name.endsWith("_KEY_IDS")&&ids.length!=1))throw new Rejected(503);
                    var seen=new HashSet<String>();for(String id:ids)if(!id.matches("[A-Za-z0-9_-]{1,32}")||!seen.add(id))throw new Rejected(503);
                    continue;
                }
                if(CSV_RINGS.contains(name)){
                    var ids=new HashSet<String>();String[] members=value.split(",",-1);if(members.length>8)throw new Rejected(503);
                    for(String member:members){String[] pair=member.split(":",-1);
                        if(pair.length!=2||!pair[0].matches("[A-Za-z0-9_-]{1,32}")||!ids.add(pair[0]))throw new Rejected(503);
                        addMaterial(inventory,name+":"+pair[0],pair[1],32);
                    }
                }else if(JSON_RINGS.contains(name)){
                    var json=JSON.readTree(value);
                    if(json==null||!json.isObject()||json.size()<1||json.size()>(name.endsWith("_LOCAL_MATERIAL")?64:8))throw new Rejected(503);
                    var fields=json.fields();while(fields.hasNext()){var field=fields.next();
                        if(!field.getKey().matches(name.endsWith("_LOCAL_MATERIAL")?"[A-Za-z0-9._-]{1,64}":"[A-Za-z0-9_-]{1,32}")||!field.getValue().isTextual())throw new Rejected(503);
                        addMaterial(inventory,name+":"+field.getKey(),field.getValue().textValue(),name.endsWith("_LOCAL_MATERIAL")?0:32);
                    }
                }else{
                    // Unknown containers are not treated as an opaque scalar: their contents may hide aliases.
                    if(List.of("_KEYS","_SECRETS","_TOKENS","_PASSWORDS","_CREDENTIALS","_KEYRING","_RING","_RINGS","_MATERIAL","_MATERIALS","_FILE","_FILES").stream().anyMatch(name::endsWith)
                        ||value.startsWith("{")||value.startsWith("[")||value.indexOf(',')>=0||value.indexOf(';')>=0
                        ||value.matches("[A-Za-z0-9_-]{1,32}:[A-Za-z0-9_+/=-]+"))throw new Rejected(503);
                    addMaterial(inventory,name,value,-1);
                }
            }
            for(String own:FIXTURE_KEYS){var forms=inventory.get(own);if(forms==null)throw new Rejected(503);
                for(var other:inventory.entrySet())if(!other.getKey().equals(own))for(byte[] a:forms)for(byte[] b:other.getValue())
                    if(MessageDigest.isEqual(a,b))throw new Rejected(503);
            }
        }catch(Exception rejected){throw new Rejected(503);}
        finally{inventory.values().forEach(forms->forms.forEach(bytes->Arrays.fill(bytes,(byte)0)));}
    }
    private static void addMaterial(Map<String,List<byte[]>> inventory,String name,String value,int binarySize){
        if(inventory.size()>=256||value==null||value.isEmpty()||value.length()>(binarySize==0?8192:2048)||value.codePoints().anyMatch(c->c<33||c>126))throw new Rejected(503);
        var forms=new ArrayList<byte[]>();byte[] raw=value.getBytes(StandardCharsets.UTF_8);forms.add(raw);byte[] decoded=null;
        for(var decoder:List.of(Base64.getDecoder(),Base64.getUrlDecoder()))try{
            byte[] candidate=decoder.decode(value);
            boolean canonical=List.of(Base64.getEncoder().encodeToString(candidate),Base64.getEncoder().withoutPadding().encodeToString(candidate),
                Base64.getUrlEncoder().encodeToString(candidate),Base64.getUrlEncoder().withoutPadding().encodeToString(candidate)).contains(value);
            if(canonical&&decoded==null)decoded=candidate;else Arrays.fill(candidate,(byte)0);
        }catch(IllegalArgumentException notEncoded){/* Ordinary opaque credential, still compared as raw bytes. */}
        if(decoded!=null)forms.add(decoded);
        if(binarySize>=0&&(decoded==null||decoded.length<1||decoded.length>4096||(binarySize>0&&decoded.length!=binarySize))){forms.forEach(bytes->Arrays.fill(bytes,(byte)0));throw new Rejected(503);}
        inventory.put(name,forms);
    }
    public static void main(String[] args) throws Exception {
        Map<String,String> env=Map.copyOf(System.getenv());validate(env);
        Database database=null;ProductionProviderAcceptancePolicy policy=null;ProductionProviderAcceptanceDelivery delivery=null;ProductionProviderAcceptanceController controller=null;
        var stop=new CountDownLatch(1);var closed=new AtomicBoolean();
        var lifetime=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("production-acceptance-lifetime").factory());
        try{
            database=new Database(env.get("DB_URL"),env.get("DB_USER"),ProductionProviderAcceptanceTransport.required(env,"DB_PASSWORD"));
            var evidence=new ProductionProviderAcceptanceEvidence(database,env.get("SSO_ISSUER"),ProductionProviderAcceptanceTransport.required(env,"AUTH_PRODUCTION_ACCEPTANCE_USERNAME"),ProductionProviderAcceptanceTransport.required(env,"AUTH_PRODUCTION_ACCEPTANCE_EMAIL"));
            evidence.requireFresh(new Deadline()); // Before worker, listener or any policy mutation.
            var admin=new ProductionProviderAcceptancePolicy.AdminPeer(URI.create("http://sso-service:8080"),env.get("AUTH_PRODUCTION_ACCEPTANCE_ADMIN_SECRET"));
            try{policy=new ProductionProviderAcceptancePolicy(admin,new Deadline());}catch(Exception failure){admin.close();throw failure;}
            delivery=new ProductionProviderAcceptanceDelivery(database,evidence,env);
            var boundDelivery=delivery;var boundPolicy=policy;
            controller=new ProductionProviderAcceptanceController(env.get("AUTH_PRODUCTION_ACCEPTANCE_KEY"),evidence.username,new Operations(){
                public Map<String,?> delivery(String purpose,Deadline deadline){return boundDelivery.read(purpose,deadline);}
                public Map<String,?> outcomes(Deadline deadline){return evidence.outcomes(deadline);}
                public void removePolicy(Deadline deadline) throws Exception{boundPolicy.remove(deadline);}
                public void restorePolicy(Deadline deadline) throws Exception{boundPolicy.restore(deadline);}
            });
            var boundController=controller;var boundDatabase=database;
            Runnable cleanup=()->{
                if(!closed.compareAndSet(false,true))return;
                try{boundController.close();}finally{try{boundDelivery.close();}finally{try{boundPolicy.close();}finally{boundDatabase.close();stop.countDown();}}}
            };
            Runtime.getRuntime().addShutdownHook(new Thread(cleanup,"production-acceptance-shutdown"));
            delivery.start();controller.start("0.0.0.0",8092);
            lifetime.schedule(cleanup,10,TimeUnit.MINUTES);stop.await();
        }catch(Exception failure){
            // Only fixed diagnostic class/message leaves this launcher; never provider/SQL/request content.
            throw new Rejected(503);
        }finally{
            lifetime.shutdownNow();if(controller!=null)controller.close();if(delivery!=null)delivery.close();if(policy!=null)policy.close();if(database!=null)database.close();
        }
    }
}
