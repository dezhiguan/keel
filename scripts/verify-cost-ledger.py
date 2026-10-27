#!/usr/bin/env python3
"""Read-only billing checks on the application node after deployment; never print credentials."""
import base64
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
import subprocess
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo


ZONE = ZoneInfo('Asia/Shanghai')
EPSILON = Decimal('0.00001')


def cluster(*args):
    return subprocess.check_output(['kubectl', '-n', 'keel-system', *args])


def read(url, headers=None):
    with urlopen(Request(url, headers=headers or {}), timeout=90) as response:
        return json.load(response, parse_float=Decimal)


def money(value):
    assert value is not None, 'Billing metric is missing'
    return Decimal(str(value))


def verify(server, gateway, headers):
    rows = {}
    cursor = None
    seen = set()
    for _ in range(500):
        query = {'limit': 100}
        if cursor:
            query['cursor'] = cursor
        body = read(gateway + '/admin/v1/spend?' + urlencode(query), headers)
        assert isinstance(body.get('data'), list) and 'nextCursor' in body, 'Incomplete ledger response'
        for row in body['data']:
            assert row.get('attemptId'), 'Ledger attempt ID is missing'
            rows.setdefault(row['attemptId'], row)
        cursor = body.get('nextCursor')
        if not cursor:
            break
        assert cursor not in seen, 'Ledger cursor repeated'
        seen.add(cursor)
    else:
        raise AssertionError('Ledger pagination limit reached')

    now = datetime.now(timezone.utc)
    today = now.astimezone(ZONE).replace(hour=0, minute=0, second=0, microsecond=0)
    amounts = defaultdict(Decimal)
    daily = defaultdict(Decimal)
    pending = defaultdict(int)
    daily_pending = defaultdict(int)
    dated = defaultdict(Decimal)
    settled = []
    for row in rows.values():
        at = datetime.fromisoformat(row['ts'].replace('Z', '+00:00'))
        agent = row['alias'].rsplit('-', 1)[0]
        if row['status'] == 'PENDING':
            pending[agent] += 1
            if at >= today:
                daily_pending[agent] += 1
        if row['status'] != 'SETTLED' or row.get('costCny') is None:
            continue
        cost = money(row['costCny'])
        amounts[agent] += cost
        if at >= today:
            daily[agent] += cost
        if agent == 'keel-assistant':
            dated[str(at.astimezone(ZONE).date())] += cost
        settled.append((at, cost))

    for window in ['today', '24h', '7d', '30d', 'all']:
        data = read(server + '/api/v1/insight/overview?' + urlencode({'env': 'all', 'range': window}))['data']
        assert data.get('dataStatus', {}).get('updatedAt'), 'Overview billing snapshot is not ready'
        assert abs(money(data['cumulative']['costCny']) - sum(amounts.values())) <= EPSILON, 'Cumulative cost differs from raw ledger'
        since = today if window == 'today' else now - timedelta(hours=24) if window == '24h' else now - timedelta(days=int(window[:-1])) if window != 'all' else datetime(2020, 1, 1, tzinfo=timezone.utc)
        expected = sum((cost for at, cost in settled if at >= since), Decimal(0))
        partial = any(row.get('routeVersion') == 'daily-recovery' and
                      datetime.fromisoformat(row['ts'].replace('Z', '+00:00')) < since <
                      datetime.fromisoformat(row['ts'].replace('Z', '+00:00')) + timedelta(days=1)
                      for row in rows.values())
        if partial:
            assert data['kpi']['modelCostCny'] is None, 'Daily-only recovery must not pretend to have hourly precision'
        else:
            assert abs(money(data['kpi']['modelCostCny']) - expected) <= EPSILON, 'Window cost differs from raw ledger: ' + window
        assert data['kpi']['pendingModelBills'] == sum(1 for row in rows.values() if row['status'] == 'PENDING' and datetime.fromisoformat(row['ts'].replace('Z', '+00:00')) >= since), 'Window pending count differs: ' + window
        for agent in data['agents']:
            name = agent['name']
            assert abs(money(agent['costCny']) - daily[name]) <= EPSILON, 'Daily agent cost differs: ' + name
            assert abs(money(agent['totalCostCny']) - amounts[name]) <= EPSILON, 'Lifetime agent cost differs: ' + name
            assert agent['pendingBillCount'] == daily_pending[name], 'Daily pending count differs: ' + name
            assert agent['totalPendingBillCount'] == pending[name], 'Lifetime pending count differs: ' + name

    print(json.dumps({'result': 'PASS', 'ledgerRecords': len(rows), 'dailyRecoveryRecords': sum(1 for row in rows.values() if row.get('routeVersion') == 'daily-recovery'), 'totalCostCny': str(sum(amounts.values())),
                      'assistantDailyCostCny': str(daily['keel-assistant']),
                      'assistantTotalCostCny': str(amounts['keel-assistant']),
                      'assistantCostByDay': {day: str(value) for day, value in sorted(dated.items())}}, ensure_ascii=False))


def main():
    server_ip = cluster('get', 'svc', 'keel-server', '-o', 'jsonpath={.spec.clusterIP}').decode()
    gateway_ip = cluster('get', 'svc', 'keel-llm', '-o', 'jsonpath={.spec.clusterIP}').decode()
    secret = json.loads(cluster('get', 'secret', 'keel-llm', '-o', 'json'))
    assert all(secret['data'].get(key) for key in ['jdbc-url', 'jdbc-username', 'jdbc-password']), 'Persistent gateway database is not configured'
    headers = {'Authorization': 'Bearer ' + base64.b64decode(secret['data']['admin-key']).decode()}
    del secret
    for window in ['today', '24h', '7d', '30d', 'all']:
        read('http://' + server_ip + ':8080/api/v1/insight/overview?' + urlencode({'env': 'all', 'range': window}))
    # Background snapshots need time to refresh newly requested windows. Active traffic can
    # also change the ledger between reads, so retry the entire read-only comparison.
    for attempt in range(36):
        try:
            verify('http://' + server_ip + ':8080', 'http://' + gateway_ip + ':8088', headers)
            return
        except (AssertionError, KeyError) as error:
            if attempt == 35:
                raise
            print('Waiting for consistent billing snapshots:', str(error), flush=True)
            time.sleep(5)


if __name__ == '__main__':
    main()
