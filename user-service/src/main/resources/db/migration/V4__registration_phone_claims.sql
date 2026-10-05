-- Fail closed on duplicate historical numbers; resolve conflicts explicitly before upgrade.
create unique index unique_profile_phone on profiles(phone_number) where phone_number is not null;
create table registration_phone_claims (
    identity_id uuid primary key,
    phone_number text not null unique check (phone_number ~ '^\+9715[024568][0-9]{7}$'),
    created_at timestamptz not null default current_timestamp
);
