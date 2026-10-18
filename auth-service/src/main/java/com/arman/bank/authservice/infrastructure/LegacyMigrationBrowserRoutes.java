package com.arman.bank.authservice.infrastructure;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import com.arman.bank.authservice.application.LegacyMigrationBrowserService;
import com.arman.bank.authservice.application.AuthService;
import com.arman.bank.authservice.application.AuthFailure;
import com.arman.bank.authservice.application.MigrationManagedProofPort;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

/** Default-off browser boundary. The injected genuine creator owns all account,
 * CSRF, attempt, state/cookie matching, one-shot claim and provider exchange.
 * No Main/gateway registration or bearer credential is introduced here. */
public final class LegacyMigrationBrowserRoutes {
    public static final String ATTEMPT_COOKIE = "__Secure-ArMoneyMigrationAttempt";
    public static final String FORM_COOKIE = "__Secure-ArMoneyMigrationForm";
    public static final String ACCOUNT_COOKIE = "__Secure-ArMoneyMigrationAccount";
    private static final Set<String> COOKIE_NAMES = Set.of(ATTEMPT_COOKIE, FORM_COOKIE, ACCOUNT_COOKIE);
    private static final String COOKIE_PATH = "/migration/";
    private static final String VERIFY_STATUS = "/migration/legacy/verify/status";
    private static final Map<String, String> CONTINUATION_POST = Map.of(
        "/migration/legacy/verify/stage", "STAGE", "/migration/legacy/otp/issue", "ISSUE",
        "/migration/legacy/otp/consume", "CONSUME", "/migration/legacy/verify/decision", "DECISION",
        "/migration/legacy/verify/publication", "PUBLICATION", "/migration/legacy/verify/final-command", "FINAL_PREPARE",
        "/migration/legacy/verify/final", "FINAL");
    private final LegacyMigrationBrowserConfiguration configuration;
    private final LegacyMigrationBrowserIngressConfiguration ingress;
    private final LegacyMigrationBrowserService service;
    private final Clock clock;
    private final BodyReader bodyReader;
    private final Semaphore requests;
    @FunctionalInterface interface BodyReader { byte[] read(Context context) throws Exception; }

    public LegacyMigrationBrowserRoutes(LegacyMigrationBrowserConfiguration configuration,
                                       LegacyMigrationBrowserIngressConfiguration ingress, LegacyMigrationBrowserService service) {
        this(configuration, ingress, service, Clock.systemUTC(), LegacyMigrationBrowserRoutes::readBody);
    }
    /** Package-private parser/time seam for isolated boundary tests, never configuration. */
    LegacyMigrationBrowserRoutes(LegacyMigrationBrowserConfiguration configuration, LegacyMigrationBrowserIngressConfiguration ingress,
                                LegacyMigrationBrowserService service, Clock clock, BodyReader bodyReader) {
        this.configuration = Objects.requireNonNull(configuration);
        this.ingress = Objects.requireNonNull(ingress);
        require(configuration.enabled() == ingress.enabled());
        if (configuration.enabled()) require(configuration.origin().equals(ingress.publicOrigin()));
        this.requests = configuration.enabled() ? new Semaphore(16) : null;
        this.service = configuration.enabled() ? Objects.requireNonNull(service) : service;
        this.clock = Objects.requireNonNull(clock); this.bodyReader = Objects.requireNonNull(bodyReader);
    }
    public void configure(JavalinConfig config) {
        if (!configuration.enabled()) return;
        Objects.requireNonNull(config);
        for (String path : LegacyMigrationBrowserConfiguration.GET_PATHS) config.routes.get(path, this::handle);
        for (String path : LegacyMigrationBrowserConfiguration.POST_PATHS) config.routes.post(path, this::handle);
    }
    private static byte[] readBody(Context context) throws Exception {
        try (var deadline = LoginProofBodyDeadline.begin(context.req())) {
            return deadline.read(context.req().getInputStream(), 8193);
        }
    }
    /** No body/query/exception text is ever copied to a response. */
    void handle(Context context) {
        responseHeaders().forEach(context::header);
        boolean acquired = false;
        boolean continuation = false;
        try {
            var request = context.req();
            ingress.requireGateway(request);
            String path = request.getRequestURI(), method = request.getMethod();
            var origins = Collections.list(request.getHeaders("Origin"));
            require(origins.size() <= 1);
            configuration.requireBrowserRequest(method, path, origins.isEmpty() ? null : origins.getFirst());
            continuation = CONTINUATION_POST.containsKey(path) || VERIFY_STATUS.equals(path)
                || LegacyMigrationBrowserConfiguration.MANAGED_CALLBACK.equals(path);
            var cookies = cookieValues(Collections.list(request.getHeaders("Cookie")));
            String existingAttempt = optionalCookie(cookies, ATTEMPT_COOKIE);
            acquired = requests.tryAcquire();
            if (!acquired) { html(context, 429, "Migration request unavailable."); return; }
            if ("GET".equals(method) && path.equals(LegacyMigrationBrowserConfiguration.FORM)) {
                require(request.getQueryString() == null);
                var form = service.form(existingAttempt);
                require(form != null && token(form.csrf()) && validCommand(form.commandId()));
                String header = cookieHeader(FORM_COOKIE, form.formCookie(), seconds(form.expiresAt()));
                String body = initialForm(form.csrf(), form.commandId());
                context.res().addHeader("Set-Cookie", header);
                html(context, 200, body);
            } else if ("GET".equals(method) && VERIFY_STATUS.equals(path)) {
                require(request.getQueryString() == null);
                verification(context, service.status(requiredCookie(cookies, ACCOUNT_COOKIE)));
            } else if ("GET".equals(method)) {
                Callback parsed = callback(request.getQueryString());
                String originalCookie = requiredCookie(cookies, ATTEMPT_COOKIE);
                var callback = new MigrationManagedProofPort.Callback(parsed.code(), parsed.state(), originalCookie);
                // The producer resolves original purpose/state/cookie BEFORE claim/exchange.
                if (path.equals(LegacyMigrationBrowserConfiguration.LEGACY_CALLBACK)) {
                    account(context, service.accountOidcCallback(callback), true);
                } else {
                    String originalAccount = requiredCookie(cookies, ACCOUNT_COOKIE);
                    var page = service.managedVerification(callback, originalAccount);
                    verification(context, page);
                }
            } else {
                require(request.getQueryString() == null);
                var types = Collections.list(request.getHeaders("Content-Type"));
                require(types.size() == 1 && types.getFirst().matches("(?i)application/x-www-form-urlencoded(?:\\s*;\\s*charset=utf-8)?"));
                String binder = requiredCookie(cookies, (path.equals(LegacyMigrationBrowserConfiguration.MANAGED_START)
                    || path.equals(LegacyMigrationBrowserConfiguration.EMAIL_CANDIDATE) || CONTINUATION_POST.containsKey(path)) ? ACCOUNT_COOKIE : FORM_COOKIE);
                Set<String> expected = path.equals(LegacyMigrationBrowserConfiguration.PASSWORD)
                    ? Set.of("csrf", "command_id", "legacy_login", "password")
                    : path.equals(LegacyMigrationBrowserConfiguration.EMAIL_CANDIDATE)
                        ? Set.of("csrf", "command_id", "email")
                        : path.equals("/migration/legacy/otp/consume") ? Set.of("csrf", "command_id", "code") : Set.of("csrf", "command_id");
                byte[] raw = bodyReader.read(context);
                Map<String,String> fields;
                try { fields = form(raw, expected).values(); }
                finally { if (raw != null) Arrays.fill(raw, (byte)0); }
                require(token(fields.get("csrf")));
                UUID command = UUID.fromString(fields.get("command_id"));
                require(validCommand(command) && command.toString().equals(fields.get("command_id")));
                if (CONTINUATION_POST.containsKey(path)) {
                    String csrf = fields.get("csrf");
                    var page = switch (CONTINUATION_POST.get(path)) {
                        case "STAGE" -> service.stage(binder, csrf, command);
                        case "ISSUE" -> service.issue(binder, csrf, command);
                        case "CONSUME" -> {
                            String code = fields.get("code");
                            require(code != null && code.matches("[0-9]{6}"));
                            yield service.consume(binder, csrf, command, code);
                        }
                        case "DECISION" -> service.authorizePublication(binder, csrf, command);
                        case "PUBLICATION" -> service.publish(binder, csrf, command);
                        case "FINAL_PREPARE" -> service.prepareFinal(binder, csrf, command);
                        case "FINAL" -> service.finish(binder, csrf, command);
                        default -> throw rejected();
                    };
                    verification(context, page);
                } else if (path.equals(LegacyMigrationBrowserConfiguration.PASSWORD)) {
                    // Historical email is authenticated by Auth; no caller owner/target IDs.
                    account(context, service.password(binder, fields.get("csrf"), fields.get("legacy_login"), fields.get("password"), command), false);
                } else if (path.equals(LegacyMigrationBrowserConfiguration.EMAIL_CANDIDATE)) {
                    String email = fields.get("email");
                    try { require(email != null && email.equals(AuthService.normalizeEmail(email))); }
                    catch (AuthFailure invalid) { throw rejected(); }
                    account(context, service.emailCandidate(binder, fields.get("csrf"), command, email), false);
                } else if (path.equals(LegacyMigrationBrowserConfiguration.LEGACY_START)) {
                    redirect(context, service.accountOidcStart(binder, fields.get("csrf"), command, existingAttempt), false);
                } else {
                    redirect(context, service.managedStart(binder, fields.get("csrf"), command, existingAttempt), true);
                }
            }
        } catch (LegacyMigrationBrowserService.Failure denied) {
            if (!continuation && denied.kind() != null && denied.clearCookies())
                for (String name : COOKIE_NAMES) context.res().addHeader("Set-Cookie", clearCookie(name));
            int status = denied.kind() == null ? 503 : switch (denied.kind()) {
                case BAD_INPUT -> 400; case UNAUTHORIZED -> 401; case CONFLICT -> 409;
                case RATE_LIMITED -> 429; case UNAVAILABLE -> 503;
            };
            html(context, status, "Migration request unavailable.");
        } catch (IllegalArgumentException denied) {
            html(context, 400, "Migration request rejected.");
        } catch (Exception unavailable) {
            html(context, 503, "Migration request unavailable.");
        } finally { if (acquired) requests.release(); }
    }
    private void redirect(Context context, LegacyMigrationBrowserService.Redirect redirect, boolean managed) {
        require(redirect != null);
        configuration.requireAuthorization(redirect.location(), managed);
        String header = attemptCookie(redirect.attemptCookie(), seconds(redirect.expiresAt()));
        context.res().addHeader("Set-Cookie", header);
        if (!managed) context.res().addHeader("Set-Cookie", clearCookie(FORM_COOKIE));
        context.header("Location", redirect.location().toASCIIString()).status(303).result("");
    }
    private void account(Context context, LegacyMigrationBrowserService.Account account, boolean callback) {
        require(account != null && token(account.csrf()) && validCommand(account.commandId()));
        String header = cookieHeader(ACCOUNT_COOKIE, account.accountCookie(), seconds(account.expiresAt()));
        String body = account.emailCandidateRequired()
            ? "<form method=\"post\" action=\"" + LegacyMigrationBrowserConfiguration.EMAIL_CANDIDATE + "\">"
                + hidden(account.csrf(), account.commandId())
                + "<label>Email for verification<input type=\"email\" name=\"email\" maxlength=\"254\" autocomplete=\"email\" required></label>"
                + "<button type=\"submit\">Continue email verification</button></form>"
            : "<form method=\"post\" action=\"" + LegacyMigrationBrowserConfiguration.MANAGED_START + "\">"
                + hidden(account.csrf(), account.commandId()) + "<button type=\"submit\">Authenticate managed account</button></form>";
        context.res().addHeader("Set-Cookie", header);
        context.res().addHeader("Set-Cookie", clearCookie(FORM_COOKIE));
        if (callback) context.res().addHeader("Set-Cookie", clearAttemptCookie());
        html(context, 200, body);
    }
    private void verification(Context context, LegacyMigrationBrowserService.VerificationPage page) {
        require(page != null);
        if (page instanceof LegacyMigrationBrowserService.ReadOnlyStatus status) {
            require(status.status() != null && status.originalExpiresAt() != null);
            // Retained readback may outlive proof; it never issues cookies or commands.
            String body = switch (status.status()) {
                case COMPLETED -> "Migration completed. Sign in again using the Android application.";
                case PENDING_RESULT -> "Migration result pending. No further action is currently available.";
                case RECOVERY_REQUIRED -> "Migration verification unavailable. Recovery is required.";
            };
            if (status.status() == LegacyMigrationBrowserService.Status.COMPLETED)
                for (String name : COOKIE_NAMES) context.res().addHeader("Set-Cookie", clearCookie(name));
            html(context, status.status() == LegacyMigrationBrowserService.Status.PENDING_RESULT ? 503 : 200, body);
            return;
        }
        require(page instanceof LegacyMigrationBrowserService.ActionBundle);
        var bundle = (LegacyMigrationBrowserService.ActionBundle)page;
        require(validCommand(bundle.bundleId()));
        seconds(bundle.originalExpiresAt());
        var forms = bundle.forms();
        require(forms != null && forms.size() >= 1 && forms.size() <= 2);
        if (forms.size() == 2) {
            var first = forms.getFirst().action(); var second = forms.get(1).action();
            require(first == LegacyMigrationBrowserService.Action.EMAIL_CONSUME && second == LegacyMigrationBrowserService.Action.EMAIL_ISSUE
                || first == LegacyMigrationBrowserService.Action.PHONE_CONSUME && second == LegacyMigrationBrowserService.Action.PHONE_ISSUE);
            require(!forms.getFirst().commandId().equals(forms.get(1).commandId()));
        }
        StringBuilder body = new StringBuilder();
        for (var form : forms) {
            require(form != null && form.action() != null && token(form.csrf()) && validCommand(form.commandId())
                && form.attemptsRemaining() >= 0 && form.attemptsRemaining() <= 5);
            boolean consume = form.action() == LegacyMigrationBrowserService.Action.EMAIL_CONSUME
                || form.action() == LegacyMigrationBrowserService.Action.PHONE_CONSUME;
            require(!consume || form.attemptsRemaining() > 0);
            require(form.retryAt() == null || form.retryAt().isBefore(bundle.originalExpiresAt()));
            String path = switch (form.action()) {
                case STAGE -> "/migration/legacy/verify/stage";
                case EMAIL_ISSUE, PHONE_ISSUE -> "/migration/legacy/otp/issue";
                case EMAIL_CONSUME, PHONE_CONSUME -> "/migration/legacy/otp/consume";
                case DECISION -> "/migration/legacy/verify/decision";
                case PUBLICATION -> "/migration/legacy/verify/publication";
                case FINAL_PREPARE -> "/migration/legacy/verify/final-command";
                case FINAL -> "/migration/legacy/verify/final";
            };
            String label = switch (form.action()) {
                case EMAIL_ISSUE -> "Send email code"; case PHONE_ISSUE -> "Send phone code";
                case EMAIL_CONSUME -> "Verify email code"; case PHONE_CONSUME -> "Verify phone code";
                case STAGE -> "Prepare verification"; case DECISION -> "Authorize publication";
                case PUBLICATION -> "Publish verification"; case FINAL_PREPARE -> "Prepare completion";
                case FINAL -> "Complete migration";
            };
            body.append("<form method=\"post\" action=\"").append(path).append("\">")
                .append(hidden(form.csrf(), form.commandId()));
            if (consume) body.append("<label>Verification code<input name=\"code\" inputmode=\"numeric\" pattern=\"[0-9]{6}\" minlength=\"6\" maxlength=\"6\" autocomplete=\"one-time-code\" required></label>");
            body.append("<button type=\"submit\"");
            if (form.retryAt() != null && clock.instant().isBefore(form.retryAt())) body.append(" disabled");
            body.append(">").append(label).append("</button></form>");
        }
        html(context, 200, body.toString());
    }
    private int seconds(Instant expiry) {
        require(expiry != null);
        long remaining = Duration.between(clock.instant(), expiry).getSeconds();
        require(remaining >= 1 && remaining <= 120);
        return Math.toIntExact(remaining);
    }
    private static boolean validCommand(UUID command) { return command != null && !command.equals(new UUID(0, 0)); }
    private static String initialForm(String csrf, UUID command) {
        return "<form method=\"post\" action=\"" + LegacyMigrationBrowserConfiguration.PASSWORD + "\">" + hidden(csrf, command)
            + "<label>Existing email<input type=\"email\" name=\"legacy_login\" autocomplete=\"username\" required></label>"
            + "<label>Password<input type=\"password\" name=\"password\" autocomplete=\"current-password\" required></label>"
            + "<button type=\"submit\">Verify existing account</button></form>"
            + "<form method=\"post\" action=\"" + LegacyMigrationBrowserConfiguration.LEGACY_START + "\">" + hidden(csrf, command)
            + "<button type=\"submit\">Authenticate existing SSO account</button></form>";
    }
    private static String hidden(String csrf, UUID command) {
        // Only canonical server-issued tokens/UUIDs qualify; no caller text rendered.
        require(token(csrf) && validCommand(command));
        return "<input type=\"hidden\" name=\"csrf\" value=\"" + csrf + "\">"
            + "<input type=\"hidden\" name=\"command_id\" value=\"" + command + "\">";
    }
    private static void html(Context context, int status, String body) {
        context.status(status).contentType("text/html; charset=utf-8")
            .result("<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>ArMoney migration</title></head><body>" + body + "</body></html>");
    }

    record Fields(Map<String,String> values) {
        Fields { values = Map.copyOf(values); }
        @Override public String toString() { return "MigrationBrowserFields[redacted]"; }
    }
    record Callback(String state, String code) {
        @Override public String toString() { return "MigrationBrowserCallback[redacted]"; }
    }
    record CookieValue(String value) {
        @Override public String toString() { return "MigrationBrowserCookie[redacted]"; }
    }
    static IllegalArgumentException rejected() { return new IllegalArgumentException("Migration browser input rejected"); }
    private static void require(boolean ok) { if (!ok) throw rejected(); }

    static Fields form(byte[] bytes, Set<String> expected) {
        require(bytes != null && bytes.length <= 8192 && expected != null && !expected.isEmpty());
        return new Fields(fields(bytes, expected));
    }
    static Fields authorizationQuery(String query) {
        require(query != null && query.length() <= 4096 && query.chars().allMatch(c -> c >= 33 && c <= 126));
        return new Fields(fields(query.getBytes(StandardCharsets.US_ASCII), Set.of("client_id", "redirect_uri",
            "response_type", "scope", "state", "nonce", "code_challenge", "code_challenge_method", "prompt", "max_age")));
    }
    static Callback callback(String query) {
        require(query != null && query.length() <= 4096 && query.chars().allMatch(c -> c >= 33 && c <= 126));
        Map<String,String> value = fields(query.getBytes(StandardCharsets.US_ASCII), Set.of("state", "code"));
        require(token(value.get("state")));
        String code = value.get("code");
        require(!code.isEmpty() && code.length() <= 2048 && code.chars().allMatch(c -> c >= 33 && c <= 126));
        return new Callback(value.get("state"), code);
    }
    private static Map<String,String> fields(byte[] raw, Set<String> expected) {
        require(raw.length > 0 && raw.length <= 8192);
        var result = new LinkedHashMap<String,String>();
        String encoded = ascii(raw);
        for (String field : encoded.split("&", -1)) {
            int separator = field.indexOf('='); require(separator > 0);
            String key = decode(field.substring(0, separator)), value = decode(field.substring(separator + 1));
            require(expected.contains(key) && !result.containsKey(key) && !value.isEmpty());
            result.put(key, value);
        }
        require(result.keySet().equals(expected));
        return result;
    }
    private static String ascii(byte[] raw) {
        for (byte b : raw) require(b >= 32 && b <= 126);
        return new String(raw, StandardCharsets.US_ASCII);
    }
    private static String decode(String encoded) {
        var bytes = new ByteArrayOutputStream();
        for (int index = 0; index < encoded.length(); index++) {
            char ch = encoded.charAt(index);
            if (ch == '%') {
                require(index + 2 < encoded.length());
                int high = Character.digit(encoded.charAt(++index), 16), low = Character.digit(encoded.charAt(++index), 16);
                require(high >= 0 && low >= 0); bytes.write(high * 16 + low);
            } else bytes.write(ch == '+' ? ' ' : ch);
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            require(value.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0x7f));
            return value;
        } catch (java.nio.charset.CharacterCodingException bad) { throw rejected(); }
    }
    /** Exact duplicate cookie names deny, including ambiguous repeated headers. */
    static CookieValue cookie(List<String> headers, String expectedName) {
        require(expectedName != null && COOKIE_NAMES.contains(expectedName));
        return new CookieValue(requiredCookie(cookieValues(headers), expectedName));
    }
    private static Map<String,String> cookieValues(List<String> headers) {
        require(headers != null && headers.size() <= 1);
        if (headers.isEmpty()) return Map.of();
        require(headers.getFirst() != null);
        String header = headers.getFirst(); require(header.length() <= 4096);
        var values = new HashMap<String,String>();
        for (String field : header.split(";", -1)) {
            String trimmed = field.strip(); int separator = trimmed.indexOf('='); require(separator > 0);
            String name = trimmed.substring(0, separator), value = trimmed.substring(separator + 1);
            require(name.matches("[A-Za-z0-9_-]{1,128}") && !values.containsKey(name));
            require(value.chars().allMatch(c -> c >= 33 && c <= 126 && c != ';' && c != ',' && c != '"'));
            values.put(name, value);
        }
        return values;
    }
    private static String requiredCookie(Map<String,String> values, String name) {
        String value = optionalCookie(values, name); require(value != null); return value;
    }
    private static String optionalCookie(Map<String,String> values, String name) {
        String value = values.get(name); require(value == null || token(value)); return value;
    }
    static boolean token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return false;
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)).equals(value); }
        catch (IllegalArgumentException bad) { return false; }
    }
    static String attemptCookie(String value, int remainingSeconds) {
        return cookieHeader(ATTEMPT_COOKIE, value, remainingSeconds);
    }
    static String clearAttemptCookie() {
        return clearCookie(ATTEMPT_COOKIE);
    }
    static String cookieHeader(String name, String value, int remainingSeconds) {
        require(name != null && COOKIE_NAMES.contains(name) && token(value) && remainingSeconds >= 1 && remainingSeconds <= 120);
        return name + "=" + value + attributes() + "; Max-Age=" + remainingSeconds;
    }
    static String clearCookie(String name) {
        require(name != null && COOKIE_NAMES.contains(name));
        return name + "=" + attributes() + "; Max-Age=0";
    }
    private static String attributes() { return "; Path=" + COOKIE_PATH + "; Secure; HttpOnly; SameSite=Lax"; }
    static Map<String,String> responseHeaders() {
        return Map.of("Cache-Control", "no-store", "Referrer-Policy", "no-referrer", "X-Content-Type-Options", "nosniff",
            "Content-Security-Policy", "default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
    }
}
