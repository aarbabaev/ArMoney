package com.arman.bank.authservice.infrastructure;

import com.arman.bank.runtime.Database;
import com.arman.bank.authservice.domain.Verification.Purpose;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.arman.bank.authservice.infrastructure.ProductionProviderAcceptanceController.*;

/** Actual transport capture only. No enrollment/issue/consume/status calls or direct payload decryption. */
final class ProductionProviderAcceptanceDelivery implements AutoCloseable {
    private record Capture(ProductionProviderAcceptanceEvidence.Delivery metadata,UUID fence,String code){
        @Override public String toString(){return "ProductionAcceptanceCapture[redacted]";}
    }
    private final Map<UUID,Capture> captures=new LinkedHashMap<>();
    private final Set<UUID> acceptedFences=new HashSet<>();
    private final ProductionProviderAcceptanceEvidence evidence;
    private final ProductionProviderAcceptanceTransport.LocalSmtp smtp;
    private final VerificationDeliveryTransport transport;
    private final VerificationDeliveryWorker worker;
    private final HttpClient reader;
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final URI smsOrigin;
    private final String readToken;
    private final AtomicBoolean closed=new AtomicBoolean();
    ProductionProviderAcceptanceDelivery(Database database,ProductionProviderAcceptanceEvidence evidence,Map<String,String> env) throws Exception {
        this.evidence=Objects.requireNonNull(evidence);
        smsOrigin=URI.create(ProductionProviderAcceptanceTransport.required(env,"VERIFICATION_SMS_BASE_URL"));
        if(!URI.create("http://sms-sandbox:8080").equals(smsOrigin))throw new Rejected(503);
        readToken=ProductionProviderAcceptanceTransport.required(env,"SMS_SANDBOX_READ_TOKEN");
        String sendToken=ProductionProviderAcceptanceTransport.required(env,"VERIFICATION_SMS_SEND_TOKEN");
        if(!readToken.matches("[A-Za-z0-9_-]{32,128}")||!sendToken.matches("[A-Za-z0-9_-]{32,128}")||readToken.equals(sendToken))throw new Rejected(503);
        var mailEnv=new HashMap<>(env);mailEnv.put("LOGIN_NOTIFICATIONS_ENABLED","true");var mail=new EmailConfiguration(mailEnv);
        reader=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
        ProductionProviderAcceptanceTransport.LocalSmtp found=null;VerificationDeliveryTransport foundTransport=null;
        try{
            // Dedicated fresh sandbox inbox. Never erase or reuse pre-existing captures.
            if(!smsMessages(new Deadline()).isEmpty())throw new Rejected(503);
            found=new ProductionProviderAcceptanceTransport.LocalSmtp(mail.smtpUsername,mail.smtpPassword);smtp=found;smtp.recipient(evidence.email);
            var sender=new MailtrapSmtp(mail,"localhost",smtp.port(),smtp.clientTls.getSocketFactory(),3000);
            foundTransport=new VerificationDeliveryTransport(sender::send,smsOrigin,sendToken,false);transport=foundTransport;
            String id=ProductionProviderAcceptanceTransport.required(env,"VERIFICATION_KEY_ID");
            byte[] otp=ProductionProviderAcceptanceTransport.key(env,"VERIFICATION_OTP_HMAC_KEY"),encryption=ProductionProviderAcceptanceTransport.key(env,"VERIFICATION_DELIVERY_ENCRYPTION_KEY");
            VerificationCrypto crypto;
            try{crypto=new VerificationCrypto(id,Map.of(id,otp),Map.of(id,encryption));}finally{Arrays.fill(otp,(byte)0);Arrays.fill(encryption,(byte)0);}
            var outbox=new PostgresVerificationStore(database,crypto);
            worker=new VerificationDeliveryWorker(new VerificationDeliveryWorker.Outbox(){
                public Optional<PostgresVerificationStore.DeliveryLease> claim(){return outbox.claimDelivery();}
                public boolean finish(UUID delivery,UUID fence,boolean accepted){
                    boolean finished=outbox.finishDelivery(delivery,fence,accepted);
                    if(finished&&accepted)synchronized(captures){
                        Capture capture=captures.get(delivery);
                        if(capture!=null&&capture.fence.equals(fence))acceptedFences.add(fence);
                    }
                    return finished;
                }
            },crypto,new VerificationDeliveryWorker.Sender(){
                public boolean send(PostgresVerificationStore.DeliveryLease lease,VerificationCrypto.DeliveryPayload payload) throws InterruptedException {
                    if(closed.get())return false;
                    UUID owner=evidence.leaseOwner(lease,payload.destination(),new Deadline());
                    synchronized(captures){if(captures.size()>=16||captures.values().stream().anyMatch(c->c.metadata.purpose().equals(lease.purpose().name())&&!c.metadata.deliveryId().equals(lease.deliveryId())))return false;}
                    if(!transport.send(lease,payload))return false;
                    try{
                        // Read the received transport message, never return payload.code().
                        String code;
                        if(lease.purpose()==Purpose.REGISTRATION_EMAIL)code=smtp.code(evidence.email);
                        else if(lease.purpose()==Purpose.REGISTRATION_PHONE){
                            var messages=smsMessages(new Deadline());
                            if(messages.size()!=1)throw new Rejected(503);
                            var message=messages.getFirst();
                            if(!evidence.username.equals(message.path("destination").asText())||!"REGISTRATION_PHONE".equals(message.path("purpose").asText()))throw new Rejected(503);
                            code=ProductionProviderAcceptanceTransport.otp(message.path("text").textValue());
                        }else return false;
                        if(!code.equals(payload.code())||closed.get())return false;
                        var metadata=new ProductionProviderAcceptanceEvidence.Delivery(lease.deliveryId(),lease.challengeId(),lease.enrollmentId(),owner,lease.purpose().name(),lease.contactVersion(),payload.destination(),lease.expiresAt());
                        synchronized(captures){if(captures.putIfAbsent(lease.deliveryId(),new Capture(metadata,lease.fence(),code))!=null)return false;}
                        return true;
                    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
                    catch(Exception unavailable){return false;}
                }
                public void close(){transport.close();}
            },Clock.systemUTC());
        }catch(Exception failure){if(foundTransport!=null)foundTransport.close();if(found!=null)found.close();reader.shutdownNow();throw new Rejected(503);}
    }
    void start(){if(closed.get())throw new Rejected(503);worker.start();}
    Map<String,?> read(String purpose,Deadline deadline){
        while(true){
            deadline.remaining();if(closed.get())throw new Rejected(503);
            try{
                var current=evidence.currentDelivery(purpose,deadline);
                synchronized(captures){
                    Capture capture=captures.get(current.deliveryId());
                    if(capture==null||!capture.metadata.equals(current)||!acceptedFences.contains(capture.fence))throw new Rejected(503);
                    deadline.remaining();return Map.of("code",capture.code,"challenge_id",current.challengeId().toString());
                }
            }catch(Rejected absent){if(absent.status!=503)throw absent;}
            try{TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(25),deadline.remaining()));}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Rejected(503);}
        }
    }
    private List<com.fasterxml.jackson.databind.JsonNode> smsMessages(Deadline deadline) throws Exception {
        var bytes=new ProductionProviderAcceptanceTransport.Bytes(65536);
        var request=reader.sendAsync(HttpRequest.newBuilder(smsOrigin.resolve("/api/messages?purpose=REGISTRATION_PHONE"))
            .timeout(Duration.ofNanos(deadline.remaining())).header("Authorization","Bearer "+readToken).GET().build(),ignored->bytes);pending.add(request);byte[] raw=null;
        try{
            var result=request.get(deadline.remaining(),TimeUnit.NANOSECONDS);raw=result.body();
            if(result.statusCode()!=200||!result.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new Rejected(503);
            var array=JSON.readTree(ProductionProviderAcceptanceTransport.strictUtf8(raw));if(array==null||!array.isArray()||array.size()>16)throw new Rejected(503);
            var found=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for(var message:array){
                CodeIntentHttp.exact(message,Set.of("id","destination","text","purpose","createdAt"));
                for(String field:List.of("id","destination","text","purpose"))if(!message.path(field).isTextual())throw new Rejected(503);
                if(!message.path("createdAt").isIntegralNumber()||!message.path("createdAt").canConvertToLong()||message.path("text").textValue().length()>4096)throw new Rejected(503);
                ProductionProviderAcceptanceTransport.uuid(message.path("id").textValue());found.add(message);
            }return found;
        }finally{pending.remove(request);request.cancel(true);bytes.cancel();if(raw!=null)Arrays.fill(raw,(byte)0);}
    }
    @Override public void close(){if(!closed.compareAndSet(false,true))return;pending.forEach(p->p.cancel(true));try{worker.close();}finally{try{smtp.close();}finally{reader.shutdownNow();synchronized(captures){captures.clear();acceptedFences.clear();}}}}
    @Override public String toString(){return "ProductionProviderAcceptanceDelivery[redacted]";}
}
