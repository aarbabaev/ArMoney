package com.arman.bank.ledgerservice;

import com.arman.bank.runtime.ServiceRuntime;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        ServiceRuntime.launch("ledger-service", true);
    }
}
