"""Publishing canonicalizes authored Markdown; content changes must still be rejected."""
import importlib.util
import json
from pathlib import Path
import sys
import pytest

ROOT=Path(__file__).resolve().parents[1]


@pytest.mark.parametrize('changed', [False, True])
def test_skill_grant_accepts_platform_text_normalization_and_rejects_content_changes(tmp_path, monkeypatch, changed):
    monkeypatch.syspath_prepend(str(ROOT/'ops'))
    spec=importlib.util.spec_from_file_location('meta_release_ops', ROOT/'ops/release.py')
    ops=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ops)
    payload=json.loads((ROOT/'skills/keel-project-development.json').read_text())
    manifest={'spec':{'eval':{'dataset':'meta-agent/v2-development'}}}
    path=tmp_path/'agent.yaml';path.write_text(json.dumps(manifest))
    grants=[]

    class Platform:
        def request(self, method, path, body=None):
            if method=='GET' and path.startswith('/api/v1/agents/meta-agent?'):
                return {'manifestYaml':json.dumps(manifest),'manifestHash':'current'}
            if method=='GET' and path=='/api/v1/skills':return {'items':[{'name':payload['name']}]}
            if method=='GET' and path=='/api/v1/skills/'+payload['name']:
                return {'versions':[{**payload,'instructions':payload['instructions'].strip()+(' changed' if changed else '')}]}
            assert (method,path)==('POST','/api/v1/agents/meta-agent/skills')
            grants.append(body)
            return body

    monkeypatch.setattr(ops,'Platform',lambda *args:Platform())
    monkeypatch.setattr(sys,'argv',['release.py','skill','--server-url','http://platform.test',
        '--cookie-file',str(tmp_path/'unused-cookie'),'--manifest',str(path),'--evidence',str(tmp_path/'evidence.json')])
    if changed:
        with pytest.raises(RuntimeError,match='内容不同'):ops.main()
        assert grants==[]
    else:
        ops.main()
        assert grants==[{'name':payload['name'],'version':1}]
