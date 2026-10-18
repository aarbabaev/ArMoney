package com.arman.bank.authservice.domain;

/** Source namespaces are closed and selected on Auth PRIMARY, never from a client claim. */
public enum ManagedEligibilitySource {
    REGISTRATION_DUAL_CONTACT, LEGACY_MIGRATION_DUAL_CONTACT
}
