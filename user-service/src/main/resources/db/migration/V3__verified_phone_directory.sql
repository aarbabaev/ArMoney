alter table profiles add column phone_number text;
alter table profiles add column phone_verified boolean not null default false;
alter table profiles add constraint canonical_phone check (phone_number is null or phone_number ~ '^\+[1-9][0-9]{7,14}$');
alter table profiles add constraint verified_phone_present check (not phone_verified or phone_number is not null);
create unique index unique_verified_phone on profiles(phone_number) where phone_verified;

create table phone_verification_audit (
    id uuid primary key,
    identity_id uuid not null references profiles(identity_id),
    phone_number text not null,
    operator_reference text not null check (length(operator_reference) between 1 and 100),
    evidence_reference text not null check (length(evidence_reference) between 1 and 200),
    verified_at timestamptz not null default current_timestamp
);
create table phone_lookup_limits (
    requester_id uuid primary key,
    window_start timestamptz not null,
    attempts integer not null check (attempts between 1 and 30)
);
