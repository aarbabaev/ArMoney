package com.arman.bank.paymentservice.application;

import java.time.Instant;
import java.util.UUID;

/** Exclusive immutable ordering boundary for descending history reads. */
public record PagePosition(Instant createdAt, UUID id) {}
