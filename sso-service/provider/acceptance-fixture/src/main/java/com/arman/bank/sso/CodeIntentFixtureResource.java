package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.keycloak.models.*;
import org.keycloak.services.Urls;
import org.keycloak.util.JsonSerialization;
import java.io.InputStream;
import java.util.*;

/** Authenticated disposable controls; never include this resource in the production artifact. */
@Path("")
@jakarta.ws.rs.ext.Provider
public final class CodeIntentFixtureResource {
    private final KeycloakSession session;
    private final CodeIntentFixtureBodyDeadline deadline;
    public CodeIntentFixtureResource(KeycloakSession session){this(session,null);}
    CodeIntentFixtureResource(KeycloakSession session,CodeIntentFixtureBodyDeadline deadline){this.session=session;this.deadline=deadline;}
    @POST @Path("{operation}") @Consumes("application/json") @Produces("application/json")
    public Response control(@PathParam("operation")String operation,@Context HttpHeaders headers,InputStream body){
        final CodeIntentFixtureSupport support;boolean peer="membership".equals(operation);
        try{support=CodeIntentFixtureSupport.current();var keys=headers.getRequestHeader(peer?"X-ArMoney-Fixture-Peer-Key":"X-ArMoney-Code-Intent-Fixture-Control-Key");
            if(keys==null||keys.size()!=1||!support.authenticate(keys.getFirst(),peer)){if(deadline!=null)deadline.close();return reply(401,null);}
        }catch(RuntimeException unavailable){if(deadline!=null)deadline.close();return reply(503,null);}
        try{
            if(deadline==null)throw new CodeIntentFixtureSupport.Rejected(503);
            deadline.requireOpen();JsonNode input=read(body);deadline.bodyComplete();Map<String,Object> result;
            deadline.requireOpen();
            switch(operation){
                case "arm"->{fields(input,"subject","client_id");result=Map.of("experiment_handle",support.arm(text(input,"subject"),text(input,"client_id")));}
                case "state"->{fields(input,"experiment_handle");result=Map.of("state",support.state(handle(input,"experiment_handle")));}
                case "cancel"->{fields(input,"experiment_handle");result=Map.of("state",support.cancel(handle(input,"experiment_handle")));}
                case "bind-stage"->{fields(input,"experiment_handle","command_id");result=Map.of("state",support.bindStage(handle(input,"experiment_handle"),LoginProof.uuid(text(input,"command_id"))));}
                case "confirm"->{fields(input,"experiment_handle","command_id");result=Map.of("state",support.confirm(handle(input,"experiment_handle"),LoginProof.uuid(text(input,"command_id"))));}
                case "membership"->{fields(input,"issuer","subject","client_id","user_session_id");result=membership(input);}
                default->{return reply(404,null);}
            }
            return reply(200,result);
        }catch(CodeIntentFixtureSupport.Rejected denied){return reply(denied.status,null);}
        catch(IllegalArgumentException|java.io.IOException malformed){return reply(400,null);}
        catch(RuntimeException unavailable){return reply(503,null);}
        finally{if(deadline!=null)deadline.close();}
    }
    private Map<String,Object> membership(JsonNode input){
        String issuer=text(input,"issuer"),subject=text(input,"subject"),clientId=text(input,"client_id"),sid=text(input,"user_session_id");
        var realm=session.getContext().getRealm();String actual=Urls.realmIssuer(session.getContext().getUri().getBaseUri(),realm.getName());
        if(!realm.getName().equals("armoney")||!issuer.equals(actual)||!Set.of("armoney-ios","armoney-android","armoney-web").contains(clientId))throw new CodeIntentFixtureSupport.Rejected(409);
        var client=realm.getClientByClientId(clientId);var us=session.sessions().getUserSession(realm,sid);
        boolean user=us!=null&&us.getRealm()!=null&&realm.getId().equals(us.getRealm().getId())&&sid.equals(us.getId())&&us.getUser()!=null&&subject.equals(us.getUser().getId());
        var cs=user&&client!=null&&client.isEnabled()&&client.isPublicClient()?us.getAuthenticatedClientSessionByClient(client.getId()):null;
        boolean member=cs!=null&&cs.getClient()!=null&&client.getId().equals(cs.getClient().getId())&&cs.getUserSession()!=null&&sid.equals(cs.getUserSession().getId())&&cs.getUserSession().getUser()!=null&&subject.equals(cs.getUserSession().getUser().getId());
        return Map.of("issuer",actual,"subject",subject,"client_id",clientId,"user_session_id",sid,"user_session_present",user,"client_session_present",member);
    }
    static JsonNode read(InputStream body)throws java.io.IOException{
        long start=System.nanoTime();var bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[2048];
        while(true){if(System.nanoTime()-start>=4_000_000_000L)throw new CodeIntentFixtureSupport.Rejected(503);int count=body.read(buffer,0,Math.min(buffer.length,12289-bytes.size()));if(count<0)break;bytes.write(buffer,0,count);if(bytes.size()>12288)throw new IllegalArgumentException();}
        if(System.nanoTime()-start>=4_000_000_000L)throw new CodeIntentFixtureSupport.Rejected(503);
        return strictUtf8(bytes.toByteArray());
    }
    static JsonNode strictUtf8(byte[] bytes)throws java.io.IOException{
        try{
            if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191)throw new java.io.IOException("Invalid fixture JSON");
            String decoded=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if(decoded.indexOf('\u0000')>=0)throw new java.io.IOException("Invalid fixture JSON");
            return Bridge.strictJson(decoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(java.nio.charset.CharacterCodingException invalid){throw new java.io.IOException("Invalid fixture JSON");}
    }
    static void fields(JsonNode input,String... names){LoginProof.fields(input,Set.of(names));}
    static String text(JsonNode input,String name){String value=LoginProof.text(input,name);if(!CodeIntentPair.safe(value,name.equals("issuer")?512:255))throw new IllegalArgumentException();return value;}
    static String handle(JsonNode input,String name){String value=text(input,name);SessionBinding.decode(value);return value;}
    private static Response reply(int status,Object body){return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).header("Cache-Control","no-store").entity(body).build();}
}
