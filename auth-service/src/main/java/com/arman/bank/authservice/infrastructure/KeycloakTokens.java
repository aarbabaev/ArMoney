package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.AuthFailure;
import com.arman.bank.authservice.application.SsoTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static com.arman.bank.authservice.application.AuthFailure.Kind.*;

/** Fixed, administrator-configured introspection destination. Never decodes untrusted JWTs. */
public final class KeycloakTokens implements SsoTokens, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(8);
    private final URI endpoint;
    private final String issuer, credentials;
    private final Clock clock;

    public KeycloakTokens(URI endpoint, String issuer, String clientId, String secret, Clock clock) {
        if (endpoint == null || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) ||
            endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null ||
            !endpoint.getPath().endsWith("/protocol/openid-connect/token/introspect"))
            throw new IllegalArgumentException("SSO_INTROSPECTION_URL must be a fixed provider introspection endpoint");
        URI external = URI.create(issuer);
        if (!"https".equals(external.getScheme()) || external.getHost() == null || external.getUserInfo() != null ||
            external.getQuery() != null || external.getFragment() != null || issuer.length() > 2048 || issuer.endsWith("/"))
            throw new IllegalArgumentException("SSO_ISSUER must be an HTTPS realm URL");
        if (clientId == null || !clientId.matches("[A-Za-z0-9_-]{1,128}") || secret == null || secret.length() < 32 || secret.length() > 1024)
            throw new IllegalArgumentException("SSO confidential client credentials are required");
        this.endpoint = endpoint; this.issuer = issuer; this.clock = clock;
        this.credentials = "client_id=" + encode(clientId) + "&client_secret=" + encode(secret);
    }

    @Override public Principal verify(String token) {
        if (!slots.tryAcquire()) throw new AuthFailure(UNAVAILABLE);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(credentials + "&token_type_hint=access_token&token=" + encode(token))).build();
            pending = client.sendAsync(request, info -> new LimitedBody());
            var response = pending.get(3, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new AuthFailure(UNAVAILABLE);
            JsonNode body = JSON.readTree(response.body());
            if (body == null || !body.isObject()) throw new AuthFailure(UNAVAILABLE);
            if (!body.path("active").isBoolean() || !body.path("active").booleanValue()) throw new AuthFailure(UNAUTHORIZED);
            var subject = body.path("sub");
            var expiry = body.path("exp");
            var audience = body.path("aud");
            String issuingClient = body.path("client_id").textValue();
            boolean acceptedClient = "armoney-ios".equals(issuingClient) || "armoney-android".equals(issuingClient) ||
                "armoney-web".equals(issuingClient);
            boolean acceptedAudience = audience.isTextual() && audience.textValue().equals("armoney-api");
            if (audience.isArray()) for (var item : audience) acceptedAudience |= item.isTextual() && item.textValue().equals("armoney-api");
            if (!issuer.equals(body.path("iss").textValue()) || !"Bearer".equalsIgnoreCase(body.path("token_type").textValue()) ||
                !acceptedAudience || !acceptedClient ||
                ((body.has("azp") || "armoney-web".equals(issuingClient)) &&
                    !issuingClient.equals(body.path("azp").textValue())) ||
                !subject.isTextual() || subject.textValue().isBlank() || subject.textValue().length() > 255 ||
                subject.textValue().chars().anyMatch(Character::isISOControl) ||
                !expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.longValue() <= clock.instant().getEpochSecond() ||
                (body.has("nbf") && (!body.path("nbf").isIntegralNumber() || !body.path("nbf").canConvertToLong() || body.path("nbf").longValue() > clock.instant().getEpochSecond())))
                throw new AuthFailure(UNAUTHORIZED);
            Instant capturedNow=clock.instant();
            var context=new TokenContext(Metadata.present(issuingClient),sessionId(body),issuedAt(body,expiry.longValue(),capturedNow),expiryView(expiry),proofMetadata(body));
            var phone = body.path("phone_number");
            if (!phone.isMissingNode() && !phone.isNull() && !phone.isTextual()) throw new AuthFailure(UNAUTHORIZED);
            // Auth persistence applies required/immutable phone rules using the existing mapping.
            var email = body.path("email");
            var verified = body.path("email_verified");
            return new Principal(issuer, subject.textValue(), phone.textValue(), email.textValue(),
                verified.isBoolean() && verified.booleanValue(),context);
        } catch (AuthFailure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AuthFailure(UNAVAILABLE); }
        catch (Exception e) { throw new AuthFailure(UNAVAILABLE); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); slots.release(); }
    }
    private static Metadata<String> sessionId(JsonNode body){
        var value=body.get("sid");
        if(value==null)return Metadata.absent();
        if(value.isNull())return Metadata.malformed(Malformation.NULL_VALUE);
        if(!value.isTextual())return Metadata.malformed(Malformation.WRONG_TYPE);
        String id=value.textValue();
        if(id.isEmpty()||id.length()>255||id.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||(c>=0xD800&&c<=0xDFFF)))
            return Metadata.malformed(Malformation.INVALID_VALUE);
        return Metadata.present(id);
    }
    private static Metadata<Instant> issuedAt(JsonNode body,long expiry,Instant capturedNow){
        var value=body.get("iat");
        if(value==null)return Metadata.absent();
        if(value.isNull())return Metadata.malformed(Malformation.NULL_VALUE);
        if(!value.isIntegralNumber())return Metadata.malformed(Malformation.WRONG_TYPE);
        if(!value.canConvertToLong())return Metadata.malformed(Malformation.OUT_OF_RANGE);
        long seconds=value.longValue();
        if(seconds<0||seconds>expiry||seconds>capturedNow.getEpochSecond())return Metadata.malformed(Malformation.OUT_OF_RANGE);
        try{return Metadata.present(Instant.ofEpochSecond(seconds));}
        catch(DateTimeException invalid){return Metadata.malformed(Malformation.OUT_OF_RANGE);}
    }
    private static Metadata<Instant> expiryView(JsonNode value){
        try{return Metadata.present(Instant.ofEpochSecond(value.longValue()));}
        catch(DateTimeException invalid){return Metadata.malformed(Malformation.OUT_OF_RANGE);}
    }
    private static Metadata<ProofClaims> proofMetadata(JsonNode body){
        var kind=body.get("armoney_login_kind");var notice=body.get("armoney_login_notice_eligible");var event=body.get("armoney_login_event_id");
        if(kind==null&&notice==null&&event==null)return Metadata.absent();
        if((kind!=null&&kind.isNull())||(notice!=null&&notice.isNull())||(event!=null&&event.isNull()))return Metadata.malformed(Malformation.NULL_VALUE);
        if(kind==null||notice==null)return Metadata.malformed(Malformation.INVALID_VALUE);
        if(!kind.isTextual()||!notice.isBoolean())return Metadata.malformed(Malformation.WRONG_TYPE);
        if("reuse".equals(kind.textValue())){
            // Signed JWT claims omit the event entirely for reuse; bridge Write's explicit null is a different DTO.
            if(event!=null||notice.booleanValue())return Metadata.malformed(Malformation.INVALID_VALUE);
            return Metadata.present(new ProofClaims("reuse",null,false));
        }
        if(!"interactive".equals(kind.textValue())||event==null)return Metadata.malformed(Malformation.INVALID_VALUE);
        if(!event.isTextual())return Metadata.malformed(Malformation.WRONG_TYPE);
        try{
            var id=java.util.UUID.fromString(event.textValue());
            if(!id.toString().equals(event.textValue()))return Metadata.malformed(Malformation.INVALID_VALUE);
            // Either literal boolean is valid format. Notice metadata never authorizes banking access.
            return Metadata.present(new ProofClaims("interactive",id,notice.booleanValue()));
        }catch(IllegalArgumentException invalid){return Metadata.malformed(Malformation.INVALID_VALUE);}
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    @Override public void close() { client.close(); }

    /** Cancels the network subscription before a provider can buffer an unbounded body. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 32768 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Provider response too large")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
