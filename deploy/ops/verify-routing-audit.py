"""Check the deployed receiver's authentication and payload filtering on each pod."""
import argparse,json,subprocess,pathlib
parser=argparse.ArgumentParser()
parser.add_argument('--expected-image',required=True)
parser.add_argument('--output',required=True)
args=parser.parse_args()
pods=json.loads(subprocess.check_output(['kubectl','-n','keel-system','get','pods','-l','app=keel-audit','-o','json']))['items']
assert all(p['spec']['containers'][0]['image']==args.expected_image for p in pods if not p['metadata'].get('deletionTimestamp'))
pods=[p for p in pods if not p['metadata'].get('deletionTimestamp') and all(c.get('ready') for c in p['status'].get('containerStatuses',[]))]
assert len(pods)==2
code='''import os,json,sys,httpx,uuid,datetime
x=json.load(sys.stdin);trace=uuid.uuid4().hex
body={'event_id':uuid.uuid4().hex,'ts':datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'),'agent':'keel-assistant','env':'staging','risk':'low','action':'invoke','decision':'pending','resource':'routing-whitelist-acceptance','trace_id':trace,'payload':{'route_mode':'local_fast','operation':'get','must_drop':'TEST_FIXTURE_NOT_PRIVATE'}}
with httpx.Client(base_url=x['endpoint'],timeout=15) as c:
 denied=c.post('/api/v1/audit/events',json=body,headers={'Authorization':'Bearer forged-fixture'})
 allowed=c.post('/api/v1/audit/events',json=body,headers={'Authorization':'Bearer '+os.environ['KEEL_AUDIT_TOKEN']})
 query=c.get('/api/v1/audit/events',params={'agent':'keel-assistant','env':'staging','traceId':trace})
 query.raise_for_status();rows=query.json()['data']['items']
 checks={'denied_status':denied.status_code==401,'allowed_status':allowed.status_code==204,'one_event':len(rows)==1,'whitelist':len(rows)==1 and rows[0].get('payload')=={'route_mode':'local_fast','operation':'get'}}
 print(json.dumps({'pod':x['pod'],'trace':trace,'checks':checks,'passed':all(checks.values())}))'''
rows=[]
for pod in pods:
 inp={'pod':pod['metadata']['name'],'endpoint':'http://'+pod['status']['podIP']+':8080'}
 result=subprocess.run(['kubectl','-n','agents','exec','-i','deployment/keel-assistant','--','python3','-c',code],input=json.dumps(inp),text=True,capture_output=True,timeout=60)
 if result.returncode:raise RuntimeError(result.stderr[-600:])
 row=json.loads(result.stdout);rows.append(row);print(json.dumps(row),flush=True)
pathlib.Path(args.output).write_text(json.dumps({'scope':'audit token/whitelist receiver integration; persistence not asserted','expectedImage':args.expected_image,'results':rows},indent=2))
assert all(row['passed'] for row in rows)
