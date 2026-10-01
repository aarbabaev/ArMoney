package com.arman.bank.paymentservice.application;

public final class PaymentFailure extends RuntimeException {
    private final int status;
    private final String code;
    public PaymentFailure(int status, String code) { super(code); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
}
