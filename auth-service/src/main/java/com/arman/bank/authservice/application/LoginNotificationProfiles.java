package com.arman.bank.authservice.application;
import java.util.UUID;
/** Optional owner-matched presentation data; never recipient authority. */
@FunctionalInterface public interface LoginNotificationProfiles { String displayName(UUID owner) throws InterruptedException; }
