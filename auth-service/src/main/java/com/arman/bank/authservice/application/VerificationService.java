package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.Verification.*;
import com.arman.bank.authservice.domain.Verification.Error;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Disabled-by-integration core: no routes, sessions, identity allocation or external sends. */
public final class VerificationService {
    private final VerificationStore store;
    private final Set<String> issuers;
    public VerificationService(VerificationStore store, Set<String> issuers) {
        this.store = Objects.requireNonNull(store);
        this.issuers = Set.copyOf(issuers);
        if (this.issuers.isEmpty() || this.issuers.size() > 16) throw new IllegalArgumentException("Invalid issuer configuration");
        for (String issuer : this.issuers) {
            var uri = URI.create(issuer);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || issuer.length() > 512)
                throw new IllegalArgumentException("Invalid issuer configuration");
        }
    }
    public Result enroll(EnrollmentCommand command) {
        if (command == null || command.commandId() == null) bad();
        binding(command.binding());
        if (!canonicalPhone(command.phone())) bad();
        return accepted(store.enroll(new EnrollmentCommand(command.commandId(), command.binding(), command.phone(), normalizeEmail(command.email()))));
    }
    public Result issue(ChallengeCommand command) {
        if (command == null) bad();
        validate(command.commandId(), command.enrollmentId(), command.binding(), command.purpose(), command.contactVersion());
        return accepted(store.issue(command));
    }
    public Result consume(ConsumeCommand command) {
        if (command == null || command.challengeId() == null || command.code() == null || !command.code().matches("[0-9]{6}")) bad();
        validate(command.commandId(), command.enrollmentId(), command.binding(), command.purpose(), command.contactVersion());
        return accepted(store.consume(command));
    }
    public Result status(UUID enrollmentId, Binding binding) {
        if (enrollmentId == null) bad();
        binding(binding);
        return accepted(store.status(enrollmentId, binding));
    }
    private void validate(UUID command, UUID enrollment, Binding binding, Purpose purpose, int version) {
        if (command == null || enrollment == null || purpose == null || version < 1) bad();
        binding(binding);
    }
    private void binding(Binding binding) {
        if (binding == null || !issuers.contains(binding.issuer())) throw new Failure(Error.BINDING);
        if (!bounded(binding.subject()) || !bounded(binding.session())) bad();
    }
    private static boolean bounded(String value) {
        return value != null && !value.isBlank() && value.length() <= 255 && value.chars().noneMatch(c -> c < 33 || c == 127);
    }
    public static boolean canonicalPhone(String value) { return value != null && value.matches("\\+9715[024568][0-9]{7}"); }
    public static String normalizeEmail(String value) {
        if (value == null) { bad(); }
        String email = value.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !email.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")) bad();
        return email;
    }
    private static Result accepted(Result result) {
        if (result == null) throw new Failure(Error.UNAVAILABLE);
        if (result.error() != null) throw new Failure(result.error());
        return result;
    }
    private static void bad() { throw new Failure(Error.BAD_INPUT); }
}
