-- Retained post-FINAL release evidence only. Never eligibility or a fresh proof.
create table legacy_migration_user_release_acks (
    decision_id uuid primary key references legacy_migration_final_completions(decision_id),
    migration_id uuid not null unique references legacy_migration_intents(id),
    identity_id uuid not null references identities(id),
    writer_id varchar(64) not null,
    retention_key_id varchar(32) not null,
    journal_hash text not null check(journal_hash ~ '^[0-9a-f]{64}$'),
    completion_hash text not null check(completion_hash ~ '^[0-9a-f]{64}$'),
    response_json text not null check(octet_length(response_json)<=4096),
    acknowledged_at timestamptz not null,
    provenance_hash text not null check(provenance_hash ~ '^[0-9a-f]{64}$')
);
create function legacy_migration_user_release_immutable() returns trigger language plpgsql as $$
begin raise exception 'immutable migration User release acknowledgement'; end $$;
create trigger migration_user_release_ack_immutable before update or delete on legacy_migration_user_release_acks
for each row execute function legacy_migration_user_release_immutable();
