package com.arman.bank.authservice;

import com.arman.bank.runtime.ServiceRuntime;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        ServiceRuntime.launch("auth-service", true);
    }
}
