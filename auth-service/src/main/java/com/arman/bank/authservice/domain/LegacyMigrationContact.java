package com.arman.bank.authservice.domain;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
/** Separate namespaces: candidate version is not a verified contact generation. */
public final class LegacyMigrationContact {
 private LegacyMigrationContact(){}
 public enum Source { CREDENTIAL_EMAIL, MIGRATION_OWNER_CONTACT, ABSENT_OWNER_CONTACT }
 /** NEW Rev5 domains only. Never use to reinterpret persisted v1 hashes. */
 public static String instant(Instant value){return DateTimeFormatter.ISO_INSTANT.format(java.util.Objects.requireNonNull(value).truncatedTo(ChronoUnit.MICROS));}
}
