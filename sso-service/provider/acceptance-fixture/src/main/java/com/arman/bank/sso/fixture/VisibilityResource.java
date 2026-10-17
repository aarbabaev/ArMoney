package com.arman.bank.sso.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.util.JsonSerialization;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Private diagnostic control, not a verification or proof authority. */
@Path("")
public final class VisibilityResource {
    private final KeycloakSession session;
    public VisibilityResource(KeycloakSession session) { this.session=session; }
    @POST @Path("{operation}") @Consumes("application/json") @Produces("application/json")
    public Response control(@PathParam("operation") String operation,
                            @HeaderParam("X-ArMoney-Visibility-Key") String supplied,InputStream body) {
        try {
            if (!VisibilityState.equal(VisibilityState.key(),supplied)) return reply(401,null);
        } catch (RuntimeException unavailable) { return reply(503,null); }
        try {
            byte[] bytes=body.readNBytes(2049);
            if(bytes.length>2048) return reply(400,null);
            var mapper=JsonSerialization.mapper.copy();
            mapper.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode input=mapper.readTree(bytes);
            if(input==null || !input.isObject()) return reply(400,null);
            ObjectNode out=JsonSerialization.mapper.createObjectNode();
            if(operation.equals("arm")) {
                fields(input,"subject","client_id","mode");
                String mode=text(input,"mode");
                if(!Set.of("COMPLETE","FAIL").contains(mode)) return reply(400,null);
                var e=VisibilityState.arm(session.getContext().getRealm().getId(),text(input,"subject"),text(input,"client_id"),mode);
                out.put("experiment_token",e.token);
            } else if(Set.of("state","release","cancel").contains(operation)) {
                fields(input,"experiment_token");
                var e=VisibilityState.get(text(input,"experiment_token"));
                if(!e.realm.equals(session.getContext().getRealm().getId())) return reply(400,null);
                if(!operation.equals("state")) VisibilityState.release(e,operation.equals("cancel"));
                out.put("state",e.state);
                out.put("hook_boundary",e.boundary.name());
                if(e.tuple==null) out.putNull("tuple");
                else {
                    var t=e.tuple; ObjectNode tuple=out.putObject("tuple");
                    tuple.put("realm_id",t.realmId()); tuple.put("subject",t.subject());
                    tuple.put("user_session_id",t.sessionId()); tuple.put("client_id",t.clientId()); tuple.put("client_uuid",t.clientUuid());
                    tuple.put("expires_at",e.expires); tuple.put("nonce",e.nonce);
                    tuple.put("observation_token",VisibilityState.seal(t,e.expires,e.nonce));
                }
            } else if(operation.equals("observe")) {
                fields(input,"realm_id","subject","user_session_id","client_id","client_uuid","expires_at","nonce","observation_token");
                if(!input.path("expires_at").isIntegralNumber() || !input.path("expires_at").canConvertToLong()) return reply(400,null);
                long expires=input.path("expires_at").longValue(),now=Instant.now().getEpochSecond();
                var t=new VisibilityState.Tuple(text(input,"realm_id"),text(input,"subject"),text(input,"user_session_id"),text(input,"client_id"),text(input,"client_uuid"));
                var realm=session.getContext().getRealm();
                if(expires<=now || expires>now+30 || !realm.getId().equals(t.realmId())
                        || !VisibilityState.equal(text(input,"observation_token"),VisibilityState.seal(t,expires,text(input,"nonce")))) return reply(400,null);
                var user=session.users().getUserById(realm,t.subject());
                var client=realm.getClientById(t.clientUuid());
                if(user==null || client==null || !client.getClientId().equals(t.clientId())) return reply(400,null);
                var us=session.sessions().getUserSession(realm,t.sessionId());
                boolean visible=us!=null && us.getUser()!=null && us.getUser().getId().equals(t.subject()) && AuthenticationManager.isSessionValid(realm,us);
                var cs=visible?us.getAuthenticatedClientSessionByClient(t.clientUuid()):null;
                boolean attached=cs!=null && cs.getClient().getId().equals(t.clientUuid()) && cs.getUserSession().getId().equals(t.sessionId());
                out.put("outcome",visible&&attached?"VISIBLE":"NOT_VISIBLE");
                out.put("user_session",visible); out.put("client_session",attached);
            } else return reply(404,null);
            return reply(200,out);
        } catch (IllegalArgumentException invalid) { return reply(400,null); }
        catch (Exception failure) {
            ObjectNode error=JsonSerialization.mapper.createObjectNode(); error.put("outcome","ERROR");
            error.put("user_session",false); error.put("client_session",false); return reply(503,error);
        }
    }
    private static String text(JsonNode node,String name) {
        JsonNode value=node.get(name);
        if(value==null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length()>256) throw new IllegalArgumentException();
        return value.textValue();
    }
    private static void fields(JsonNode node,String... expected) {
        Set<String> actual=new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(Set.of(expected))) throw new IllegalArgumentException();
    }
    private static Response reply(int status,Object entity) {
        return Response.status(status).type("application/json").header("Cache-Control","no-store").entity(entity).build();
    }
}
