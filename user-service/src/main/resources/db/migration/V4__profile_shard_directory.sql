-- Global identity placement and phone state belong to the primary user database.
create table profile_directory (
    identity_id uuid primary key,
    profile_id uuid not null unique,
    shard_id text not null check (shard_id ~ '^[a-z0-9_]+$'),
    initialized boolean not null default false,
    phone_number text check (phone_number is null or phone_number ~ '^\+[1-9][0-9]{7,14}$'),
    phone_verified boolean not null default false,
    check (not phone_verified or phone_number is not null)
);
insert into profile_directory(identity_id, profile_id, shard_id, initialized, phone_number, phone_verified)
select identity_id, id, 'primary', true, phone_number, phone_verified from profiles;
create unique index directory_unique_verified_phone on profile_directory(phone_number) where phone_verified;
alter table phone_verification_audit drop constraint phone_verification_audit_identity_id_fkey;
alter table phone_verification_audit add foreign key (identity_id) references profile_directory(identity_id);
create function preserve_profile_placement() returns trigger language plpgsql as $$
begin
    if new.identity_id is distinct from old.identity_id or new.profile_id is distinct from old.profile_id
       or new.shard_id is distinct from old.shard_id or (old.initialized and not new.initialized) then
        raise exception 'Profile placement is immutable';
    end if;
    return new;
end;
$$;
create trigger immutable_profile_placement before update on profile_directory
for each row execute function preserve_profile_placement();
