package com.arman.bank.authservice.infrastructure

import com.arman.bank.runtime.Database
import org.testcontainers.containers.PostgreSQLContainer
import java.net.http.*
import java.nio.file.*
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Actual child JVM ownership, permitted only in root's disposable isolated runner. */
final class LiveMigrationMainFixture implements AutoCloseable {
    final PostgreSQLContainer authPg
    final PostgreSQLContainer userPg
    final Database authDb
    final HttpClient http
    final int authPort
    final Path hosts
    final List<Process> children=[]
    final List<Thread> drainers=[]
    static final String SERVICE='synthetic-main-service-credential-unique'
    static final String CURRENT='synthetic-main-current-credential-unique'
    static final String MUTATION='synthetic-main-mutation-credential-unique'
    static final String PROJECTION='synthetic-projection-credential-unique'
    static final String PROJECTION_WRITER='auth-projection'
    static final String CURRENT_WRITER='auth-current'
    static final String MUTATION_WRITER='user-mutation'
    static void requireIsolation(){
        if(System.getProperty('armoney.test.isolated.user.fixture')!='true')throw new IllegalStateException('Root-owned disposable fixture required')
    }
    LiveMigrationMainFixture(){
        requireIsolation() // BEFORE files, ports, processes or databases.
        new ServerSocket(8080,0,InetAddress.getByName('0.0.0.0')).close() // Fail closed; never replace another listener.
        int selected
        new ServerSocket(0,0,InetAddress.loopbackAddress).withCloseable{selected=it.localPort}
        authPort=selected
        hosts=Files.createTempFile('armoney-owned-jvm-hosts-','.txt')
        Files.writeString(hosts,'127.0.0.1 localhost user-service\n::1 localhost\n')
        http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
        PostgreSQLContainer a=null,u=null;Database db=null
        try{
            a=new PostgreSQLContainer('postgres:17.6-alpine');a.start()
            u=new PostgreSQLContainer('postgres:17.6-alpine');u.start()
            db=new Database(a.jdbcUrl,a.username,a.password)
            authPg=a;userPg=u;authDb=db
        }catch(Throwable failure){db?.close();u?.stop();a?.stop();http.close();Files.deleteIfExists(hosts);throw failure}
    }
    URI authOrigin(){URI.create('http://127.0.0.1:'+authPort)}
    URI userOrigin(){URI.create('http://127.0.0.1:8080')}
    void start(boolean enabled,Map authExtra=[:]){
        Map user=[INTERNAL_AUTH_KEY:SERVICE]
        if(enabled)user.putAll([
            USER_MIGRATION_BASELINE_ENABLED:'true',USER_MIGRATION_WRITER_ID:'migration',
            AUTH_TO_USER_MIGRATION_STAGE_KEY:authExtra.AUTH_TO_USER_MIGRATION_STAGE_KEY,
            AUTH_TO_USER_MIGRATION_PUBLICATION_KEY:authExtra.AUTH_TO_USER_MIGRATION_PUBLICATION_KEY,
            USER_TO_AUTH_MIGRATION_DECISION_KEY:authExtra.USER_TO_AUTH_MIGRATION_DECISION_KEY,
            AUTH_MIGRATION_PRIMARY_ORIGIN:authOrigin().toString(),USER_LIVE_REVISION_ENABLED:'true',
            USER_LIVE_CURRENT_WRITER_ID:CURRENT_WRITER,AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY:CURRENT,
            USER_LIVE_MUTATION_WRITER_ID:MUTATION_WRITER,USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY:MUTATION,
            USER_VERIFICATION_PROJECTION_ENABLED:'true',USER_VERIFICATION_PROJECTION_WRITER_ID:PROJECTION_WRITER,
            AUTH_TO_USER_VERIFICATION_PROJECTION_KEY:PROJECTION])
        spawn('user-service',userPg,8080,user)
        awaitReady(userOrigin())
        spawn('auth-service',authPg,authPort,[INTERNAL_AUTH_KEY:SERVICE,USER_BASE_URL:'http://user-service:8080']+authExtra)
        awaitReady(authOrigin())
    }
    private void spawn(String module,PostgreSQLContainer pg,int port,Map selected){
        Path root=Path.of('..').toAbsolutePath().normalize()
        Path lib=root.resolve(module+'/build/install/'+module+'/lib')
        if(!Files.isDirectory(lib))throw new IllegalStateException('Actual Main installDist required')
        String java=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').toLowerCase().contains('win')?'java.exe':'java').toString()
        def builder=new ProcessBuilder(java,'-Xms32m','-Xmx256m','-Djdk.net.hosts.file='+hosts.toAbsolutePath(),'-cp',lib.toString()+File.separator+'*','com.arman.bank.'+(module=='auth-service'?'authservice':'userservice')+'.Main')
        def env=builder.environment();def essential=[:]
        env.each{k,v->if(k.toUpperCase(Locale.ROOT) in ['SYSTEMROOT','WINDIR','TEMP','TMP','PATH'])essential[k]=v}
        env.clear();env.putAll(essential)
        env.putAll([DB_URL:pg.jdbcUrl,DB_USER:pg.username,DB_PASSWORD:pg.password,PORT:port.toString()])
        selected.each{k,v->if(v==null)throw new IllegalArgumentException('Missing synthetic configuration');env.put(k.toString(),v.toString())}
        def child=builder.redirectErrorStream(true).start();children.add(child)
        // Drain and discard: never retain credentials, contacts, OTPs or raw process errors.
        def drainer=new Thread({->try{child.inputStream.withCloseable{s->byte[] bytes=new byte[4096];while(s.read(bytes)!=-1)Arrays.fill(bytes,(byte)0)}}catch(IOException ignored){}} as Runnable,'live-main-private-drain')
        drainer.daemon=true;drainers.add(drainer);drainer.start()
    }
    void awaitReady(URI origin){
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(35)
        boolean ready=false
        while(!ready&&System.nanoTime()<until&&children.every{it.alive}){
            try{ready=request(origin,'GET','/health/ready').statusCode()==200}catch(IOException ignored){}
            if(!ready)Thread.sleep(50)
        }
        assert ready:'Actual private Main readiness failed; process output intentionally not exported'
    }
    HttpResponse<String> request(URI origin,String method,String path,Map headers=[:],String body=''){
        def b=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(6))
        headers.each{k,v->b.header(k.toString(),v.toString())}
        http.send(b.method(method,method=='POST'||method=='PUT'?HttpRequest.BodyPublishers.ofString(body):HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
    }
    long userCount(String table){
        if(!(table in ['registration_phone_claims','profile_directory','profiles','verification_projection_current','migration_baselines']))throw new IllegalArgumentException('Fixed fixture table required')
        java.sql.DriverManager.getConnection(userPg.jdbcUrl,userPg.username,userPg.password).withCloseable{c->c.createStatement().withCloseable{s->s.executeQuery('select count(*) from '+table).withCloseable{r->r.next();r.getLong(1)}}}
    }
    @Override void close(){
        Throwable failure=null
        children.each{it.destroy()}
        children.each{p->try{if(!p.waitFor(8,TimeUnit.SECONDS)){p.destroyForcibly();assert p.waitFor(5,TimeUnit.SECONDS)}}catch(Throwable e){if(failure==null)failure=e;else failure.addSuppressed(e)}}
        drainers.each{t->try{t.join(3000);assert !t.alive}catch(Throwable e){if(failure==null)failure=e;else failure.addSuppressed(e)}}
        for(Closure action:[{http.close()},{authDb.close()},{userPg.stop()},{authPg.stop()},{Files.deleteIfExists(hosts)}])try{action()}catch(Throwable e){if(failure==null)failure=e;else failure.addSuppressed(e)}
        if(failure!=null)throw failure
    }
}
