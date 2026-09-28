package com.arman.bank.userservice;

import com.arman.bank.runtime.ServiceRuntime;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        ServiceRuntime.launch("user-service", true);
    }
}
