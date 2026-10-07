package com.arman.bank.authservice.application;

/** Only an authenticated provider response can establish this principal. */
public interface SsoTokens {
    record Principal(String issuer, String subject, String phoneNumber, String email, boolean emailVerified) {
        public Principal(String issuer, String subject, String phoneNumber) {
            this(issuer, subject, phoneNumber, null, false);
        }
        public Principal {
            try { email = AuthService.normalizeEmail(email); }
            catch (AuthFailure invalid) { email = null; }
            emailVerified = email != null && emailVerified;
        }
        @Override public String toString() { return "Principal[REDACTED]"; }
    }
    Principal verify(String accessToken);
}
