"""Reviewable v1 baseline sealing, candidate preparation, evaluation and gated publication."""
import argparse
import hashlib
import json
from pathlib import Path
import yaml
from platform_api import Platform, save_evidence

ROOT=Path(__file__).resolve().parents[1]
AGENT='meta-agent'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['baseline','candidate','prompts','skill','start','status','publish'])
    parser.add_argument('--server-url',required=True)
    parser.add_argument('--cookie-file',required=True)
    parser.add_argument('--evidence',required=True)
    parser.add_argument('--run-id')
    parser.add_argument('--git-sha')
    parser.add_argument('--manifest',default=str(ROOT/'agent.yaml'))
    parser.add_argument('--reconcile-runtime',action='store_true',help='管理员核实历史运行清单漂移后显式对齐')
    args=parser.parse_args()
    api=Platform(args.server_url,args.cookie_file)
    manifest=yaml.safe_load(Path(args.manifest).read_text())
    prefix=f'/api/v1/agents/{AGENT}'
    if args.action in ('baseline','publish'):
        if not args.run_id: parser.error('需要 --run-id')
        measured=api.request('GET',f'/api/v1/eval/runs/{args.run_id}')
        result=measured.get('result') or {}
        if measured.get('agent')!=AGENT or measured.get('state')!='DONE' or not result.get('complete') or not result.get('passed'):
            raise RuntimeError('只能发布该智能体完整通过的评测')
        if args.action=='baseline' and (result.get('caseCount')!=52 or len(result.get('dimensions',[]))!=16):
            raise RuntimeError('第一版基线必须完整覆盖原52条和16个标签')
        # Stage the exact measured prompt versions. The platform performs its own gate validation.
        for name,version in result['promptVersions'].items():
            api.request('POST',f'{prefix}/prompts/{name}/promote',{'env':'staging','version':version})
        save_evidence(args.evidence+'.evaluation.json',measured)
        outcome=api.request('POST',f'/api/v1/eval/{AGENT}/runs/{args.run_id}/publish',{'env':'prod'})
    elif args.action=='candidate':
        detail=api.request('GET',prefix+'?includeRuntimeEvidence=false')
        save_evidence(args.evidence+'.previous.json',detail)
        outcome=api.request('PUT',prefix+'/candidate',{'env':'dev','expectedHash':detail['manifestHash'],'manifest':manifest,
                                                     'reconcileRuntime':args.reconcile_runtime})
    elif args.action=='prompts':
        if not args.git_sha or len(args.git_sha)!=40: parser.error('需要完整 --git-sha')
        files=[]
        for item in manifest['spec']['prompts']['items']:
            body=(ROOT/'prompts'/f'{item["name"]}.md').read_text()
            files.append({'name':item['name'],'type':item['type'],'prompt':body,
                          'sha256':'sha256:'+hashlib.sha256(body.encode()).hexdigest()})
        outcome=api.request('POST',prefix+'/prompts/sync',{'gitSha':args.git_sha,'files':files})
        versions={row['name']:row['version'] for row in outcome['created']}
        for item in files:
            if item['name'] not in versions:
                detail=api.request('GET',f'{prefix}/prompts/{item["name"]}')
                candidates=detail.get('versions') or []
                matching=[v for v in candidates if v.get('sha256')==item['sha256']]
                if not matching: raise RuntimeError('无法定位同步后的提示词版本')
                versions[item['name']]=matching[0]['version']
            for env in ('dev','staging'):
                api.request('POST',f'{prefix}/prompts/{item["name"]}/promote',{'env':env,'version':versions[item['name']]})
        outcome={**outcome,'activated':versions}
    elif args.action=='skill':
        # Granting imports cases into the current dataset. Move only the dataset
        # pointer first, so the measured gateway dataset remains unchanged.
        current=api.request('GET',prefix+'?includeRuntimeEvidence=false')
        staged=yaml.safe_load(current['manifestYaml'])
        dataset=manifest['spec']['eval']['dataset']
        if staged['spec']['eval']['dataset']!=dataset:
            staged['spec']['eval']['dataset']=dataset
            api.request('PUT',prefix+'/candidate',{'env':'dev','expectedHash':current['manifestHash'],'manifest':staged})
        payload=json.loads((ROOT/'skills/keel-project-development.json').read_text())
        existing=api.request('GET','/api/v1/skills')
        found=next((row for row in existing.get('items',[]) if row['name']==payload['name']),None)
        if found:
            detail=api.request('GET',f'/api/v1/skills/{payload["name"]}')
            version=next((v for v in detail['versions'] if v['version']==payload['version']),None)
            if not version or any(version.get(field)!=payload[field] for field in ('instructions','tools','examples','cases')):
                raise RuntimeError('技能版本已存在但内容不同；必须人工发布新版本')
        else:
            created=api.request('POST','/api/v1/skills',payload)
            if created['version']!=payload['version']: raise RuntimeError('技能版本不一致')
        outcome=api.request('POST',prefix+'/skills',{'name':payload['name'],'version':payload['version']})
    elif args.action=='start':
        outcome=api.request('POST',f'/api/v1/eval/{AGENT}/runs',{})
    else:
        if not args.run_id: parser.error('需要 --run-id')
        outcome=api.request('GET',f'/api/v1/eval/runs/{args.run_id}')
    save_evidence(args.evidence,outcome)
    print(json.dumps(outcome,ensure_ascii=False))


if __name__=='__main__': main()
