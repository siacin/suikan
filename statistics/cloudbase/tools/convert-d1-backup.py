"""Convert a local Wrangler D1 SQL export to a private, validated JSON snapshot.

Only the five statistics tables are exported. The source SQL is loaded into an
in-memory SQLite database with file attachment and extension loading disabled.
"""
import argparse
import json
import sqlite3
from pathlib import Path

COUNTERS=[kind+'_'+outcome for kind in ('extract','summary') for outcome in ('started','succeeded','failed','cancelled')]
TABLES={'installations':['id','first_day','last_day','app_version'],'active':['day','id'],
        'installation_access':['id','enabled','reason'],'operation_daily':['day','id']+COUNTERS,'operation_totals':['id']+COUNTERS}

def convert(source):
    if source.stat().st_size>64*1024*1024:
        raise ValueError('Backup too large')
    with sqlite3.connect(':memory:') as db:
        def authorize(action,a,b,*_):
            if action in (sqlite3.SQLITE_ATTACH,sqlite3.SQLITE_DETACH):
                return sqlite3.SQLITE_DENY
            if action==sqlite3.SQLITE_FUNCTION and (b or '').lower()=='load_extension':
                return sqlite3.SQLITE_DENY
            return sqlite3.SQLITE_OK
        db.set_authorizer(authorize)
        db.executescript(source.read_text(encoding='utf-8-sig'))
        present={r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {'installations','active','installation_access'}.issubset(present):
            raise ValueError('Missing statistics tables')
        tables={}
        for table,fields in TABLES.items():
            if table not in present:
                tables[table]=[]
                continue
            tables[table]=[dict(zip(fields,row)) for row in db.execute('SELECT '+','.join(fields)+' FROM '+table)]
    return {'schema':1,'tables':tables}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('sql_backup',type=Path)
    p.add_argument('json_snapshot',type=Path)
    args=p.parse_args()
    value=convert(args.sql_backup)
    args.json_snapshot.parent.mkdir(parents=True,exist_ok=True)
    with args.json_snapshot.open('x',encoding='utf-8') as f:
        json.dump(value,f,ensure_ascii=False,separators=(',',':'))
    print('Private snapshot created; installations:',len(value['tables']['installations']))

if __name__=='__main__':
    main()
