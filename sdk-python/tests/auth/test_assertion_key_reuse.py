import base64
import json
import pytest
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa,padding
from keel.auth import client_assertion as mod

def key():
    value=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    pem=value.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
    return value,pem

def decode(s):return base64.urlsafe_b64decode(s+'='*(-len(s)%4))

def test_key_reuse_keeps_assertions_fresh_and_rotated_keys_independent(monkeypatch):
    mod._signing_key.cache_clear();one,pem1=key();two,pem2=key();loaded=[]
    original=mod.serialization.load_pem_private_key
    def load(*args,**kwargs):loaded.append(1);return original(*args,**kwargs)
    monkeypatch.setattr(mod.serialization,'load_pem_private_key',load)
    first=mod.sign_client_assertion('agent-one','audience-one',pem1,now=1000)
    second=mod.sign_client_assertion('agent-two','audience-two',pem1,now=1001)
    third=mod.sign_client_assertion('agent-one','audience-one',pem2,now=1002)
    assert len(loaded)==2
    for token,private,client,aud,iat in [(first,one,'agent-one','audience-one',1000),(second,one,'agent-two','audience-two',1001),(third,two,'agent-one','audience-one',1002)]:
        header,body,sig=token.split('.')
        private.public_key().verify(decode(sig),(header+'.'+body).encode(),padding.PKCS1v15(),hashes.SHA256())
        claims=json.loads(decode(body));assert claims['iss']==claims['sub']==client and claims['aud']==aud
        assert claims['iat']==iat and claims['exp']==iat+300
    assert len({json.loads(decode(t.split('.')[1]))['jti'] for t in (first,second,third)})==3
    assert mod._signing_key.cache_info().maxsize==2
    with pytest.raises(ValueError):mod.sign_client_assertion('a','b','invalid PEM')
    mod._signing_key.cache_clear()
