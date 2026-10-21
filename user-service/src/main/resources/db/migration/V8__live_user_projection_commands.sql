-- Immutable technical request custody. Not an authorization or business fence.
create table live_user_projection_commands (
 writer_id text not null check(writer_id ~ '^[A-Za-z0-9._-]{1,64}$'),
 operation_id uuid not null,
 identity_id uuid not null,
 projection_command jsonb not null check(octet_length(projection_command::text)<=4096),
 suspension_request jsonb not null check(octet_length(suspension_request::text)<=4096),
 payload_hash text not null check(payload_hash ~ '^[0-9a-f]{64}$'),
 created_at_us bigint not null check(created_at_us>=0),
 primary key(writer_id,operation_id)
);
create function live_user_projection_immutable() returns trigger language plpgsql as $$
begin
 raise exception 'Live User command is immutable';
end;
$$;
create trigger live_user_projection_history before update or delete on live_user_projection_commands
 for each row execute function live_user_projection_immutable();
