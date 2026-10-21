"""Fixture-only subprocess wrapper. Root enforces a hard 3s capture deadline and kill/reap.
Private stdin/snapshot data must never be logged. Offline psycopg is supplied by the owned image.
"""
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import sys

_spec=importlib.util.spec_from_file_location('_armoney_loss_evidence',Path(__file__).with_name('production-provider-response-loss-evidence.py'))
E=importlib.util.module_from_spec(_spec)
sys.modules[_spec.name]=E
_spec.loader.exec_module(E)
LIMIT=16384
FILES=('baseline.json','drop.json','fresh.json')
FIELDS=frozenset(('version','scope_hash','counts','events','original_counts','fingerprint','selector_hash','original_event','retention_current','bank_events'))

class Rejected(ValueError):
    def __init__(self):super().__init__('response_loss_snapshot_rejected')

def _object(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise Rejected()
        result[key]=value
    return result

def parse(raw):
    try:
        if type(raw) is not bytes or len(raw)>LIMIT:raise Rejected()
        return json.loads(raw.decode('utf-8'),object_pairs_hook=_object,parse_constant=lambda _:(_ for _ in ()).throw(Rejected()))
    except Exception:raise Rejected() from None

def _hex(value):
    if type(value) is not str or not re.fullmatch('[0-9a-f]{64}',value):raise Rejected()
    return bytes.fromhex(value)

def _ints(value,length,maximum):
    if type(value) is not list or len(value)!=length or any(type(n) is not int or not 0<=n<=maximum for n in value):raise Rejected()
    return tuple(value)

def _events(value):
    if type(value) is not list or len(value)>E.MAX_EVENTS:raise Rejected()
    result=tuple(E._uuid(x) for x in value)
    if tuple(sorted(set(result)))!=result:raise Rejected()
    return result

def encode(snapshot):
    if type(snapshot) is not E.Snapshot:raise Rejected()
    value={'version':1,'scope_hash':snapshot._scope_hash.hex(),'counts':list(snapshot._counts),'events':list(snapshot._events),
           'original_counts':None if snapshot._original_counts is None else list(snapshot._original_counts),
           'fingerprint':snapshot._fingerprint,'selector_hash':None if snapshot._selector_hash is None else snapshot._selector_hash.hex(),
           'original_event':snapshot._original_event,'retention_current':snapshot._retention_current,
           'bank_events':[list(x) for x in snapshot._bank_events]}
    raw=json.dumps(value,separators=(',',':'),sort_keys=True,allow_nan=False).encode('utf-8')
    decode(raw) # Validate the complete codec before persistence.
    if len(raw)>LIMIT:raise Rejected()
    return raw

def decode(raw):
    try:
        value=parse(raw)
        if type(value) is not dict or set(value)!=FIELDS or type(value['version']) is not int or value['version']!=1:raise Rejected()
        scope=_hex(value['scope_hash']);counts=_ints(value['counts'],4,E.MAX_COUNT);events=_events(value['events'])
        rows=value['bank_events']
        if type(rows) is not list or len(rows)>E.MAX_EVENTS or any(type(r) is not list or len(r)!=2 or type(r[1]) is not bool for r in rows):raise Rejected()
        bank=tuple((E._uuid(r[0]),r[1]) for r in rows)
        if tuple(sorted(bank))!=bank or len({r[0] for r in bank})!=len(bank) or len(bank)!=counts[1] or not {r[0] for r in bank}.issubset(set(events)):raise Rejected()
        if type(value['retention_current']) is not bool:raise Rejected()
        original=value['original_counts'];fingerprint=value['fingerprint'];selector=value['selector_hash'];event=value['original_event'];retained=value['retention_current']
        if original is None:
            if any(x is not None for x in (fingerprint,selector,event)) or retained:raise Rejected()
        else:
            original=_ints(original,6,1)
            if original[:3]!=(1,1,1) or original[3:] not in ((0,0,0),(1,1,1)) or not retained:raise Rejected()
            _hex(fingerprint);selector=_hex(selector);E._uuid(event)
            if original[3]==1 and event not in events:raise Rejected()
        return E.Snapshot(scope,counts,events,original,fingerprint,selector,event,retained,bank)
    except Exception:raise Rejected() from None

def _directory(path):
    if os.name!='posix' or type(path) is not str or not Path(path).is_absolute() or '..' in Path(path).parts or not hasattr(os,'O_NOFOLLOW'):raise Rejected()
    fd=os.open('/',os.O_RDONLY|os.O_DIRECTORY)
    try:
        for part in Path(path).parts[1:]:
            next_fd=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
            os.close(fd);fd=next_fd
        info=os.fstat(fd)
        if info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o700:raise Rejected()
        return fd
    except Exception:
        os.close(fd);raise Rejected() from None

def _read(fd,name):
    handle=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=fd)
    try:
        info=os.fstat(handle)
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o600 or info.st_nlink!=1 or info.st_size>LIMIT:raise Rejected()
        with os.fdopen(handle,'rb',closefd=False) as stream:raw=stream.read(LIMIT+1)
        return decode(raw)
    finally:os.close(handle)

def _write(fd,name,snapshot):
    raw=encode(snapshot)
    handle=os.open(name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=fd)
    try:
        info=os.fstat(handle)
        if info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o600 or info.st_nlink!=1:raise Rejected()
        with os.fdopen(handle,'wb',closefd=False) as stream:stream.write(raw);stream.flush();os.fsync(handle)
    finally:os.close(handle)

def connect(config):
    # No DSN, service file, operator.env, password file or DNS lookup is accepted.
    if type(config) is not dict or set(config)!={'hostaddr','port','database','user','password'}:raise Rejected()
    if type(config['hostaddr']) is not str or type(config['port']) is not int or config['port']!=5432 or config['database']!='bank' or config['user']!='bank':raise Rejected()
    try:address=ipaddress.IPv4Address(config['hostaddr'])
    except Exception:raise Rejected() from None
    if str(address)!=config['hostaddr'] or not any(address in ipaddress.IPv4Network(n) for n in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')):raise Rejected()
    if type(config['password']) is not str or not re.fullmatch('[A-Za-z0-9_-]{43}',config['password']):raise Rejected()
    import psycopg
    return psycopg.connect(hostaddr=str(address),host=str(address),port=5432,dbname='bank',user='bank',password=config['password'],
        connect_timeout=1,sslmode='disable',gssencmode='disable',passfile='/dev/null',application_name='armoney-loss-readonly',
        options='-c default_transaction_read_only=on -c statement_timeout=1000 -c lock_timeout=1000 -c idle_in_transaction_session_timeout=1000')

def run(document,*,connector=connect):
    fd=None
    try:
        if type(document) is not dict or set(document)!={'version','action','directory','db','scope','original'} or type(document['version']) is not int or document['version']!=1:raise Rejected()
        action=document['action'];scope=E._scope(document['scope']);original=document['original']
        if type(action) is not str or action not in ('baseline','drop','fresh','compare'):raise Rejected()
        if action=='baseline':
            if original is not None:raise Rejected()
        elif E._selector(original)!=scope:raise Rejected()
        if action=='compare' and document['db'] is not None:raise Rejected()
        fd=_directory(document['directory'])
        expected={'baseline':set(),'drop':{FILES[0]},'fresh':set(FILES[:2]),'compare':set(FILES)}[action]
        if set(os.listdir(fd))!=expected:raise Rejected()
        snapshots=[_read(fd,name) for name in FILES if name in expected]
        expected_scope=E._digest(list(scope))
        if any(s._scope_hash!=expected_scope for s in snapshots):raise Rejected()
        if snapshots and snapshots[0]._original_counts is not None:raise Rejected()
        if original is not None:
            expected_selector=E._digest([str(original[k]) for k in sorted(E.SELECTOR_KEYS)])
            expected_counts=(0,0,0) if original['operation']=='STAGE' else (1,1,1)
            if any(s._selector_hash!=expected_selector or s._original_event!=original['event_id'] or s._original_counts[3:]!=expected_counts for s in snapshots[1:]):raise Rejected()
        if action=='compare':return E.compare(*snapshots)
        with connector(document['db']) as connection:
            snapshot=E.capture(connection,document['scope'],original)
        _write(fd,FILES[('baseline','drop','fresh').index(action)],snapshot)
        return E.public_receipt(snapshot)
    except Exception:raise Rejected() from None
    finally:
        if fd is not None:os.close(fd)

def main():
    # This disposable child must not inherit libpq service/password/options files from operator environment.
    os.environ.clear()
    try:
        result=run(parse(sys.stdin.buffer.read(LIMIT+1)))
        if not isinstance(result,dict) or any(type(v) not in (bool,int) for v in result.values()):raise Rejected()
        print(json.dumps(result,separators=(',',':'),sort_keys=True))
        return 0
    except Exception:
        print('{"verified":false,"check":"snapshot_wrapper"}')
        return 1

if __name__=='__main__':sys.exit(main())
