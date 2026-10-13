package com.arman.bank.paymentservice.application;

import com.arman.bank.paymentservice.domain.*;
import java.util.*;

public interface PaymentStore {
    record Claim(Payment payment, UUID token, int attempts) {}
    Optional<Payment> findKey(UUID requester, String key, PaymentRequest request);
    Payment create(UUID requester, String key, PaymentRequest request, PaymentPeers.Mapping mapping);
    Optional<Payment> visible(UUID owner, UUID payment);
    List<Payment> history(UUID owner);
    List<Notification> notifications(UUID owner);
    List<Payment> historyPage(UUID owner, PagePosition before, int limit);
    List<Notification> notificationsPage(UUID owner, PagePosition before, int limit);
    Optional<Notification> readNotification(UUID owner, UUID id);
    Optional<Claim> claim();
    boolean finish(Claim claim, PaymentPeers.Outcome outcome);
    void retry(Claim claim);
}
