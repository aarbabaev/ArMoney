-- Every reserved slot remains charged forever, including terminal/hash-only tombstones.
create table legacy_migration_browser_capacity (
    singleton boolean primary key default true check(singleton),
    reserved_slots integer not null check(reserved_slots between 0 and 100000)
);
insert into legacy_migration_browser_capacity values(true,0);
create table legacy_migration_browser_continuations (
    migration_id uuid primary key references legacy_migration_intents(id),
    identity_id uuid not null references identities(id),
    actor varchar(64) not null,
    retention_key_id varchar(32) not null,
    cookie_hash bytea not null check(octet_length(cookie_hash)=32),
    account_deadline timestamptz not null,
    intent_deadline timestamptz not null check(intent_deadline>=account_deadline),
    reserved_slots integer not null default 32 check(reserved_slots=32),
    created_at timestamptz not null
);
create table legacy_migration_browser_commands (
    command_id uuid primary key,
    migration_id uuid not null references legacy_migration_browser_continuations(migration_id),
    slot integer not null check(slot between 1 and 32),
    prior_command_id uuid references legacy_migration_browser_commands(command_id),
    purpose varchar(32) not null check(purpose in ('STAGE','EMAIL_ISSUE','EMAIL_CONSUME','PHONE_ISSUE','PHONE_CONSUME','DECISION','PUBLICATION','FINAL_PREPARE','FINAL')),
    challenge_id uuid references legacy_migration_otp_challenges(id),
    eligible_at timestamptz,
    attempts_remaining integer not null check(attempts_remaining between 0 and 5),
    final_command_id uuid,
    csrf_hash bytea not null check(octet_length(csrf_hash)=32),
    unique(migration_id,slot),
    check((purpose in ('EMAIL_CONSUME','PHONE_CONSUME'))=(challenge_id is not null)),
    check((purpose='FINAL')=(final_command_id is not null))
);
create table legacy_migration_browser_claims (
    command_id uuid primary key references legacy_migration_browser_commands(command_id),
    otp_key_id varchar(32),
    payload_hash bytea not null check(octet_length(payload_hash)=32),
    claimed_at timestamptz not null
);
create table legacy_migration_browser_bundles (
    bundle_id uuid primary key,
    migration_id uuid not null references legacy_migration_browser_continuations(migration_id),
    sequence integer not null check(sequence between 1 and 33),
    status varchar(32) check(status in ('RECOVERY_REQUIRED','COMPLETED')),
    unique(migration_id,sequence)
);
create table legacy_migration_browser_bundle_forms (
    bundle_id uuid not null references legacy_migration_browser_bundles(bundle_id),
    ordinal integer not null check(ordinal in (0,1)),
    command_id uuid not null references legacy_migration_browser_commands(command_id),
    primary key(bundle_id,ordinal),unique(bundle_id,command_id)
);
create table legacy_migration_browser_results (
    command_id uuid primary key references legacy_migration_browser_claims(command_id),
    bundle_id uuid not null unique references legacy_migration_browser_bundles(bundle_id),
    completed_at timestamptz not null
);
-- Bounded technical worker custody, one row per permanently charged command.
-- This never changes its command payload, proof deadline or result journal.
create table legacy_migration_browser_executions (
    command_id uuid primary key references legacy_migration_browser_claims(command_id),
    fence uuid not null,
    request_id uuid not null,
    backend_pid integer not null check(backend_pid>0),
    backend_start timestamptz not null,
    permit_slot integer not null check(permit_slot between 0 and 15),
    active boolean not null
);
-- Do not allow capacity reuse, result rewriting or age-based eviction.
create function legacy_migration_browser_immutable() returns trigger language plpgsql as $$
begin raise exception 'immutable migration browser history'; end $$;
create trigger migration_browser_continuations_immutable before update or delete on legacy_migration_browser_continuations for each row execute function legacy_migration_browser_immutable();
create trigger migration_browser_commands_immutable before update or delete on legacy_migration_browser_commands for each row execute function legacy_migration_browser_immutable();
create trigger migration_browser_claims_immutable before update or delete on legacy_migration_browser_claims for each row execute function legacy_migration_browser_immutable();
create trigger migration_browser_bundles_immutable before update or delete on legacy_migration_browser_bundles for each row execute function legacy_migration_browser_immutable();
create trigger migration_browser_bundle_forms_immutable before update or delete on legacy_migration_browser_bundle_forms for each row execute function legacy_migration_browser_immutable();
create trigger migration_browser_results_immutable before update or delete on legacy_migration_browser_results for each row execute function legacy_migration_browser_immutable();
create function legacy_migration_browser_capacity_guard() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' or new.reserved_slots<old.reserved_slots then raise exception 'permanent migration browser capacity'; end if;
 return new;
end $$;
create trigger migration_browser_capacity_monotonic before update or delete on legacy_migration_browser_capacity for each row execute function legacy_migration_browser_capacity_guard();
