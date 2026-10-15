package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.AuthService;
import java.util.Map;
import java.util.Set;

/** Sandbox-only configuration; disabled mode does not require or contact a provider. */
public final class EmailConfiguration {
    public final boolean enabled;
    public final String smtpHost, smtpUsername, smtpPassword, fromEmail, fromName;
    public final int smtpPort;
    public EmailConfiguration(Map<String, String> env) {
        var flag = env.getOrDefault("LOGIN_NOTIFICATIONS_ENABLED", "false");
        if (!Set.of("true", "false").contains(flag)) throw new IllegalArgumentException("Invalid LOGIN_NOTIFICATIONS_ENABLED");
        enabled = flag.equals("true");
        if (!enabled) { smtpHost = smtpUsername = smtpPassword = fromEmail = fromName = null; smtpPort = 0; return; }
        smtpHost = env.getOrDefault("MAILTRAP_SMTP_HOST", "sandbox.smtp.mailtrap.io");
        if (!smtpHost.equals("sandbox.smtp.mailtrap.io") || !env.getOrDefault("MAILTRAP_MODE", "sandbox").equals("sandbox"))
            throw new IllegalArgumentException("Login notifications require Mailtrap Sandbox SMTP");
        var port = env.getOrDefault("MAILTRAP_SMTP_PORT", "2525");
        if (!Set.of("25", "587", "2525").contains(port)) throw new IllegalArgumentException("Invalid MAILTRAP_SMTP_PORT");
        smtpPort = Integer.parseInt(port);
        smtpUsername = required(env, "MAILTRAP_SMTP_USERNAME");
        smtpPassword = required(env, "MAILTRAP_SMTP_PASSWORD");
        fromEmail = required(env, "MAILTRAP_FROM_EMAIL");
        if (!validEmail(fromEmail)) throw new IllegalArgumentException("Invalid MAILTRAP_FROM_EMAIL");
        fromName = env.getOrDefault("MAILTRAP_FROM_NAME", "ArMoney");
        if (fromName.length() > 100 || fromName.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid MAILTRAP_FROM_NAME");
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank() || value.length() > 2048 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    public static boolean validEmail(String value) {
        try { return value != null && AuthService.normalizeEmail(value).equals(value); }
        catch (RuntimeException invalid) { return false; }
    }
    @Override public String toString() { return "LoginEmailConfiguration[REDACTED]"; }
}
