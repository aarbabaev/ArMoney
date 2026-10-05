package com.arman.bank.authservice.application;

public final class AuthFailure extends RuntimeException {
    public enum Kind { BAD_INPUT, UNAUTHORIZED, CONFLICT, RATE_LIMITED, UNAVAILABLE }
    private final Kind kind;
    public AuthFailure(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
