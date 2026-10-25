"""Synchronize one running manifest's capture whitelist; never prints its token.

Run on the application node after the routing image is Ready. Preserves other
configured policies, and CAS-checks the audit image before changing deployment.
"""
import argparse,base64,json,subprocess

p=argparse.ArgumentParser();p.add_argument('--image',required=True);p.add_argument('--expected-image',required=True);p.add_argument('--application-version',default='0.4.0');p.add_argument('--apply',action='store_true');args=p.parse_args()

def command(argv,**options):return subprocess.run(argv,text=True,capture_output=True,check=True,**options).stdout

def get(kind,name,namespace):return json.loads(command(['kubectl','-n',namespace,'get',kind,name,'-o','json']))

read='''import os,json,hashlib,yaml,sys
from assistant import __version__
assert __version__==json.load(sys.stdin)['version']
m=yaml.safe_load(open('/app/agent.yaml'));assert m['metadata']['name']=='keel-assistant'
assert os.environ['KEEL_ENV']=='staging'
token=os.environ['KEEL_AUDIT_TOKEN'];assert token
print(json.dumps({'captureFields':m['spec']['audit']['captureFields'],'tokenSha256':hashlib.sha256(token.encode()).hexdigest()}))'''
policy=json.loads(command(['kubectl','-n','agents','exec','-i','deployment/keel-assistant','--','python3','-c',read],input=json.dumps({'version':args.application_version})))
name='keel-routing-audit-policy'
r=subprocess.run(['kubectl','-n','keel-system','get','secret',name,'-o','json'],text=True,capture_output=True)
if r.returncode:
    assert '(NotFound)' in r.stderr,r.stderr
    secret={'apiVersion':'v1','kind':'Secret','metadata':{'name':name,'namespace':'keel-system'},'type':'Opaque','data':{}}
    policies={}
else:
    secret=json.loads(r.stdout)
    raw=secret.get('data',{}).get('KEEL_AUDIT_CAPTURE_POLICIES')
    policies=json.loads(base64.b64decode(raw)) if raw else {}
policies.setdefault('keel-assistant',{})['staging']=policy
secret.setdefault('data',{})['KEEL_AUDIT_CAPTURE_POLICIES']=base64.b64encode(json.dumps(policies,separators=(',',':')).encode()).decode()
deployment=get('deployment','keel-audit','keel-system')
container=deployment['spec']['template']['spec']['containers'][0]
assert container['image']==args.expected_image,'Audit image changed; inspect before retrying'
refs=container.get('envFrom',[])
patch=[{'op':'test','path':'/spec/template/spec/containers/0/image','value':args.expected_image},
       {'op':'replace','path':'/spec/template/spec/containers/0/image','value':args.image}]
if not any(ref.get('secretRef',{}).get('name')==name for ref in refs):
    patch.append({'op':'add','path':'/spec/template/spec/containers/0/envFrom',
                  'value':refs+[{'secretRef':{'name':name}}]})
if args.apply:
    if r.returncode:
        command(['kubectl','create','-f','-'],input=json.dumps(secret))
    else:
        command(['kubectl','replace','-f','-'],input=json.dumps(secret))
    command(['kubectl','-n','keel-system','patch','deployment','keel-audit','--type=json','-p',json.dumps(patch)])
print(json.dumps({'applied':args.apply,'agent':'keel-assistant','env':'staging','captureFields':policy['captureFields'],
                  'oldImage':args.expected_image,'newImage':args.image,'otherPoliciesPreserved':True}))
