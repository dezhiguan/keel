"""Prepare the existing runtime Secret for logical routing and authenticated skills.

Run on the application node. Existing budgets, quotas and vendor configuration are preserved.
"""
import argparse
import base64
import json
import subprocess


def kube(namespace, *args, body=None):
    return subprocess.check_output(['kubectl','-n',namespace,*args],input=body)


def secret(namespace,name):
    value=json.loads(kube(namespace,'get','secret',name,'-o','json'))
    return {key:base64.b64decode(raw).decode() for key,raw in value['data'].items()}


REMOTE='''import json,sys,httpx
request=json.load(sys.stdin)
with httpx.Client(base_url='http://keel-llm.keel-system.svc.cluster.local:8088',headers={'Authorization':'Bearer '+request['admin']},timeout=30) as client:
    response=client.request(request['method'],request['path'],json=request.get('body'))
    response.raise_for_status()
    print(json.dumps(response.json()))
'''


def logical_key_request(current,routes):
    if current.get('alias')!='meta-agent-dev' or current.get('blocked') is not False:
        raise ValueError('只允许调整当前有效的 meta-agent-dev Key')
    rows=[row for row in routes['data'] if row['logical']=='chat-default']
    if not rows: raise ValueError('网关未发布 chat-default')
    targets=sorted({row[field] for row in rows for field in ('primary','secondary')})
    if set(targets).issubset(current['models']): return None
    return {'alias':current['alias'],'models':sorted(set(current['models'])|set(targets)),
            **{key:current[key] for key in ('fallback','dailyBudgetCny','allowFallback')},
            **{key:current.get(key) for key in ('rpm','tpm','concurrency')}}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    admin=secret('keel-system','keel-llm')['admin-key']
    def api(method,path,body=None):
        payload={'admin':admin,'method':method,'path':path,'body':body}
        return json.loads(kube('keel-system','exec','-i','deployment/git-ci','--','python','-c',REMOTE,body=json.dumps(payload).encode()))
    current=api('GET','/admin/v1/keys/meta-agent-dev')
    request=logical_key_request(current,api('GET','/admin/v1/logical-routes'))
    identity=secret('agents','keel-meta-agent')
    if not identity.get('KEEL_CLIENT_PRIVATE_KEY'): raise RuntimeError('缺少已登记的元智能体服务身份')
    print(json.dumps({'alias':current['alias'],'logical':'chat-default','keyUpdateNeeded':request is not None,
                      'dailyBudgetCny':current['dailyBudgetCny'],'apply':args.apply}))
    if not args.apply: return
    patch={'KEEL_SERVER_URL':'http://keel-server.keel-system.svc.cluster.local:8080',
           'KEEL_SERVICE_AUDIENCE':'keel-server','KEEL_CLIENT_PRIVATE_KEY':identity['KEEL_CLIENT_PRIVATE_KEY']}
    if request:
        patch['KEEL_LLM_KEY']=api('POST','/admin/v1/keys',request)['key']
    kube('keel-system','patch','secret','keel-meta-agent','--type=merge','--patch-file=/dev/stdin',
         body=json.dumps({'stringData':patch}).encode())
    print('Runtime Secret prepared; deploy the candidate to load it')


if __name__=='__main__': main()
