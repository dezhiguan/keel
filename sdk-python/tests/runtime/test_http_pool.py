import asyncio
import httpx
from keel.http_pool import new_async_client, use_transport, BorrowedTransport


class Transport(httpx.AsyncBaseTransport):
    def __init__(self):
        self.seen=[];self.closed=0
    async def handle_async_request(self, request):
        await asyncio.sleep(0)
        self.seen.append((request.headers.get('authorization'),request.headers.get('cookie'),request.headers.get('x-trace-id')))
        return httpx.Response(200,headers={'set-cookie':'private=reply'},json={'ok':True})
    async def aclose(self):self.closed+=1


def clean_proxy(monkeypatch):
    for key in ('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy'):
        monkeypatch.delenv(key,raising=False)


def test_pool_reuses_transport_without_sharing_auth_cookies_or_trace_and_closes_only_at_owner(monkeypatch):
    clean_proxy(monkeypatch)
    async def run():
        transport=Transport()
        with use_transport(transport):
            async def call(name):
                async with new_async_client(headers={'authorization':name,'x-trace-id':'trace-'+name}) as client:
                    await client.get('https://service.test')
            await asyncio.gather(call('a'),call('b'))
            assert transport.closed==0
            await call('c')
        assert transport.seen==[('a',None,'trace-a'),('b',None,'trace-b'),('c',None,'trace-c')]
        assert transport.closed==0
        await transport.aclose();assert transport.closed==1
    asyncio.run(run())


def test_transport_binding_is_invocation_scoped_and_explicit_transport_is_respected(monkeypatch):
    clean_proxy(monkeypatch)
    async def run():
        a,b=Transport(),Transport()
        async def call(t,label):
            with use_transport(t):
                await asyncio.sleep(0)
                async with new_async_client(headers={'authorization':label}) as client:
                    await client.get('https://service.test')
        await asyncio.gather(call(a,'a'),call(b,'b'))
        assert a.seen[0][0]=='a' and b.seen[0][0]=='b'
        with use_transport(a):
            async with new_async_client(transport=b) as client:await client.get('https://service.test')
        assert len(a.seen)==1 and len(b.seen)==2
        async with new_async_client() as independent:assert not isinstance(independent._transport,BorrowedTransport)
    asyncio.run(run())


def test_cancelled_borrower_does_not_close_pool_or_break_next_request(monkeypatch):
    clean_proxy(monkeypatch)
    class Blocking(Transport):
        async def handle_async_request(self,request):
            if request.url.path=='/block':await asyncio.Event().wait()
            return await super().handle_async_request(request)
    async def run():
        t=Blocking()
        with use_transport(t):
            async with new_async_client() as client:
                task=asyncio.create_task(client.get('https://service.test/block'))
                await asyncio.sleep(.01);task.cancel()
                await asyncio.gather(task,return_exceptions=True)
            async with new_async_client() as other:assert (await other.get('https://service.test/ok')).is_success
        assert t.closed==0
    asyncio.run(run())


def test_environment_proxy_keeps_httpx_default_transport(monkeypatch):
    clean_proxy(monkeypatch);monkeypatch.setenv('HTTPS_PROXY','http://proxy.test:8080')
    async def run():
        with use_transport(Transport()):
            async with new_async_client() as client:assert not isinstance(client._transport,BorrowedTransport)
    asyncio.run(run())


def test_asgi_lifespan_owns_pool_and_request_binding_reaches_entry(monkeypatch,tmp_path):
    from starlette.testclient import TestClient
    from keel.agent import Agent
    from keel.manifest import AgentManifest
    import keel.http_pool as pool
    clean_proxy(monkeypatch);monkeypatch.chdir(tmp_path)
    transport=Transport();monkeypatch.setattr(pool,'create_transport',lambda:transport)
    manifest=AgentManifest.model_validate({'apiVersion':'keel/v1','metadata':{'name':'pool-agent'},'spec':{'kind':'workflow','runtime':{'endpoint':'https://agent.test'}}})
    agent=Agent(manifest)
    @agent.entry
    async def entry(request,ctx):
        async with new_async_client(headers={'authorization':request.input['text']}) as client:
            await client.get('https://service.test')
        return ctx.final('pool fixture')
    with TestClient(agent.asgi()) as client:
        for text in ('a','b'):
            response=client.post('/v1/invoke',json={'input':{'text':text}})
            assert response.status_code==200 and 'pool fixture' in response.text
            assert transport.closed==0
    assert [row[0] for row in transport.seen]==['a','b']
    assert transport.closed==1
