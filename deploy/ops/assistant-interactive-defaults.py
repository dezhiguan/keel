"""Set only the personal assistant's interactive model defaults, preserving other fields.

Operator script, run on the deployment host. Default is a read-only plan; --apply
uses the Secret resourceVersion as an optimistic lock. No credentials are printed.
"""
import argparse,base64,copy,json,subprocess

def desired(existing):
    result=copy.deepcopy(existing)
    models=result.setdefault('keel-assistant-staging',{})
    models.setdefault('qwen3.8-flash',{})['interactive']={'enable_thinking':False}
    models.setdefault('deepseek-flash',{})['interactive']={'thinking':{'type':'disabled'}}
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    secret=json.loads(subprocess.check_output(['kubectl','-n','keel-system','get','secret','keel-llm','-o','json']))
    raw=secret.get('data',{}).get('request-defaults');prior=json.loads(base64.b64decode(raw)) if raw else {}
    after=desired(prior)
    print(json.dumps({'changed':after!=prior,'alias':'keel-assistant-staging','profiles':after['keel-assistant-staging'],'applied':args.apply},ensure_ascii=False))
    if args.apply and after!=prior:
        encoded=base64.b64encode(json.dumps(after,separators=(',',':')).encode()).decode()
        patch=[{'op':'test','path':'/metadata/resourceVersion','value':secret['metadata']['resourceVersion']},
               {'op':'add','path':'/data/request-defaults','value':encoded}]
        subprocess.run(['kubectl','-n','keel-system','patch','secret','keel-llm','--type=json','-p',json.dumps(patch)],check=True)
if __name__=='__main__':main()
