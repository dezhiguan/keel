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

REMOTE='''import hashlib,json,os,sys,httpx
request=json.load(sys.stdin)
with httpx.Client(base_url=os.environ['LANGFUSE_HOST'],auth=(os.environ['LANGFUSE_PUBLIC_KEY'],os.environ['LANGFUSE_SECRET_KEY']),timeout=60) as client:
    response=client.post('/api/public/datasets',json={'name':request['dataset']})
    response.raise_for_status()
    for row in request['rows']:
        item_id=hashlib.sha256((request['dataset']+'\\n'+row['id']).encode()).hexdigest()
        response=client.post('/api/public/dataset-items',json={'id':item_id,'datasetName':request['dataset'],
            'input':row['input'],'expectedOutput':row['expected'],'status':'ACTIVE',
            'metadata':{'tags':row['tags'],'source':'meta-agent-v2','caseId':row['id']}})
        response.raise_for_status()
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
        raise RuntimeError('数据集导入失败；检查节点上的受控执行环境，原数据集保持不变')
    print(completed.stdout.decode().strip())


if __name__=='__main__': main()
