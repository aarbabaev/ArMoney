-- Migration snapshots are not verification authority. Unresolved fences never expire.
create table migration_contact_counters (
 identity_id uuid primary key,
 revision bigint not null default 0 check(revision >= 0),
 generation bigint not null default 0 check(generation >= 0)
);
create table migration_baselines (
 migration_id uuid primary key,
 writer_id text not null check(writer_id ~ '^[A-Za-z0-9._-]{1,64}$'),
 stage_operation_id uuid not null,
 command_id uuid not null,
 identity_id uuid not null,
 state text not null check(state in ('RESERVED','PUBLISHED','RELEASED_AFTER_COMPLETION','ABORTED')),
 stage_command jsonb not null,
 stage_ack jsonb not null,
 publication_command jsonb,
 publication_ack jsonb,
 terminal_at timestamptz,
 unique(writer_id,stage_operation_id),
 unique(writer_id,command_id)
);
create unique index migration_one_unresolved_owner on migration_baselines(identity_id)
 where state in ('RESERVED','PUBLISHED');
create function migration_history_guard() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'Migration history is retained'; end if;
 if new.migration_id<>old.migration_id or new.writer_id<>old.writer_id or
    new.stage_operation_id<>old.stage_operation_id or new.command_id<>old.command_id or
    new.identity_id<>old.identity_id or new.stage_command<>old.stage_command or new.stage_ack<>old.stage_ack then
   raise exception 'Migration baseline is immutable';
 end if;
 if old.publication_command is not null and new.publication_command is distinct from old.publication_command then
   raise exception 'Migration command is immutable';
 end if;
 if old.publication_ack is not null and new.publication_ack is distinct from old.publication_ack then
   raise exception 'Migration ACK is immutable';
 end if;
 if not (new.state=old.state or (old.state='RESERVED' and new.state in ('PUBLISHED','ABORTED'))
   or (old.state='PUBLISHED' and new.state='RELEASED_AFTER_COMPLETION')) then
   raise exception 'Migration state is irreversible';
 end if;
 if new.state in ('PUBLISHED','RELEASED_AFTER_COMPLETION') and
    (new.publication_command is null or new.publication_ack is null) then raise exception 'Publication evidence required'; end if;
 return new;
end;
$$;
create trigger immutable_migration_history before update or delete on migration_baselines
 for each row execute function migration_history_guard();
create function migration_contact_guard() returns trigger language plpgsql as $$
declare owner_id uuid; active_state text; ack jsonb;
begin
 -- Every contact writer joins the same lock before inspecting the fence.
 perform pg_advisory_xact_lock(725631904128::bigint);
 if TG_OP='UPDATE' then
   if new.identity_id is distinct from old.identity_id then
     raise exception using errcode='P0001',message='Contact identity is immutable';
   end if;
 end if;
 if TG_OP='DELETE' then owner_id=old.identity_id; else owner_id=new.identity_id; end if;
 if TG_OP='UPDATE' and TG_TABLE_NAME='profiles' then
   if new.id=old.id and new.identity_id=old.identity_id and
      new.phone_number is not distinct from old.phone_number and
      new.phone_verified=old.phone_verified then return new; end if;
 end if;
 if TG_OP='UPDATE' and TG_TABLE_NAME='profile_directory' and new is not distinct from old then return new; end if;
 select state,publication_ack into active_state,ack from migration_baselines
 where identity_id=owner_id and state in ('RESERVED','PUBLISHED');
 if active_state is not null then
   -- Publication ACK is inserted in this transaction before these narrow flag writes.
   -- Ordinary callers cannot manufacture an ACK through any exposed command.
   if active_state='PUBLISHED' and ack is not null and TG_OP='UPDATE' then
     if TG_TABLE_NAME='profile_directory' then
       if new.identity_id=old.identity_id and new.profile_id=old.profile_id and
          new.shard_id=old.shard_id and new.initialized=old.initialized and
          new.phone_number is not distinct from old.phone_number and new.phone_verified then return new; end if;
     end if;
     if TG_TABLE_NAME='profiles' then
       if new.id=old.id and new.identity_id=old.identity_id and
          new.display_name=old.display_name and new.phone_number is not distinct from old.phone_number and
          new.phone_verified then return new; end if;
     end if;
   end if;
   raise exception using errcode='P0001',message='Migration contact baseline reserved';
 end if;
 insert into migration_contact_counters(identity_id,revision) values(owner_id,1)
 on conflict(identity_id) do update set revision=migration_contact_counters.revision+1;
 if TG_OP='DELETE' then return old; else return new; end if;
end;
$$;
create trigger migration_claim_guard before insert or update or delete on registration_phone_claims
 for each row execute function migration_contact_guard();
create trigger migration_directory_guard before insert or update or delete on profile_directory
 for each row execute function migration_contact_guard();
create trigger migration_profile_guard before insert or update or delete on profiles
 for each row execute function migration_contact_guard();
create trigger migration_projection_guard before insert or update or delete on verification_projection_current
 for each row execute function migration_contact_guard();
