package com.arman.bank.userservice.infrastructure;
import com.arman.bank.userservice.application.LiveUserCurrentStore;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.thread.Scheduler;
public final class LiveUserCurrentRoutes implements AutoCloseable {
    public static final String PATH="/v1/internal/migrations/legacy/user-current",HEADER="X-ArMoney-Migration-User-Current-Key";
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final LiveUserCurrentStore store;private final LiveUserRevisionConfiguration configuration;private final Semaphore slots=new Semaphore(16);private volatile boolean closed;
    public LiveUserCurrentRoutes(LiveUserCurrentStore store,LiveUserRevisionConfiguration configuration){this.store=Objects.requireNonNull(store);this.configuration=Objects.requireNonNull(configuration);}
    public void configure(JavalinConfig config){if(configuration.enabled())config.routes.post(PATH,this::handle);}
    public void close(){closed=true;}
    private void handle(Context ctx){long started=System.nanoTime();boolean acquired=false;ctx.header("Cache-Control","no-store");try{
        String writer=authenticate(ctx);acquired=slots.tryAcquire();if(closed||!acquired)throw new LiveUserCurrentStore.Rejected(503);
        if(ctx.contentType()==null||!ctx.contentType().split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new IllegalArgumentException();
        byte[] bytes=readBody(ctx,started,4096);
        var response=store.current(writer,parse(bytes));HttpUserMutationSuspension.remaining(started);reply(ctx,200,response);
    }catch(LiveUserCurrentStore.Rejected e){int status=Set.of(400,401,409,503).contains(e.status())?e.status():503;reply(ctx,status,Map.of("error",status==409?"conflict":status==503?"unavailable":"rejected"));}
    catch(IllegalArgumentException|ArithmeticException e){reply(ctx,400,Map.of("error","rejected"));}
    catch(Exception e){reply(ctx,503,Map.of("error","unavailable"));}finally{if(acquired)slots.release();}}
    private String authenticate(Context ctx){if(Collections.list(ctx.req().getHeaders(HEADER)).size()!=1)throw new LiveUserCurrentStore.Rejected(401);String supplied=ctx.header(HEADER);if(supplied==null||!supplied.matches("[!-~]{32,256}"))throw new LiveUserCurrentStore.Rejected(401);String match=null;for(var e:configuration.currentWriters().entrySet())for(String key:e.getValue())if(MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),key.getBytes(StandardCharsets.US_ASCII)))match=e.getKey();if(match==null)throw new LiveUserCurrentStore.Rejected(401);return match;}
    public static Map<String,Object> parse(byte[] bytes){if(bytes==null||bytes.length>4096)throw new IllegalArgumentException("Live User body rejected");try(var p=JSON.getFactory().createParser(bytes)){while(p.nextToken()!=null)if(p.currentToken().isNumeric()){if(p.currentToken()!=JsonToken.VALUE_NUMBER_INT||!p.getText().matches("0|[1-9][0-9]*"))throw new IllegalArgumentException();p.getLongValue();}Map<String,Object> result=JSON.readValue(bytes,new TypeReference<LinkedHashMap<String,Object>>(){});if(result==null)throw new IllegalArgumentException();return result;}catch(Exception e){throw new IllegalArgumentException("Live User body rejected");}}
    public static byte[] encode(Object value){try{byte[] bytes=JSON.writeValueAsBytes(value);if(bytes.length>4096)throw new IllegalArgumentException("Live User body rejected");return bytes;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("Live User body rejected");}}
    private static void reply(Context ctx,int status,Object body){ctx.status(status).contentType("application/json").result(encode(body));}
    /** Shared technical body bound; no business parsing or authentication here. */
    static byte[] readBody(Context ctx,long originalStartedNanos,int maximum)throws IOException {
        if(maximum!=4096&&maximum!=12288)throw new IllegalArgumentException("Body bound rejected");
        try(var deadline=BodyDeadline.begin(ctx,originalStartedNanos)){return deadline.read(ctx.req().getInputStream(),maximum);}
    }
    /** HTTP/1 only: an absolute deadline physically closes its own Jetty endpoint.
     * Completion and timeout share a monitor, preventing a late keepalive close.
     */
    private static final class BodyDeadline implements AutoCloseable {
        private final Scheduler scheduler;private final EndPoint endpoint;private final long started;private Scheduler.Task task;private boolean ended;
        private BodyDeadline(Scheduler scheduler,EndPoint endpoint,long started){this.scheduler=scheduler;this.endpoint=endpoint;this.started=started;if(scheduler==null||!scheduler.isRunning()||endpoint==null||!endpoint.isOpen())throw new LiveUserCurrentStore.Rejected(503);}
        static BodyDeadline begin(Context ctx,long started){if(!"HTTP/1.1".equals(ctx.req().getProtocol()))throw new LiveUserCurrentStore.Rejected(400);var core=ServletContextRequest.getServletContextRequest(ctx.req());if(core==null)throw new LiveUserCurrentStore.Rejected(503);return new BodyDeadline(core.getComponents().getScheduler(),core.getConnectionMetaData().getConnection().getEndPoint(),started);}
        byte[] read(InputStream stream,int maximum)throws IOException{
            synchronized(this){long left=HttpUserMutationSuspension.remaining(started);task=scheduler.schedule(this::expire,left,TimeUnit.NANOSECONDS);if(task==null)throw new LiveUserCurrentStore.Rejected(503);}
            try{byte[] bytes=stream.readNBytes(maximum+1);synchronized(this){if(ended||bytes.length>maximum||System.nanoTime()-started>=4_000_000_000L){expire();throw new LiveUserCurrentStore.Rejected(503);}ended=true;cancel();}return bytes;}finally{close();}
        }
        private synchronized void expire(){if(!ended){ended=true;try{endpoint.close(new TimeoutException("live_user_body_deadline"));}finally{cancel();}}}
        private void cancel(){if(task!=null){var pending=task;task=null;pending.cancel();}}
        @Override public synchronized void close(){ended=true;cancel();}
    }
}
