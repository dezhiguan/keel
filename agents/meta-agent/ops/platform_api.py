"""Authenticated platform operations; credentials are read from a caller-supplied file."""
import json
from pathlib import Path
import urllib.error
import urllib.request


class Platform:
    def __init__(self, base_url, cookie_file):
        self.base_url = base_url.rstrip('/')
        raw = Path(cookie_file).read_text().strip()
        try:
            saved = json.loads(raw)
        except json.JSONDecodeError:
            saved = None
        if isinstance(saved, dict) and isinstance(saved.get('cookies'), list):
            cookies = {item['name']: item['value'] for item in saved['cookies']
                       if item.get('name') in ('keel_console_at', 'keel_console_rt')}
            self.cookie = '; '.join(f'{name}={value}' for name, value in cookies.items())
        elif isinstance(saved, dict):
            self.cookie = '; '.join(f'{name}={saved[name]}' for name in ('keel_console_at','keel_console_rt') if saved.get(name))
        else:
            self.cookie = raw
        if not self.cookie or '\n' in self.cookie or '\r' in self.cookie:
            raise ValueError('需要有效的 Keel 控制台认证文件')

    def request(self, method, path, body=None):
        request = urllib.request.Request(self.base_url + path,
            data=None if body is None else json.dumps(body,ensure_ascii=False).encode(), method=method,
            headers={'Content-Type':'application/json','Cookie':self.cookie,'X-Keel-Console':'1'})
        try:
            with urllib.request.urlopen(request,timeout=180) as response:
                payload = json.load(response)
        except urllib.error.HTTPError as error:
            # Never echo headers, tokens or an upstream error body.
            raise RuntimeError(f'平台请求失败 HTTP {error.code}: {method} {path}') from None
        if payload.get('code') not in (None,'OK'):
            raise RuntimeError(f'平台拒绝请求: {payload.get("code")}')
        return payload.get('data')


def save_evidence(path, data):
    target = Path(path)
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
