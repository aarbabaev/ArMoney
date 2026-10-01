package com.arman.bank.paymentservice.application;

import com.arman.bank.paymentservice.domain.*;
import java.util.UUID;

public interface PaymentPeers {
    record Mapping(UUID destinationWalletId, UUID debitAccountId, UUID creditAccountId) {}
    enum Outcome { POSTED, INSUFFICIENT_FUNDS, INVALID_ACCOUNT, BALANCE_LIMIT }
    Mapping resolve(UUID requester, PaymentRequest request) throws Exception;
    Outcome post(Payment payment) throws Exception;
}
