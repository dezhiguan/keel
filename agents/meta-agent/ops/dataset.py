"""Create versioned v2 datasets without changing the original 52-case dataset.

Run on the application node with the project checked out. Secrets stay in the runtime pod.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'evals'))
from v2_cases import cases

REMOTE='''import hashlib,json,os,sys,time,httpx
request=json.load(sys.stdin)
with httpx.Client(base_url=os.environ['LANGFUSE_HOST'],auth=(os.environ['LANGFUSE_PUBLIC_KEY'],os.environ['LANGFUSE_SECRET_KEY']),timeout=60) as client:
    def post(path, body):
        for attempt in range(6):
            response=client.post(path,json=body)
            if response.status_code != 429 and response.status_code < 500:
                break
            if attempt < 5:
                try: delay=float(response.headers.get('retry-after',2**attempt))
                except ValueError: delay=2**attempt
                time.sleep(min(max(delay,1),60))
        if response.is_error:
            print('DATASET_IMPORT_ERROR HTTP '+str(response.status_code),file=sys.stderr)
            raise SystemExit(1)
        return response
    post('/api/public/datasets',{'name':request['dataset']})
    for row in request['rows']:
        item_id=hashlib.sha256((request['dataset']+'\\n'+row['id']).encode()).hexdigest()
        post('/api/public/dataset-items',{'id':item_id,'datasetName':request['dataset'],
            'input':row['input'],'expectedOutput':row['expected'],'status':'ACTIVE',
            'metadata':{'tags':row['tags'],'source':'meta-agent-v2','caseId':row['id']}})
    print(json.dumps({'dataset':request['dataset'],'imported':len(request['rows'])}))
'''


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase',choices=['gateway','development'],required=True)
    parser.add_argument('--snapshot',required=True)
    args=parser.parse_args()
    rows=cases(skill_version=1 if args.phase=='development' else None)
    payload={'dataset':'meta-agent/v2-'+args.phase,'rows':rows}
    # Persist exact definitions alongside the measured release evidence.
    snapshot=Path(args.snapshot)
    snapshot.parent.mkdir(parents=True,exist_ok=True)
    snapshot.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n')
    completed=subprocess.run(['kubectl','-n','keel-system','exec','-i','deployment/meta-agent','--','python','-c',REMOTE],
                             input=json.dumps(payload).encode(),capture_output=True)
    if completed.returncode:
        status=next((line for line in completed.stderr.decode().splitlines()
                     if line.startswith('DATASET_IMPORT_ERROR HTTP ')), '受控执行环境错误')
        raise RuntimeError('数据集导入失败：'+status+'；可按相同ID重试，原数据集保持不变')
    print(completed.stdout.decode().strip())


if __name__=='__main__': main()
