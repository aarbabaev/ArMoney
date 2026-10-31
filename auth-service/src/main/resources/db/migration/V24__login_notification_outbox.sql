-- Only new successful managed interactive logins; no historical backfill.
create table login_notification_outbox (
 id uuid primary key, issuer varchar(512) not null, event_id uuid not null,
 identity_id uuid not null references identities(id), key_id varchar(32) not null,
 authority_stamp bytea not null check(octet_length(authority_stamp)=32),
 occurred_at timestamptz not null, created_at timestamptz not null,
 expires_at timestamptz not null, tombstone_until timestamptz not null,
 state varchar(16) not null check(state in ('PENDING','LEASED','SENT','DEAD','EXPIRED','SKIPPED')),
 attempts integer not null default 0 check(attempts between 0 and 5),
 next_attempt_at timestamptz not null, lease_id uuid, leased_until timestamptz,
 profile_frozen boolean not null default false,
 capture_nonce bytea not null check(octet_length(capture_nonce)=12),
 nonce bytea not null check(octet_length(nonce)=12), ciphertext bytea,
 unique(issuer,event_id), unique(key_id,nonce), unique(key_id,capture_nonce),
 check(expires_at>created_at and expires_at<=created_at+interval '86400 seconds'),
 check(tombstone_until=created_at+interval '604800 seconds'),
 check((state='LEASED')=(lease_id is not null and leased_until is not null)),
 check((state in ('PENDING','LEASED') and ciphertext is not null and octet_length(ciphertext) between 16 and 4096)
    or (state in ('SENT','DEAD','EXPIRED','SKIPPED') and ciphertext is null))
);
create index login_notification_due on login_notification_outbox(next_attempt_at) where state in ('PENDING','LEASED');
create function login_notification_immutable() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then
  if OLD.tombstone_until>clock_timestamp() then raise exception 'retained login notice'; end if;
  return OLD;
 end if;
 if row(NEW.id,NEW.issuer,NEW.event_id,NEW.identity_id,NEW.key_id,NEW.authority_stamp,NEW.occurred_at,NEW.created_at,NEW.expires_at,NEW.tombstone_until,NEW.capture_nonce)
   is distinct from row(OLD.id,OLD.issuer,OLD.event_id,OLD.identity_id,OLD.key_id,OLD.authority_stamp,OLD.occurred_at,OLD.created_at,OLD.expires_at,OLD.tombstone_until,OLD.capture_nonce)
   or NEW.attempts<OLD.attempts or (OLD.profile_frozen and not NEW.profile_frozen)
   or OLD.state in ('SENT','DEAD','EXPIRED','SKIPPED') then raise exception 'immutable login notice'; end if;
 return NEW;
end $$;
create trigger login_notification_immutable before update or delete on login_notification_outbox for each row execute function login_notification_immutable();
