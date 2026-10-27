#!/usr/bin/env python3
"""Run on the application node. Recover verified daily money without inventing call details.

--prepare backs up Redis/day summaries and configures the existing gateway Secret.
Run without flags after the gateway has applied its Flyway migrations to import evidence.
Credentials stay in process memory and are never printed or written to the evidence files.
"""
import base64
from collections import defaultdict
from datetime import date, datetime
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit, urlunsplit
from zoneinfo import ZoneInfo

import psycopg
import redis

ROOT = Path('/root/keel-cost-recovery-20261027')
ZONE = ZoneInfo('Asia/Shanghai')
os.umask(0o077)
ROOT.mkdir(mode=0o700, exist_ok=True)


def secret(name):
    body = json.loads(subprocess.check_output(['kubectl', '-n', 'keel-system', 'get', 'secret', name, '-o', 'json']))
    return {key: base64.b64decode(value).decode() for key, value in body['data'].items()}


def connect(config, database=None):
    url = urlsplit(config['KEEL_DB_URL'].removeprefix('jdbc:'))
    return psycopg.connect(host=url.hostname, port=url.port or 5432, dbname=database or url.path.lstrip('/'),
                           user=config['KEEL_DB_USERNAME'], password=config['KEEL_DB_PASSWORD'], connect_timeout=15)


def day_of(row):
    return datetime.fromisoformat(row['ts'].replace('Z', '+00:00')).astimezone(ZONE).date()


def main():
    config = secret('keel-server')
    llm = secret('keel-llm')
    cache = redis.Redis.from_url(llm['redis-url'], decode_responses=True, socket_timeout=30)
    with connect(config) as db:
        usage_file = ROOT / 'usage-full-before.json'
        if not usage_file.exists():
            observed = db.execute("SELECT day,env,agent,calls,tokens FROM usage_daily").fetchall()
            usage_file.write_text(json.dumps([{'day':str(day),'env':env,'agent':agent,'calls':calls,'tokens':tokens}
                                               for day,env,agent,calls,tokens in observed]))
        daily_file = ROOT / 'usage-daily-before.json'
        if not daily_file.exists():
            days = db.execute("SELECT day,env,cost_cny FROM usage_daily WHERE agent='*'").fetchall()
            daily_file.write_text(json.dumps([{'day': str(day), 'env': env, 'costCny': str(cost)}
                                             for day, env, cost in days if cost is not None]))
    backup_file = ROOT / 'redis-before.json'
    if not backup_file.exists():
        backup = {}
        for pattern in ['keel-llm:budget:v2:*', 'keel-llm:spend', 'keel-llm:pending-spend']:
            for key in cache.scan_iter(match=pattern, count=500):
                kind = cache.type(key)
                value = cache.hgetall(key) if kind == 'hash' else cache.lrange(key, 0, -1) if kind == 'list' else cache.zrange(key, 0, -1, withscores=True) if kind == 'zset' else cache.get(key)
                backup[key] = {'type': kind, 'ttl': cache.ttl(key), 'value': value}
        backup_file.write_text(json.dumps(backup))

    if '--prepare' in sys.argv:
        with connect(config, 'keel_llm'):
            pass  # Database existence/permissions must be verified before changing configuration.
        old = urlsplit(config['KEEL_DB_URL'].removeprefix('jdbc:'))
        url = 'jdbc:' + urlunsplit(old._replace(path='/keel_llm'))
        values = {'jdbc-url': url, 'jdbc-username': config['KEEL_DB_USERNAME'], 'jdbc-password': config['KEEL_DB_PASSWORD']}
        patch = {'data': {key: base64.b64encode(value.encode()).decode() for key, value in values.items()}}
        subprocess.run(['kubectl', '-n', 'keel-system', 'patch', 'secret', 'keel-llm', '--type=merge', '--patch-file=/dev/stdin'],
                       input=json.dumps(patch).encode(), check=True, stdout=subprocess.DEVNULL)
        print('Private evidence backups saved; gateway JDBC configuration prepared.')
        return

    # Restore known usage facts independently of recovered money; recovery rows have no usage details.
    with connect(config) as db:
        with db.cursor() as cursor:
            cursor.executemany('''UPDATE usage_daily SET calls=GREATEST(calls,%s),tokens=GREATEST(tokens,%s)
                                  WHERE day=%s AND env=%s AND agent=%s AND closed''',
                               [(row['calls'],row['tokens'],row['day'],row['env'],row['agent'])
                                for row in json.loads(usage_file.read_text())])

    backup = json.loads(backup_file.read_text())
    evidence = hashlib.sha256(backup_file.read_bytes()).hexdigest()
    daily_evidence = hashlib.sha256(daily_file.read_bytes()).hexdigest()
    raw = list(cache.lrange('keel-llm:spend', 0, -1)) + list(cache.hvals('keel-llm:pending-spend'))
    for key in ['keel-llm:spend', 'keel-llm:pending-spend']:
        saved = backup.get(key, {}).get('value', {})
        raw.extend(saved.values() if isinstance(saved, dict) else saved)
    rows = {}
    for item in raw:
        row = json.loads(item)
        assert row.get('attemptId'), 'Recovery refuses a bill without an attempt ID'
        rows.setdefault(row['attemptId'], row)

    floors = {}
    for key, value in backup.items():
        if value['type'] != 'hash' or key.endswith((':requests', ':expiry')) or not key.startswith('keel-llm:budget:v2:'):
            continue
        suffix = key.removeprefix('keel-llm:budget:v2:')
        alias, day = suffix.rsplit(':', 1)
        if 'settled' in value['value']:
            floors[(date.fromisoformat(day), alias)] = (Decimal(value['value']['settled']) / 1000000, 'redis-settled', evidence)

    with connect(config, 'keel_llm') as db:
        assert db.execute("SELECT to_regclass('llm_daily_recovery')").fetchone()[0], 'Deploy V2 before importing recovery evidence'
        sql = '''INSERT INTO llm_spend(attempt_id,request_id,alias,model,status,input_tokens,output_tokens,cache_tokens,
                  cost_cny,reserved_cny,price_version,route_version,trace_id,latency_ms,timed_out,created_at)
                 VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(attempt_id) DO NOTHING'''
        with db.cursor() as cursor:
            cursor.executemany(sql, [(row['attemptId'], row['requestId'], row['alias'], row['model'], row['status'],
                                     row.get('inputTokens'), row.get('outputTokens'), row.get('cacheTokens'), row.get('costCny'),
                                     row.get('reservedCny', 0), row.get('priceVersion', ''), row.get('routeVersion', ''),
                                     row.get('traceId', ''), row.get('latencyMs', 0), row.get('timedOut', False), row['ts'])
                                    for row in rows.values()])
        known = {(day, alias): amount for day, alias, amount in db.execute('''SELECT
                 (created_at AT TIME ZONE 'Asia/Shanghai')::date,alias,SUM(cost_cny)
                 FROM llm_spend WHERE status='SETTLED' GROUP BY 1,2''').fetchall()}
        for key, (amount, _, _) in floors.items():
            known[key] = max(known.get(key, Decimal(0)), amount)
        # Preserve genuine old day summaries without assigning unknown money to a real agent.
        daily = json.loads(daily_file.read_text())
        for saved in sorted(daily, key=lambda item: item['env'] == 'all'):
            day = date.fromisoformat(saved['day']); env = saved['env']
            if day >= datetime.now(ZONE).date():
                continue
            actual = sum((amount for (known_day, alias), amount in known.items()
                          if known_day == day and (env == 'all' or alias.endswith('-' + env))), Decimal(0))
            gap = max(Decimal(0), Decimal(saved['costCny']) - actual)
            alias = 'historical-unattributed' if env == 'all' else 'historical-' + env
            floors[(day, alias)] = (gap, 'usage-daily-confirmed', daily_evidence)
            known[(day, alias)] = gap
        with db.cursor() as cursor:
            cursor.executemany('''INSERT INTO llm_daily_recovery(day,alias,settled_cny,source,evidence_sha256)
                    VALUES (%s,%s,%s,%s,%s) ON CONFLICT(day,alias) DO UPDATE SET
                    settled_cny=EXCLUDED.settled_cny,source=EXCLUDED.source,evidence_sha256=EXCLUDED.evidence_sha256''',
                               [(day, alias, amount, source, digest) for (day, alias), (amount, source, digest) in floors.items()])
        total = db.execute("SELECT SUM(cost_cny) FROM llm_spend_history WHERE status='SETTLED'").fetchone()[0]
        assistant = db.execute("SELECT SUM(cost_cny) FROM llm_spend_history WHERE status='SETTLED' AND alias LIKE 'keel-assistant-%'").fetchone()[0]
        print(json.dumps({'result': 'recovered', 'rawRowsImportedOrAlreadyPresent': len(rows),
                          'verifiedDailyEvidenceRows': len(floors), 'confirmedTotalCny': str(total),
                          'assistantConfirmedTotalCny': str(assistant),
                          'detailLimitation': 'Daily recovery rows have no per-call/token/hourly details.'}))


if __name__ == '__main__':
    main()
