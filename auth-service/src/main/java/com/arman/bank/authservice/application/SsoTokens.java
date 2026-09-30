package com.arman.bank.authservice.application;

/** Only an authenticated provider response can establish this principal. */
public interface SsoTokens {
    record Principal(String issuer, String subject) {}
    Principal verify(String accessToken);
}
