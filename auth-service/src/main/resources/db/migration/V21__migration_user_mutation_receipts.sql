-- Immutable denial/classification custody only; no backfill or positive banking authority.
CREATE TABLE auth_user_mutation_receipts (
 writer_id varchar(64) NOT NULL CHECK(writer_id ~ '^[A-Za-z0-9_-]{1,64}$'),
 operation_id uuid NOT NULL,identity_id uuid NOT NULL REFERENCES identities(id),
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 canonical_request text NOT NULL CHECK(octet_length(canonical_request) BETWEEN 1 AND 4096),
 outcome varchar(16) NOT NULL CHECK(outcome IN ('NOT_MIG','SUSPENDED')),
 response_json text NOT NULL CHECK(octet_length(response_json) BETWEEN 1 AND 4096),
 recorded_at timestamptz NOT NULL,
 source_kind varchar(3),migration_id uuid,decision_id uuid,classification_generation bigint,
 previous_epoch bigint,previous_source_revision bigint,resulting_epoch bigint,resulting_source_revision bigint,
 PRIMARY KEY(writer_id,operation_id),
 FOREIGN KEY(decision_id,identity_id) REFERENCES legacy_migration_publication_decisions(decision_id,identity_id),
 FOREIGN KEY(identity_id,decision_id,previous_epoch,previous_source_revision)
   REFERENCES migration_banking_authorities(identity_id,decision_id,verification_epoch,source_revision),
 FOREIGN KEY(identity_id,decision_id,resulting_epoch,resulting_source_revision)
   REFERENCES migration_banking_authorities(identity_id,decision_id,verification_epoch,source_revision),
 CHECK((outcome='NOT_MIG' AND source_kind IS NULL AND migration_id IS NULL AND decision_id IS NULL
   AND classification_generation IS NULL AND previous_epoch IS NULL AND previous_source_revision IS NULL
   AND resulting_epoch IS NULL AND resulting_source_revision IS NULL)
 OR(outcome='SUSPENDED' AND source_kind IS NOT NULL AND source_kind='MIG' AND migration_id IS NOT NULL
   AND decision_id IS NOT NULL AND classification_generation IS NOT NULL AND classification_generation>0
   AND previous_epoch IS NOT NULL AND previous_epoch>0 AND previous_source_revision IS NOT NULL AND previous_source_revision>0
   AND resulting_epoch IS NOT NULL AND resulting_epoch>0 AND resulting_source_revision IS NOT NULL AND resulting_source_revision>0
   AND ((resulting_epoch=previous_epoch AND resulting_source_revision=previous_source_revision)
      OR(resulting_epoch=previous_epoch+1 AND resulting_source_revision=previous_source_revision+1))))
);
-- The existing globally ordered controls serialize count+insert at the 100000 retained-row cap.
-- No TTL/pruning/tombstone exclusion: every retained row remains charged and replayable.
CREATE FUNCTION auth_user_mutation_receipt_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable user mutation receipt'; END $$;
CREATE TRIGGER auth_user_mutation_receipt_immutable BEFORE UPDATE OR DELETE ON auth_user_mutation_receipts
 FOR EACH ROW EXECUTE FUNCTION auth_user_mutation_receipt_immutable();
