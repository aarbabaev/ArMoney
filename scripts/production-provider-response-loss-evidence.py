"""Read-only evidence for actual dropped Auth responses in a fresh isolated fixture.
No CLI, HTTP, Docker, environment reads, replay, writes, or response reconstruction.
The caller owns the DB connection and supplies selectors captured from actual requests/replies.
Only public_receipt()/compare() return publishable values. Never serialize private snapshots.
"""
from dataclasses import dataclass, field
import hashlib
import re
import time
import uuid

ISSUER = 'https://fixture.local:8443/sso/realms/armoney'
CLIENTS = frozenset(('armoney-android', 'armoney-ios', 'armoney-web'))
SELECTOR_KEYS = frozenset(('operation','issuer','subject','client_id','user_session_id','command_id','intent_id','event_id','proof_id'))
MAX_COUNT = 64
MAX_EVENTS = 16
BUDGET_SECONDS = 3

class Rejected(ValueError):
    def __init__(self):
        super().__init__('response_loss_evidence_rejected')

def _uuid(value):
    if type(value) is not str:
        raise Rejected()
    try:
        if str(uuid.UUID(value)) != value:
            raise Rejected()
    except (ValueError, AttributeError):
        raise Rejected() from None
    return value

def _text(value, maximum):
    if type(value) is not str or not value or len(value)>maximum or any(c.isspace() or ord(c)<33 or 0x7f<=ord(c)<=0x9f or 0xd800<=ord(c)<=0xdfff for c in value):
        raise Rejected()
    return value

def _scope(value):
    if type(value) is not dict or set(value)!={'issuer','subject'} or value['issuer']!=ISSUER:
        raise Rejected()
    return (ISSUER, _uuid(value['subject']))

def _selector(value):
    if type(value) is not dict or set(value)!=SELECTOR_KEYS or value['operation'] not in ('STAGE','CONFIRM'):
        raise Rejected()
    scope=_scope({k:value[k] for k in ('issuer','subject')})
    if type(value['client_id']) is not str or value['client_id'] not in CLIENTS:
        raise Rejected()
    _text(value['user_session_id'],255)
    for name in ('command_id','intent_id','event_id'):
        _uuid(value[name])
    if value['operation']=='STAGE':
        if value['proof_id'] is not None:
            raise Rejected()
    else:
        _uuid(value['proof_id'])
    return scope

def _digest(fields):
    return hashlib.sha256(b''.join(len(s.encode()).to_bytes(4,'big')+s.encode() for s in fields)).digest()

# All identifiers below are static. The driver binds every value; no SQL interpolation.
OWNER = """select m.identity_id from verification_enrollments e
 join verification_authorities a on a.enrollment_id=e.id
 join managed_registration_principals m on m.enrollment_id=e.id and m.identity_id=a.pending_identity_id and m.identity_id=a.mapped_identity_id
 join external_identities x on x.issuer=e.issuer and x.subject=e.subject and x.identity_id=m.identity_id
 where e.issuer=%s and e.subject=%s and e.state='VERIFIED' and a.state='PROJECTED'
 and e.expires_at>clock_timestamp() and a.expires_at>clock_timestamp()
 and not exists(select 1 from migration_banking_authority_current q where q.issuer=e.issuer and q.subject=e.subject)"""
COUNTS_SQL = """with owner as ("""+OWNER+""")
select (select count(*)::int from owner) as owner_count,
 (select count(*)::int from managed_banking_operations where identity_id in(select identity_id from owner)) as bank_operations,
 (select count(*)::int from managed_banking_event_consumptions where identity_id in(select identity_id from owner)) as bank_consumptions,
 ((select count(*) from managed_banking_sessions where identity_id in(select identity_id from owner))+
  (select count(*) from sessions where identity_id in(select identity_id from owner)))::int as bank_sessions,
 (select count(*)::int from managed_banking_completions c join managed_banking_operations o on o.id=c.operation_id where o.identity_id in(select identity_id from owner)) as bank_completions
"""
EVENTS_SQL = """with owner as ("""+OWNER+""")
select v.event_id::text from sso_code_confirmation_results r join sso_login_proofs p on p.id=r.proof_id
 join sso_login_events v on v.issuer=p.issuer and v.event_id=p.event_id
 where v.identity_id in(select identity_id from owner) and v.issuer=%s and p.kind='interactive'
 order by v.event_id limit 17
"""
BANK_EVENTS_SQL = """with owner as ("""+OWNER+""")
select c.event_id::text,exists(select 1 from managed_banking_sessions s where s.issuer=c.issuer and s.event_id=c.event_id and s.identity_id=c.identity_id)
 from managed_banking_event_consumptions c where c.identity_id in(select identity_id from owner) and c.issuer=%s
 order by c.event_id limit 17
"""
ORIGINAL_SQL = """with i as (select * from sso_code_intents where id=%s::uuid),
 d as (select d.* from sso_code_intent_details d join i on i.id=d.intent_id where d.event_id=%s::uuid),
 c as (select c.* from sso_code_intent_commands c join i on i.id=c.intent_id where c.command_id=%s::uuid),
 r as (select r.* from sso_code_confirmation_results r join i on i.id=r.intent_id),
 p as (select p.* from sso_login_proofs p join r on r.proof_id=p.id),
 v as (select v.* from sso_login_events v join p on p.issuer=v.issuer and p.event_id=v.event_id),
 a as (select a.* from sso_code_confirmation_anchors a join i on i.id=a.intent_id),
 ac as (select c.* from sso_code_confirmation_anchor_completions c join a on a.id=c.anchor_id)
select (select count(*)::int from i) as original_stages,
 (select count(*)::int from d) as original_details,
 (select count(*)::int from c) as original_commands,
 (select count(*)::int from r) as original_confirmations,
 (select count(*)::int from p) as original_proofs,
 (select count(*)::int from v) as original_events,
 coalesce((select bool_and(retained_until>clock_timestamp()) from i),false)
 and coalesce((select bool_and(erase_at>clock_timestamp()) from d),false) as retention_current,
 coalesce((select bool_and(p.id=%s::uuid and p.event_id=%s::uuid and p.issuer=%s and p.binding_hash=%s) from p),false) as proof_selector_matches,
 (select encode(sha256(convert_to(jsonb_build_array(
  (select coalesce(jsonb_agg(to_jsonb(i) order by id),'[]'::jsonb) from i),
  (select coalesce(jsonb_agg(to_jsonb(d) order by intent_id),'[]'::jsonb) from d),
  (select coalesce(jsonb_agg(to_jsonb(c) order by command_id),'[]'::jsonb) from c),
  (select coalesce(jsonb_agg(to_jsonb(r) order by intent_id),'[]'::jsonb) from r),
  (select coalesce(jsonb_agg(to_jsonb(p) order by id),'[]'::jsonb) from p),
  (select coalesce(jsonb_agg(to_jsonb(v) order by event_id),'[]'::jsonb) from v),
  (select coalesce(jsonb_agg(to_jsonb(a) order by generation),'[]'::jsonb) from a),
  (select coalesce(jsonb_agg(to_jsonb(ac) order by anchor_id),'[]'::jsonb) from ac)
 )::text,'UTF8')),'hex')) as private_fingerprint
"""

@dataclass(frozen=True, repr=False)
class Snapshot:
    _scope_hash: bytes = field(repr=False)
    _counts: tuple = field(repr=False)
    _events: tuple = field(repr=False)
    _original_counts: tuple | None = field(default=None,repr=False)
    _fingerprint: str | None = field(default=None,repr=False)
    _selector_hash: bytes | None = field(default=None,repr=False)
    _original_event: str | None = field(default=None,repr=False)
    _retention_current: bool = field(default=False,repr=False)
    _bank_events: tuple = field(default=(),repr=False)
    def __repr__(self):
        return 'ResponseLossSnapshot[private]'

def _rows(cur, sql, args, end, clock):
    # Never start another 1s server statement without a full 1s left.
    if clock()>=end-1:
        raise Rejected()
    cur.execute(sql,args)
    rows=cur.fetchall()
    if clock()>=end:
        raise Rejected()
    return rows

def capture(connection, private_scope, original=None, *, clock=time.monotonic):
    """Caller configures READ ONLY and statement_timeout<=1000ms, and runs this helper in an owned
    subprocess with a hard 3s deadline (network/cancel uncertainty must not extend authorization).
    No implicit transaction changes. Local deadline checks do not establish physical I/O cancellation.
    private_scope is exact issuer/subject; original is the closed selector schema above.
    STAGE owner provenance additionally requires the owned fault peer to match the actual original
    request binding to private_scope: Auth's stage binding is keyed and not independently reversible.
    """
    try:
        scope=_scope(private_scope)
        if original is not None and _selector(original)!=scope:
            raise Rejected()
        end=clock()+BUDGET_SECONDS
        with connection.cursor() as cur:
            settings=_rows(cur,"select current_setting('transaction_read_only'),current_setting('statement_timeout')",(),end,clock)
            if len(settings)!=1 or settings[0][0]!='on':
                raise Rejected()
            timeout=settings[0][1]
            if type(timeout) is not str or not re.fullmatch(r'(?:[1-9][0-9]{0,2}ms|1000ms|1s)',timeout):
                raise Rejected()
            rows=_rows(cur,COUNTS_SQL,scope,end,clock)
            if len(rows)!=1 or len(rows[0])!=5 or any(type(n) is not int or n<0 or n>MAX_COUNT for n in rows[0]) or rows[0][0]!=1:
                raise Rejected()
            counts=tuple(rows[0][1:])
            rows=_rows(cur,EVENTS_SQL,scope+(scope[0],),end,clock)
            if len(rows)>MAX_EVENTS or any(len(r)!=1 for r in rows):
                raise Rejected()
            events=tuple(sorted(_uuid(r[0]) for r in rows))
            if len(set(events))!=len(events):
                raise Rejected()
            bank_rows=_rows(cur,BANK_EVENTS_SQL,scope+(scope[0],),end,clock)
            if len(bank_rows)>MAX_EVENTS or any(len(r)!=2 or type(r[1]) is not bool for r in bank_rows):
                raise Rejected()
            bank_events=tuple(sorted((_uuid(r[0]),r[1]) for r in bank_rows))
            if len({r[0] for r in bank_events})!=len(bank_events) or len(bank_events)!=counts[1]:
                raise Rejected()
            scoped=_digest(list(scope))
            if original is None:
                return Snapshot(scoped,counts,events,_bank_events=bank_events)
            binding=_digest(['armoney-login-proof-binding-v1',scope[0],scope[1],original['client_id'],original['user_session_id']])
            args=(original['intent_id'],original['event_id'],original['command_id'],original['proof_id'],original['event_id'],scope[0],binding)
            rows=_rows(cur,ORIGINAL_SQL,args,end,clock)
            if len(rows)!=1 or len(rows[0])!=9:
                raise Rejected()
            row=rows[0]
            if any(type(n) is not int or n<0 or n>1 for n in row[:6]) or tuple(row[:3])!=(1,1,1) or type(row[6]) is not bool or not row[6] or type(row[7]) is not bool:
                raise Rejected()
            expected=(0,0,0) if original['operation']=='STAGE' else (1,1,1)
            if tuple(row[3:6])!=expected or (original['operation']=='CONFIRM' and (not row[7] or original['event_id'] not in events)):
                raise Rejected()
            if type(row[8]) is not str or not re.fullmatch(r'[0-9a-f]{64}',row[8]):
                raise Rejected()
            selector_hash=_digest([str(original[k]) for k in sorted(SELECTOR_KEYS)])
            return Snapshot(scoped,counts,events,tuple(row[:6]),row[8],selector_hash,original['event_id'],True,bank_events)
    except Exception:
        raise Rejected() from None

def public_receipt(snapshot):
    if type(snapshot) is not Snapshot:
        raise Rejected()
    names=('bank_operations','bank_consumptions','bank_sessions','bank_completions')
    result=dict(zip(names,snapshot._counts))
    result['confirmed_event_count']=len(snapshot._events)
    if snapshot._original_counts is not None:
        result.update(dict(zip(('original_stages','original_details','original_commands','original_confirmations','original_proofs','original_events'),snapshot._original_counts)))
        result['original_retention_current']=snapshot._retention_current
    return result

def compare(before, dropped, fresh):
    if any(type(x) is not Snapshot for x in (before,dropped,fresh)) or not(before._scope_hash==dropped._scope_hash==fresh._scope_hash) or dropped._fingerprint is None or dropped._selector_hash!=fresh._selector_hash:
        raise Rejected()
    added=set(fresh._events)-set(dropped._events)
    removed=set(dropped._events)-set(fresh._events)
    unchanged=dropped._fingerprint==fresh._fingerprint and dropped._original_counts==fresh._original_counts
    no_writes=before._counts==dropped._counts and before._bank_events==dropped._bank_events
    retained=dropped._retention_current and fresh._retention_current
    unique=len(added)==1 and not removed and dropped._original_event not in added
    deltas=tuple(y-x for x,y in zip(dropped._counts,fresh._counts))
    added_bank=set(fresh._bank_events)-set(dropped._bank_events)
    removed_bank=set(dropped._bank_events)-set(fresh._bank_events)
    linked=not removed_bank and len(added_bank)==1 and {event for event,has_session in added_bank if has_session}==added
    bank=linked and deltas[1:3]==(1,1) and 1<=deltas[0]<=MAX_EVENTS and deltas[3]==deltas[0]
    # The dropped CONFIRM may add its one immutable event; STAGE must add none.
    expected_drop=set() if dropped._original_counts[3]==0 else {dropped._original_event}
    drop_events=dropped._original_event not in before._events and set(dropped._events)-set(before._events)==expected_drop and not(set(before._events)-set(dropped._events))
    return {'original_unchanged':unchanged,'lost_attempt_no_banking_writes':no_writes,
            'original_retention_current':retained,'fresh_unique_confirmation':unique,
            'dropped_event_inventory_matches':drop_events,'fresh_banking_result':bank,
            'verified':all((unchanged,no_writes,retained,unique,bank,drop_events))}
