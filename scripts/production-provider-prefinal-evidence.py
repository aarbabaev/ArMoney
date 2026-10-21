"""Auth-owned pre-FINAL storage evidence only, for a fresh isolated migration fixture.
Not full migration acceptance: does not authenticate receipt HMACs, provider tokens/PKCE/cookies,
HTTP admission/session validity, User/wallet/balance preservation, or provider claim absence.
The owned actual browser workflow must establish those separately. No SQL writes or CLI/network.
Root supplies a READ ONLY connection, <=1s statement/lock timeouts, and hard 3s kill/reap.
A historical baseline precedes admission: owner/credential/association generations stay exact;
email generation increases once when genuine admission establishes its migration contact baseline.
Baseline values and selectors are private; never print/serialize them into public receipts.
"""
import datetime as D
import re
import time
import uuid

ISSUER='https://fixture.local:8443/sso/realms/armoney'
SCOPE_FIELDS=frozenset(('owner_id','mode','issuer','legacy_subject','target_subject','target_client','session_hash'))
BASE_FIELDS=SCOPE_FIELDS|frozenset(('version','session_row_id','created_at','expires_at','owner_generation','credential_generation','email_generation','association_generation','session_count'))
MODES=('LOCAL_PASSWORD','LEGACY_OIDC_REAUTH')
CLIENTS=('armoney-android','armoney-ios','armoney-web')
RESULT_FIELDS=('pending_owner_bound','generations_current','original_session_continuity','legacy_session_count_unchanged',
 'authority_association_consistent','managed_receipt_chain_consistent','account_receipt_chain_consistent',
 'no_final_or_otp_publication','no_promoted_authority','no_banking_writes','no_c2_proofs')
class Rejected(ValueError):
    def __init__(self):super().__init__('prefinal_evidence_rejected')

def _uuid(value):
    if type(value) is not str:raise Rejected()
    try:
        if str(uuid.UUID(value))!=value:raise Rejected()
    except Exception:raise Rejected() from None
    return value

def _scope(value):
    if type(value) is not dict or set(value)!=SCOPE_FIELDS or value['issuer']!=ISSUER or value['mode'] not in MODES or value['target_client'] not in CLIENTS:raise Rejected()
    _uuid(value['owner_id']);_uuid(value['target_subject'])
    if type(value['session_hash']) is not str or not re.fullmatch('[0-9a-f]{64}',value['session_hash']):raise Rejected()
    if value['mode']=='LOCAL_PASSWORD':
        if value['legacy_subject'] is not None:raise Rejected()
    elif _uuid(value['legacy_subject'])!=value['target_subject']:raise Rejected()
    return dict(value)

def _integer(value):
    if type(value) is not int or not 0<=value<=9223372036854775807:raise Rejected()
    return value

def _stamp(value):
    if type(value) is not str or not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}\+00:00',value):raise Rejected()
    result=D.datetime.fromisoformat(value)
    if result.isoformat(timespec='microseconds')!=value:raise Rejected()
    return result

def _baseline(value,scope):
    if type(value) is not dict or set(value)!=BASE_FIELDS or type(value['version']) is not int or value['version']!=1 or any(value[k]!=scope[k] for k in SCOPE_FIELDS):raise Rejected()
    _uuid(value['session_row_id'])
    if not _stamp(value['created_at'])<_stamp(value['expires_at']):raise Rejected()
    for k in ('owner_generation','credential_generation','email_generation'): _integer(value[k])
    if value['email_generation']==9223372036854775807:raise Rejected()
    if type(value['session_count']) is not int or not 1<=value['session_count']<=64:raise Rejected()
    if scope['mode']=='LOCAL_PASSWORD':
        if value['association_generation'] is not None:raise Rejected()
    else:_integer(value['association_generation'])
    return value

BASELINE_SQL="""
with p as(select %s::uuid as owner,%s::text as token,%s::text as mode,%s::text as issuer,%s::text as legacy_subject)
select r.row_id::text,s.created_at,s.expires_at,g.owner_generation,g.credential_generation,g.email_generation,
 case when p.mode='LEGACY_OIDC_REAUTH' then a.generation else null end,
 (select count(*)::int from sessions where identity_id=p.owner),
 s.expires_at>clock_timestamp(),
 case when p.mode='LOCAL_PASSWORD' then i.password_hash is not null
 else a.active and a.identity_id=p.owner and x.identity_id=p.owner end,
 not exists(select 1 from legacy_migration_intents where identity_id=p.owner),
 not exists(select 1 from managed_registration_principals where identity_id=p.owner)
 and not exists(select 1 from managed_identity_sources where identity_id=p.owner)
from p join identities i on i.id=p.owner join sessions s on s.identity_id=p.owner and s.token_hash=p.token
join legacy_auth_session_rows r on r.token_hash=s.token_hash join legacy_auth_generations g on g.identity_id=p.owner
left join legacy_auth_association_generations a on a.issuer=p.issuer and a.subject=p.legacy_subject
left join external_identities x on x.issuer=p.issuer and x.subject=p.legacy_subject
limit 2
"""
PREFINAL_SQL="""
with p as(select %s::uuid as owner,%s::uuid as migration,%s::text as mode,%s::text as issuer,%s::text as legacy_subject,
 %s::text as target_subject,%s::text as client,%s::text as token,%s::uuid as original_row,
 %s::timestamptz as original_created,%s::timestamptz as original_expiry,%s::bigint as owner_generation,
 %s::bigint as credential_generation,%s::bigint as email_generation,%s::bigint as association_generation,%s::int as original_session_count),
 i as(select i.* from legacy_migration_intents i join p on i.id=p.migration and i.identity_id=p.owner),
 owners as(select owner as id from p union select x.identity_id from external_identities x join p on x.issuer=p.issuer and x.subject=p.target_subject)
select
 (select count(*)=1 from i where state='OWNER_BOUND' and proof_mode=p.mode and authorization_deadline>clock_timestamp()) as pending_owner_bound,
 exists(select 1 from i join legacy_auth_generations g on g.identity_id=i.identity_id
 where g.owner_generation=p.owner_generation and i.owner_generation=g.owner_generation
 and g.credential_generation=p.credential_generation and i.credential_generation=g.credential_generation
 and g.email_generation=p.email_generation+1 and i.email_generation=g.email_generation
 and (p.mode='LOCAL_PASSWORD' or (i.legacy_issuer=p.issuer and i.legacy_subject=p.legacy_subject and i.association_generation=p.association_generation
 and exists(select 1 from legacy_auth_association_generations a join external_identities x on x.issuer=a.issuer and x.subject=a.subject
 where a.issuer=p.issuer and a.subject=p.legacy_subject and a.active and a.identity_id=p.owner and x.identity_id=p.owner and a.generation=p.association_generation)))) as generations_current,
 exists(select 1 from legacy_migration_continuity c join sessions s on s.token_hash=c.token_hash and s.identity_id=c.identity_id
 join legacy_auth_session_rows r on r.token_hash=s.token_hash
 where c.migration_id=p.migration and c.identity_id=p.owner and c.token_hash=p.token and c.session_row_id=p.original_row and r.row_id=p.original_row
 and c.owner_generation=p.owner_generation and c.original_created_at=p.original_created and c.original_expires_at=p.original_expiry
 and s.created_at=p.original_created and s.expires_at=p.original_expiry and s.expires_at>clock_timestamp()) as original_session_continuity,
 (select count(*)=p.original_session_count from sessions where identity_id=p.owner) as legacy_session_count_unchanged,
 (not exists(select 1 from verification_authorities where mapped_identity_id=p.owner or pending_identity_id=p.owner)
 or ((select count(*) from verification_authorities where mapped_identity_id=p.owner or pending_identity_id=p.owner)=1
 and exists(select 1 from legacy_migration_associations a join verification_authorities v on v.enrollment_id=a.enrollment_id
 where a.migration_id=p.migration and a.owner_generation=p.owner_generation and (v.mapped_identity_id=p.owner or v.pending_identity_id=p.owner)))) as authority_association_consistent,
 exists(select 1 from legacy_migration_managed_attempts a join legacy_migration_managed_claims claim on claim.attempt_id=a.id
 join legacy_migration_managed_receipts r on r.attempt_id=a.id and r.receipt_id=a.receipt_id
 join legacy_migration_managed_targets t on t.migration_id=r.migration_id and t.receipt_id=r.receipt_id
 join legacy_migration_managed_completions c on c.attempt_id=a.id and c.receipt_id=r.receipt_id
 where a.migration_id=p.migration and a.owner_id=p.owner and r.migration_id=p.migration and r.owner_id=p.owner and t.owner_id=p.owner
 and a.account_proof_mode=p.mode and r.account_proof_mode=p.mode and a.account_receipt_hash=r.account_receipt_hash
 and a.owner_generation=p.owner_generation and r.owner_generation=p.owner_generation and t.owner_generation=p.owner_generation
 and a.mode_generation=case when p.mode='LOCAL_PASSWORD' then p.credential_generation else p.association_generation end
 and r.mode_generation=a.mode_generation and t.mode_generation=a.mode_generation
 and a.email_generation=p.email_generation+1 and r.email_generation=a.email_generation and t.email_generation=a.email_generation
 and a.issuer=p.issuer and a.client_id=p.client and a.redirect_uri='https://fixture.local:8443/migration/managed/callback'
 and r.issuer=p.issuer and r.subject=p.target_subject and r.client_id=p.client and t.issuer=r.issuer and t.subject=r.subject
 and (p.mode='LEGACY_OIDC_REAUTH' or not exists(select 1 from external_identities x where x.issuer=p.issuer and x.subject=p.target_subject))
 and t.target_binding_hash=r.target_binding_hash and r.account_deadline=a.account_deadline and r.attempt_deadline=a.attempt_deadline
 and r.authenticated_at<=r.issued_at and r.issued_at<r.token_expires_at and r.created_at>=claim.started_at
 and c.completed_at>=claim.started_at and c.completed_at<claim.started_at+interval '4 seconds'
 and r.account_deadline>clock_timestamp() and r.attempt_deadline>clock_timestamp() and r.token_expires_at>clock_timestamp()
 and t.original_deadline>clock_timestamp() and t.original_deadline<=r.account_deadline and t.original_deadline<=r.attempt_deadline and t.original_deadline<=r.token_expires_at
 and (p.mode='LOCAL_PASSWORD' or (a.expected_subject=p.legacy_subject and a.legacy_issuer=p.issuer and a.legacy_subject=p.legacy_subject))) as managed_receipt_chain_consistent,
 case when p.mode='LOCAL_PASSWORD' then (select account_receipt_id is null from i)
 else exists(select 1 from i join legacy_migration_account_receipts r on r.receipt_id=i.account_receipt_id
 join legacy_migration_account_attempts a on a.id=r.attempt_id and a.receipt_id=r.receipt_id
 join legacy_migration_account_completions c on c.receipt_id=r.receipt_id and c.migration_id=i.id and c.attempt_id=r.attempt_id
 join legacy_migration_account_claims claim on claim.attempt_id=r.attempt_id
 where r.owner_id=p.owner and r.proof_mode=p.mode and r.issuer=p.issuer and r.subject=p.legacy_subject
 and r.client_id='armoney-migration-account' and a.client_id=r.client_id and a.issuer=r.issuer
 and a.redirect_uri='https://fixture.local:8443/migration/legacy/account-oidc/callback'
 and r.challenge_created_at=a.created_at and r.callback_database_start=claim.started_at
 and date_trunc('second',r.authenticated_at)>=date_trunc('second',r.challenge_created_at) and r.authenticated_at<=r.issued_at and r.issued_at<r.token_expires_at
 and c.completed_at>=claim.started_at and c.completed_at<claim.started_at+interval '4 seconds'
 and r.owner_generation=p.owner_generation and r.association_generation=p.association_generation
 and r.credential_generation=p.credential_generation and r.email_generation=p.email_generation+1
 and r.account_deadline>clock_timestamp() and r.attempt_deadline>clock_timestamp() and r.token_expires_at>clock_timestamp()
 and exists(select 1 from legacy_migration_managed_receipts m where m.migration_id=i.id and m.account_receipt_hash=r.receipt_hash)) end as account_receipt_chain_consistent,
 not exists(select 1 from legacy_migration_otp_stage_acks where migration_id=p.migration)
 and not exists(select 1 from legacy_migration_otp_receipts where migration_id=p.migration)
 and not exists(select 1 from legacy_migration_publication_decisions where migration_id=p.migration)
 and not exists(select 1 from legacy_migration_verified_final_acks where migration_id=p.migration)
 and not exists(select 1 from legacy_migration_final_commands where migration_id=p.migration)
 and not exists(select 1 from legacy_migration_final_completions where migration_id=p.migration) as no_final_or_otp_publication,
 not exists(select 1 from managed_registration_principals where identity_id in(select id from owners))
 and not exists(select 1 from managed_identity_sources where identity_id in(select id from owners))
 and not exists(select 1 from migration_banking_authorities where identity_id in(select id from owners) or migration_id=p.migration)
 and not exists(select 1 from migration_banking_authority_current where identity_id in(select id from owners) or migration_id=p.migration)
 and not exists(select 1 from migration_banking_grants where identity_id in(select id from owners))
 and not exists(select 1 from banking_eligibility_grants where identity_id in(select id from owners)) as no_promoted_authority,
 not exists(select 1 from managed_banking_operations where identity_id in(select id from owners) or (issuer=p.issuer and subject=p.target_subject))
 and not exists(select 1 from managed_banking_event_consumptions where identity_id in(select id from owners))
 and not exists(select 1 from managed_banking_sessions where identity_id in(select id from owners) or (issuer=p.issuer and subject=p.target_subject))
 and not exists(select 1 from managed_banking_completions c join managed_banking_operations o on o.id=c.operation_id where o.identity_id in(select id from owners)) as no_banking_writes,
 not exists(select 1 from sso_login_proofs) and not exists(select 1 from sso_code_confirmation_results) as no_c2_proofs
from p
"""

def _query(cur,sql,args,end,clock):
    if clock()>=end-1:raise Rejected()
    cur.execute(sql,args);rows=cur.fetchmany(3)
    if clock()>=end:raise Rejected()
    return rows

def _settings(cur,end,clock):
    rows=_query(cur,"select current_setting('transaction_read_only'),current_setting('statement_timeout'),current_setting('lock_timeout')",(),end,clock)
    if len(rows)!=1 or len(rows[0])!=3 or rows[0][0]!='on' or any(type(x) is not str or not re.fullmatch(r'(?:[1-9][0-9]{0,2}ms|1000ms|1s)',x) for x in rows[0][1:]):raise Rejected()

def capture_baseline(connection,private_scope,*,clock=time.monotonic):
    """PRIVATE return only. Root must store any serialized copy owner0600 and never in public receipts."""
    try:
        scope=_scope(private_scope);end=clock()+2
        with connection.cursor() as cur:
            _settings(cur,end,clock)
            args=tuple(scope[k] for k in ('owner_id','session_hash','mode','issuer','legacy_subject'))
            rows=_query(cur,BASELINE_SQL,args,end,clock)
        if len(rows)!=1 or len(rows[0])!=12:raise Rejected()
        r=rows[0]
        if any(type(v) is not bool or not v for v in r[8:]):raise Rejected()
        def stamp(v):
            if not isinstance(v,D.datetime) or v.tzinfo is None:raise Rejected()
            return v.astimezone(D.timezone.utc).isoformat(timespec='microseconds')
        result=dict(scope,version=1,session_row_id=r[0],created_at=stamp(r[1]),expires_at=stamp(r[2]),owner_generation=r[3],credential_generation=r[4],email_generation=r[5],association_generation=r[6],session_count=r[7])
        _baseline(result,scope)
        return result
    except Exception:raise Rejected() from None

def inspect_prefinal(connection,private_scope,migration_id,baseline,*,clock=time.monotonic):
    """BOOL-only persisted-storage receipt. Does not authorize, redeem or complete any migration.
    Global no-C2 is intentionally conservative for a fresh migration-only fixture; unrelated proof
    rows cause false, never a positive claim about an unmapped target inferred from absence of owner IDs.
    """
    try:
        scope=_scope(private_scope);_uuid(migration_id);b=_baseline(baseline,scope);end=clock()+2
        args=(scope['owner_id'],migration_id,scope['mode'],scope['issuer'],scope['legacy_subject'],scope['target_subject'],scope['target_client'],scope['session_hash'],
              b['session_row_id'],b['created_at'],b['expires_at'],b['owner_generation'],b['credential_generation'],b['email_generation'],b['association_generation'],b['session_count'])
        with connection.cursor() as cur:
            _settings(cur,end,clock);rows=_query(cur,PREFINAL_SQL,args,end,clock)
        if len(rows)!=1 or len(rows[0])!=len(RESULT_FIELDS) or any(type(v) is not bool for v in rows[0]):raise Rejected()
        result=dict(zip(RESULT_FIELDS,rows[0]));result['prefinal_storage_evidence']=all(result.values())
        return result
    except Exception:raise Rejected() from None
