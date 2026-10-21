package com.arman.bank.authservice.infrastructure
import com.arman.bank.authservice.domain.LegacyMigrationContact
import spock.lang.Specification
import java.time.Instant
class LegacyMigrationContactSpec extends Specification {
 def 'new contact domains use one UTC microsecond codec without millisecond rounding'(){
  expect:
  LegacyMigrationContact.instant(Instant.parse('2026-10-18T08:00:00.123456789Z'))=='2026-10-18T08:00:00.123456Z'
  LegacyMigrationContact.instant(Instant.parse('2026-10-18T08:00:00Z'))=='2026-10-18T08:00:00Z'
 }
 def 'source namespace cannot equate absent baseline with candidate or verified generation'(){
  expect:
  LegacyMigrationContact.Source.values()*.name()==['CREDENTIAL_EMAIL','MIGRATION_OWNER_CONTACT','ABSENT_OWNER_CONTACT']
 }
}
