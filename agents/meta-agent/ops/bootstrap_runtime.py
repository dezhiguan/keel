"""Prepare the existing runtime Secret for logical routing and authenticated skills.

Run on the application node. Existing budgets, quotas and vendor configuration are preserved.
"""
import argparse
import base64
import json
import subprocess
from pathlib import Path


def kube(namespace, *args, body=None):
    return subprocess.check_output(['kubectl','-n',namespace,*args],input=body)


def secret(namespace,name):
    value=json.loads(kube(namespace,'get','secret',name,'-o','json'))
    return {key:base64.b64decode(raw).decode() for key,raw in value['data'].items()}


REMOTE='''import json,sys,httpx
request=json.load(sys.stdin)
with httpx.Client(base_url='http://keel-llm.keel-system.svc.cluster.local:8088',headers={'Authorization':'Bearer '+request['admin']},timeout=30) as client:
    response=client.request(request['method'],request['path'],json=request.get('body'))
    if (request.get('allowMissing') and response.status_code == 404
            and response.json().get('code') == 'SERVER_NOT_FOUND'):
        print('null')
    else:
        response.raise_for_status()
        print(json.dumps(response.json()))
'''


def logical_key_request(current,routes):
    if current.get('alias')!='meta-agent-dev' or current.get('blocked') is not False:
        raise ValueError('只允许调整当前有效的 meta-agent-dev Key')
    rows=[row for row in routes['data'] if row['logical']=='chat-default']
    if not rows: raise ValueError('网关未发布 chat-default')
    # The current gateway authorizes the logical name and expands its live targets.
    targets=sorted({'chat-default'}|{row[field] for row in rows for field in ('primary','secondary')})
    if set(targets).issubset(current['models']): return None
    return {'alias':current['alias'],'models':sorted(set(current['models'])|set(targets)),
            **{key:current[key] for key in ('fallback','dailyBudgetCny','allowFallback')},
            **{key:current.get(key) for key in ('rpm','tpm','concurrency')}}


def missing_key_request(policy, routes):
    if policy.get('models') != ['chat-default']:
        raise ValueError('恢复策略必须显式授权 chat-default')
    # Validate the same ownership and live route constraints before issuing a key.
    current = {**policy, 'blocked': False, 'models': []}
    return logical_key_request(current, routes)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--missing-key-policy',type=Path)
    args=parser.parse_args()
    admin=secret('keel-system','keel-llm')['admin-key']
    def api(method,path,body=None,allow_missing=False):
        payload={'admin':admin,'method':method,'path':path,'body':body,'allowMissing':allow_missing}
        return json.loads(kube('keel-system','exec','-i','deployment/git-ci','--','python','-c',REMOTE,body=json.dumps(payload).encode()))
    current=api('GET','/admin/v1/keys/meta-agent-dev',allow_missing=True)
    routes=api('GET','/admin/v1/logical-routes')
    if current is None:
        if args.missing_key_policy is None:
            raise RuntimeError('专用运行 Key 缺失；恢复须显式提供 --missing-key-policy')
        policy=json.loads(args.missing_key_policy.read_text())
        request=missing_key_request(policy,routes)
    else:
        request=logical_key_request(current,routes)
    secret('keel-system','keel-meta-agent')
    identity=secret('agents','keel-meta-agent')
    if not identity.get('KEEL_CLIENT_PRIVATE_KEY'): raise RuntimeError('缺少已登记的元智能体服务身份')
    selected=current if current is not None else request
    print(json.dumps({'alias':selected['alias'],'logical':'chat-default','keyUpdateNeeded':request is not None,
                      'keyMissing':current is None,'dailyBudgetCny':selected['dailyBudgetCny'],'apply':args.apply}))
    if not args.apply: return
    patch={'KEEL_SERVER_URL':'http://keel-server.keel-system.svc.cluster.local:8080',
           'KEEL_SERVICE_AUDIENCE':'keel-server','KEEL_CLIENT_PRIVATE_KEY':identity['KEEL_CLIENT_PRIVATE_KEY']}
    if request:
        patch['KEEL_LLM_KEY']=api('POST','/admin/v1/keys',request)['key']
    kube('keel-system','patch','secret','keel-meta-agent','--type=merge','--patch-file=/dev/stdin',
         body=json.dumps({'stringData':patch}).encode())
    print('Runtime Secret prepared; deploy the candidate to load it')


if __name__=='__main__': main()
