-- Only the primary user database is an authority for these protocol records.
create table verification_projection_current (
    identity_id uuid primary key,
    version integer not null check (version > 0),
    canonical_phone text not null check (canonical_phone ~ '^\+9715[024568][0-9]{7}$'),
    phone_contact_version integer not null check (phone_contact_version > 0),
    verification_epoch bigint not null check (verification_epoch > 0)
);
create table verification_projection_tombstones (
    writer_id text not null check (writer_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    operation_id uuid not null,
    payload_hash text not null check (payload_hash ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz not null,
    primary key (writer_id, operation_id)
);
create table verification_projection_operations (
    writer_id text not null,
    operation_id uuid not null,
    identity_id uuid not null,
    expected_version integer not null check (expected_version >= 0 and expected_version < 2147483647),
    version integer not null check (version = expected_version + 1),
    canonical_phone text not null check (canonical_phone ~ '^\+9715[024568][0-9]{7}$'),
    phone_contact_version integer not null check (phone_contact_version > 0),
    verification_epoch bigint not null check (verification_epoch > 0),
    payload_hash text not null check (payload_hash ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz not null,
    primary key (writer_id, operation_id),
    unique(identity_id, version),
    foreign key (writer_id, operation_id) references verification_projection_tombstones(writer_id, operation_id)
);
create index verification_projection_expiry on verification_projection_operations(expires_at);
create table verification_projection_writer_budgets (
    writer_id text primary key check (writer_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    tokens numeric not null check (tokens >= 0 and tokens <= 20),
    updated_at timestamptz not null
);
create function immutable_projection_history() returns trigger language plpgsql as $$
begin
    raise exception 'Projection history is immutable';
end;
$$;
create trigger immutable_projection_operation before update on verification_projection_operations
for each row execute function immutable_projection_history();
create trigger immutable_projection_tombstone before update or delete on verification_projection_tombstones
for each row execute function immutable_projection_history();
