package com.arman.bank.paymentservice.application;

import com.arman.bank.paymentservice.domain.*;
import java.util.*;
import java.util.concurrent.Semaphore;

public final class PaymentService {
    private final PaymentStore store;
    private final PaymentPeers peers;
    private final Semaphore resolutions = new Semaphore(8);
    public PaymentService(PaymentStore store, PaymentPeers peers) { this.store = store; this.peers = peers; }

    public Payment submit(UUID requester, String key, PaymentRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}") || requester.equals(request.recipientId()))
            throw new PaymentFailure(400, "invalid_request");
        // A durable replay never consults mutable phone/wallet mappings or remote availability.
        var existing = store.findKey(requester, key, request);
        if (existing.isPresent()) return existing.get();
        if (!request.recipientPhone().matches("\\+9715[024568][0-9]{7}"))
            throw new PaymentFailure(400, "invalid_request");
        if (!resolutions.tryAcquire()) throw new PaymentFailure(503, "service_unavailable");
        try {
            var mapping = peers.resolve(requester, request);
            return store.create(requester, key, request, mapping);
        } catch (PaymentFailure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new PaymentFailure(503, "service_unavailable"); }
        catch (Exception e) { throw new PaymentFailure(503, "service_unavailable"); }
        finally { resolutions.release(); }
    }
    public Payment payment(UUID owner, UUID id) {
        return store.visible(owner, id).orElseThrow(() -> new PaymentFailure(404, "not_found"));
    }
    public List<Payment> history(UUID owner) { return store.history(owner); }
    public List<Notification> notifications(UUID owner) { return store.notifications(owner); }
    public Notification readNotification(UUID owner, UUID id) {
        return store.readNotification(owner, id).orElseThrow(() -> new PaymentFailure(404, "not_found"));
    }
}
