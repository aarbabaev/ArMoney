package com.arman.bank.authservice.application;

public interface PasswordHasher {
    String hash(String password);
    boolean verify(String password, String encoded);
}
