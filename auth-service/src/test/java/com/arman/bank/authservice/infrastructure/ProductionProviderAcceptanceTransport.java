package com.arman.bank.authservice.infrastructure;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import static com.arman.bank.authservice.infrastructure.ProductionProviderAcceptanceController.Rejected;

/** Disposable transport mechanics only. No Auth use cases, database or authority factories. */
final class ProductionProviderAcceptanceTransport {
    private ProductionProviderAcceptanceTransport(){}
    static String required(Map<String,String> env,String name){
        String value=env.get(name);if(value==null||value.isBlank())throw new Rejected(503);return value;
    }
    static byte[] key(Map<String,String> env,String name){
        String value=required(env,name);byte[] decoded;
        try{decoded=Base64.getDecoder().decode(value);}catch(IllegalArgumentException invalid){throw new Rejected(503);}
        if(decoded.length!=32||!Base64.getEncoder().encodeToString(decoded).equals(value)){Arrays.fill(decoded,(byte)0);throw new Rejected(503);}return decoded;
    }
    static UUID uuid(String value){
        if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new Rejected(503);
        return UUID.fromString(value);
    }
    /** One process-owned SMTP/STARTTLS peer, trust context and finite inbox. No message diagnostics. */
    static final class LocalSmtp implements AutoCloseable {
        private final Path directory,keyStore,passwordFile;
        private final ServerSocket listener;
        private volatile Socket active;
        private final Thread server;
        private final SSLContext serverTls;
        final SSLContext clientTls;
        private final String username,password;
        private final AtomicBoolean closed=new AtomicBoolean();
        private final BlockingQueue<String> messages=new ArrayBlockingQueue<>(1);
        private volatile boolean failed;
        private String recipient;
        LocalSmtp(String username,String password) throws Exception {
            this.username=username;this.password=password;
            directory=Files.createTempDirectory("armoney-production-smtp-test-");keyStore=directory.resolve("ephemeral.p12");passwordFile=directory.resolve("keystore-password");
            ServerSocket socket=null;SSLContext serverContext=null,clientContext=null;
            char[] storePassword=new char[0];
            try{
                byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);storePassword=Base64.getUrlEncoder().withoutPadding().encodeToString(secret).toCharArray();Arrays.fill(secret,(byte)0);
                Files.writeString(passwordFile,new String(storePassword),StandardCharsets.US_ASCII);
                String tool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"keytool.exe":"keytool").toString();
                Process child=new ProcessBuilder(tool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-storetype","PKCS12",
                    "-keystore",keyStore.toString(),"-storepass:file",passwordFile.toString(),"-keypass:file",passwordFile.toString(),
                    "-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","2","-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                try{if(!child.waitFor(30,TimeUnit.SECONDS)||child.exitValue()!=0)throw new Rejected(503);}
                finally{if(child.isAlive()){child.destroyForcibly();if(!child.waitFor(5,TimeUnit.SECONDS))throw new Rejected(503);}}
                KeyStore keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(keyStore)){keys.load(input,storePassword);}
                var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,storePassword);
                var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
                serverContext=SSLContext.getInstance("TLS");serverContext.init(km.getKeyManagers(),null,null);
                clientContext=SSLContext.getInstance("TLS");clientContext.init(null,tm.getTrustManagers(),null);
                socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));socket.setSoTimeout(1000);
            }catch(Exception failure){if(socket!=null)socket.close();erase();throw new Rejected(503);}
            finally{Arrays.fill(storePassword,'\0');Files.deleteIfExists(passwordFile);}
            listener=socket;serverTls=serverContext;clientTls=clientContext;
            server=Thread.ofPlatform().name("production-acceptance-local-smtp").daemon(true).start(this::serve);
        }
        int port(){return listener.getLocalPort();}
        synchronized void recipient(String value){if(closed.get()||failed||recipient!=null||value==null)throw new Rejected(503);recipient=value;messages.clear();}
        synchronized void release(String value){if(Objects.equals(value,recipient)){recipient=null;messages.clear();}}
        String code(String expected) throws InterruptedException {
            synchronized(this){if(failed||closed.get()||!Objects.equals(expected,recipient))throw new Rejected(503);}
            String value=messages.poll(100,TimeUnit.MILLISECONDS);if(value==null||!messages.isEmpty())throw new Rejected(503);return value;
        }
        private void serve(){
            while(!closed.get())try{
                active=listener.accept();active.setSoTimeout(5000);conversation(active);
            }catch(SocketTimeoutException idle){/* Owned finite listener wait. */}
            catch(Exception invalid){if(!closed.get())failed=true;}
            finally{Socket socket=active;active=null;if(socket!=null)try{socket.close();}catch(IOException ignored){}}
        }
        private void conversation(Socket socket) throws Exception {
            InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();boolean tls=false,auth=false,acceptedRecipient=false;
            reply(output,"220 local SMTP test");
            while(!closed.get()){
                String command=line(input,4096);if(command==null)return;
                if(command.startsWith("EHLO ")){reply(output,"250-localhost");if(!tls)reply(output,"250-STARTTLS");reply(output,"250 AUTH PLAIN");}
                else if(command.equals("STARTTLS")&&!tls){
                    reply(output,"220 Begin TLS");socket=serverTls.getSocketFactory().createSocket(socket,"localhost",port(),true);active=socket;
                    ((SSLSocket)socket).setUseClientMode(false);socket.setSoTimeout(5000);((SSLSocket)socket).startHandshake();input=socket.getInputStream();output=socket.getOutputStream();tls=true;
                }else if(command.startsWith("AUTH PLAIN ")&&tls){
                    byte[] raw=Base64.getDecoder().decode(command.substring(11));
                    try{
                        String credentials=strictUtf8(raw);int first=credentials.indexOf(0),second=credentials.indexOf(0,first+1);
                        if(first<0||second<0||(!credentials.substring(0,first).isEmpty()&&!username.equals(credentials.substring(0,first)))||credentials.indexOf(0,second+1)>=0||!username.equals(credentials.substring(first+1,second))||!password.equals(credentials.substring(second+1)))throw new Rejected(503);
                        auth=true;reply(output,"235 Authenticated");
                    }finally{Arrays.fill(raw,(byte)0);}
                }else if(command.startsWith("MAIL FROM:")&&auth)reply(output,"250 Sender accepted");
                else if(command.startsWith("RCPT TO:")&&auth){
                    String expected;synchronized(this){expected=recipient;}
                    if(expected==null||!command.equalsIgnoreCase("RCPT TO:<"+expected+">"))throw new Rejected(503);
                    acceptedRecipient=true;reply(output,"250 Recipient accepted");
                }else if(command.equals("DATA")&&auth&&acceptedRecipient){
                    reply(output,"354 Send content");var bytes=new ByteArrayOutputStream();
                    for(int count=0;count<512;count++){
                        String row=line(input,4096);if(row==null)throw new Rejected(503);if(row.equals("."))break;
                        if(row.startsWith(".."))row=row.substring(1);byte[] part=(row+"\r\n").getBytes(StandardCharsets.US_ASCII);
                        if(bytes.size()+part.length>65536)throw new Rejected(503);bytes.writeBytes(part);if(count==511)throw new Rejected(503);
                    }
                    byte[] raw=bytes.toByteArray();
                    try{
                        var message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(raw));
                        if(message.getAllRecipients()==null||message.getAllRecipients().length!=1)throw new Rejected(503);
                        String expected;synchronized(this){expected=recipient;}
                        if(!(message.getAllRecipients()[0] instanceof jakarta.mail.internet.InternetAddress address)||!Objects.equals(address.getAddress(),expected))throw new Rejected(503);
                        Object content=message.getContent();String text=null;
                        if(content instanceof Multipart multipart){for(int n=0;n<multipart.getCount();n++){var part=multipart.getBodyPart(n);if(part.isMimeType("text/plain")){if(text!=null)throw new Rejected(503);text=part.getContent().toString();}}}
                        else if(message.isMimeType("text/plain"))text=content.toString();
                        if(!messages.offer(otp(text)))throw new Rejected(503);
                    }finally{Arrays.fill(raw,(byte)0);bytes.reset();}
                    reply(output,"250 Accepted");
                }else if(command.equals("QUIT")){reply(output,"221 Closed");return;}
                else throw new Rejected(503);
            }
        }
        private static String line(InputStream input,int max) throws IOException {
            var bytes=new ByteArrayOutputStream();
            for(int size=0;size<=max;size++){int value=input.read();if(value<0)return null;if(value=='\n'){byte[] row=bytes.toByteArray();int length=row.length;if(length==0||row[length-1]!='\r')throw new Rejected(503);return new String(row,0,length-1,StandardCharsets.US_ASCII);}if(value>127||value==0)throw new Rejected(503);bytes.write(value);}throw new Rejected(503);
        }
        private static void reply(OutputStream output,String value) throws IOException {output.write((value+"\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();}
        private void erase() throws IOException {Files.deleteIfExists(passwordFile);Files.deleteIfExists(keyStore);Files.deleteIfExists(directory);}
        @Override public void close(){
            if(!closed.compareAndSet(false,true))return;
            try{listener.close();}catch(IOException ignored){}Socket socket=active;if(socket!=null)try{socket.close();}catch(IOException ignored){}
            server.interrupt();try{server.join(5000);if(server.isAlive())throw new Rejected(503);erase();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Rejected(503);}catch(IOException invalid){throw new Rejected(503);}
            synchronized(this){recipient=null;messages.clear();}
        }
    }
    static String strictUtf8(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    static String otp(String text){
        if(text==null||text.length()>4096)throw new Rejected(503);
        var match=java.util.regex.Pattern.compile("Your ArMoney registration verification code is ([0-9]{6})\\. It expires in 5 minutes\\. Do not share this code\\.").matcher(text.strip());
        if(!match.matches())throw new Rejected(503);return match.group(1);
    }
    static final class Bytes implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        private boolean terminal;
        Bytes(int limit){this.limit=limit;}
        public CompletionStage<byte[]> getBody(){return result;}
        public synchronized void onSubscribe(java.util.concurrent.Flow.Subscription value){
            if(terminal||subscription!=null){value.cancel();return;}
            subscription=value;value.request(1);
        }
        public synchronized void onNext(List<java.nio.ByteBuffer> items){
            if(terminal)return;
            if(subscription==null){fail();return;}
            try{
                for(var item:items){
                    if(item.remaining()>limit-bytes.size())throw new Rejected(503);
                    byte[] part=new byte[item.remaining()];item.get(part);
                    try{bytes.writeBytes(part);}finally{Arrays.fill(part,(byte)0);}
                }
                subscription.request(1);
            }catch(RuntimeException failed){fail();}
        }
        public synchronized void onError(Throwable error){if(!terminal)fail();}
        public synchronized void onComplete(){
            if(terminal)return;
            terminal=true;result.complete(bytes.toByteArray());bytes.reset();
        }
        private void fail(){
            terminal=true;
            try{if(subscription!=null)subscription.cancel();}
            finally{bytes.reset();result.completeExceptionally(new Rejected(503));}
        }
        synchronized void cancel(){if(!terminal)fail();}
    }
}
