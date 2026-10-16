package com.arman.bank.sso;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.*;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.utils.*;
import org.keycloak.services.Urls;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** No factory registration. Activation requires independent pinned-provider acceptance. */
final class ProofLoginProtocol extends OIDCLoginProtocol {
    private final boolean enabled;
    private final ProofClient client;
    private final CodeKeys keys;
    private final Clock clock;
    private final java.util.function.BiFunction<String,AuthenticationSessionModel,String> binding;
    ProofLoginProtocol(org.keycloak.protocol.oidc.OIDCProviderConfig providerConfig, boolean enabled, ProofClient client, CodeKeys keys, Clock clock) {
        this(providerConfig,enabled,client,keys,clock,SessionBinding::of);
    }
    ProofLoginProtocol(org.keycloak.protocol.oidc.OIDCProviderConfig providerConfig, boolean enabled, ProofClient client, CodeKeys keys, Clock clock,
                       java.util.function.BiFunction<String,AuthenticationSessionModel,String> binding) {
        super(providerConfig);
        this.enabled = enabled; this.client = client; this.keys = keys; this.clock = clock; this.binding=binding;
    }
    static void requireQueryCode(String responseType, String responseMode) {
        if (!"code".equals(responseType) || responseMode != null && !"query".equals(responseMode)) throw new IllegalArgumentException("Unsupported proof response mode");
    }
    @Override public Response authenticated(AuthenticationSessionModel auth, UserSessionModel user, ClientSessionContext context) {
        if (enabled) {
            requireQueryCode(auth.getClientNote(RESPONSE_TYPE_PARAM),auth.getClientNote(RESPONSE_MODE_PARAM));
            TokenLifetime.configured(user.getRealm(),auth.getClient());
            if (user.getRealm().getAccessCodeLifespan() < 1 || user.getRealm().getAccessCodeLifespan() > 120) throw new ProofClient.Failure();
        }
        return super.authenticated(auth,user,context);
    }
    @Override public Response buildRedirectUri(OIDCRedirectUriBuilder builder, AuthenticationSessionModel auth,
                                               UserSessionModel user, ClientSessionContext context) {
        if (!enabled) return super.buildRedirectUri(builder,auth,user,context);
        try {
            requireQueryCode(auth.getClientNote(RESPONSE_TYPE_PARAM),auth.getClientNote(RESPONSE_MODE_PARAM));
            TokenLifetime.configured(user.getRealm(),auth.getClient());
            Response response = builder.build();
            try {
                String id = codeId(response, user.getId(), context.getClientSession().getClient().getId());
                Map<String,String> stored = session.singleUseObjects().get(OAuth2CodeParser.CACHE_KEY_PREFIX + id);
                if (stored == null) throw new ProofClient.Failure();
                OAuth2Code data = OAuth2Code.deserializeCode(stored);
                if (!id.equals(data.getId()) || !user.getId().equals(data.getUserSessionId())
                        || !context.getClientSession().getClient().getId().equals(data.getClientUUID())
                        || !auth.getClient().getId().equals(data.getClientUUID())) throw new ProofClient.Failure();
                Instant now = Instant.ofEpochSecond(clock.instant().getEpochSecond());
                Instant expiry = Instant.ofEpochSecond(data.getExpiration());
                if (!now.isBefore(expiry)) throw new ProofClient.Failure();
                var binding = new LoginProof.Binding(Urls.realmIssuer(uriInfo.getBaseUri(),realm.getName()), user.getUser().getId(),
                        auth.getClient().getClientId(),user.getId());
                String note = "armoney.code-proof." + id;
                LoginProof.Write write;
                String pending = auth.getAuthNote(note);
                if (pending != null) {
                    if (pending.length()>12288) throw new ProofClient.Failure();
                    write = LoginProof.write(Bridge.strictJson(pending.getBytes(StandardCharsets.UTF_8)));
                    CodeKeys.Key selected = keys.keys().stream().filter(k -> k.id().equals(write.keyId())).findFirst().orElseThrow(ProofClient.Failure::new);
                    if (!binding.equals(write.binding()) || !write.digest().equals(keys.digest(selected,binding,id))
                            || !expiry.equals(write.expires()) || !write.authenticationSession().equals(this.binding.apply(realm.getId(),auth))) throw new ProofClient.Failure();
                } else {
                    String event = auth.getAuthNote(PasswordProofFactory.NOTE), password = auth.getAuthNote(PasswordProofFactory.TIME_NOTE);
                    if ((event == null) != (password == null)) throw new ProofClient.Failure();
                    var selected = keys.keys().getFirst();
                    write = new LoginProof.Write(UUID.randomUUID(),keys.digest(selected,binding,id),selected.id(),binding,
                            this.binding.apply(realm.getId(),auth),now,expiry,event == null ? "reuse" : "interactive",
                            event == null ? null : LoginProof.uuid(event),password == null ? null : LoginProof.time(password));
                    auth.setAuthNote(note,JsonSerialization.writeValueAsString(write.fields()));
                }
                client.write(write); // Do not release even a cookie-only code without a durable acknowledged record.
                return response;
            } catch (Exception failure) { response.close(); throw failure; }
        } catch (Exception unavailable) {
            throw new WebApplicationException(Response.status(503).type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                    .header("Cache-Control","no-store").build());
        }
    }
    static String codeId(Response response, String sessionId, String clientInternalId) {
        if (response.getStatus() != 302 || response.getLocation() == null || response.getLocation().getRawQuery() == null
                || response.getLocation().getRawFragment() != null) throw new ProofClient.Failure();
        var codes = new ArrayList<String>();
        for (String part : response.getLocation().getRawQuery().split("&")) {
            String[] pair = part.split("=",2);
            if (URLDecoder.decode(pair[0],StandardCharsets.UTF_8).equals("code")) {
                if (pair.length != 2) throw new ProofClient.Failure();
                codes.add(URLDecoder.decode(pair[1],StandardCharsets.UTF_8));
            }
        }
        if (codes.size() != 1) throw new ProofClient.Failure();
        String[] pieces = codes.getFirst().split("\\.",-1);
        if (pieces.length != 3 || !pieces[1].equals(sessionId) || !pieces[2].equals(clientInternalId)) throw new ProofClient.Failure();
        return LoginProof.uuid(pieces[0]).toString();
    }
}
