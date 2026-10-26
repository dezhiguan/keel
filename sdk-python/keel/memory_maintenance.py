"""Lifespan workers for durable server-owned memory jobs. Never an in-request task."""
import asyncio
import copy
import json
import logging
import os
import re
import time
import uuid
from decimal import Decimal
from contextlib import ExitStack, nullcontext
from types import SimpleNamespace
from opentelemetry import trace

from keel.context_window import SUMMARY_PROMPT, tokens, turns
from keel.http_pool import use_transport
from keel.llm.client import LlmClient, estimate_context_tokens
from keel.memory_remote import UserMemory
from keel.memory_evidence import evidence
from keel.prompts import TrustedMessage
from keel.session_facts import SessionFacts
from keel.session_history import source_ids
from keel.memory_combined import prepare as prepare_combined, validate_summary, validate_facts

log=logging.getLogger(__name__)


class WorkLimit(Exception):
    pass


class BoundedModel:
    """Reserve a conservative configured CNY upper bound before every paid call.

    Bounds must cover all allowed route/fallback prices. This is explicitly not
    settled spend; actual request IDs remain on managed generation spans.
    """
    def __init__(self,llm,job,input_price,output_price,limiter=None):
        self.llm,self.job=llm,job
        self.limiter=limiter
        self.input_price,self.output_price=Decimal(input_price),Decimal(output_price)
        if not self.input_price.is_finite() or not self.output_price.is_finite() or self.input_price<=0 or self.output_price<=0:raise ValueError('Missing memory price bounds')
        self.last_error=None
        self.usage_known=True
        self.calls=0;self.reserved=Decimal(0);self.input_tokens=0;self.output_tokens=0
        self.tracer,self.trace_id=llm.tracer,llm.trace_id
    async def chat(self,messages,**options):
        used=estimate_context_tokens(messages)
        output=min(int(options.get('max_tokens',1600)),self.job['maxOutputTokens'])
        bound=Decimal(used)*self.input_price+Decimal(output)*self.output_price
        if self.calls>=self.job['maxCalls'] or used>self.job['maxInputTokens']-128 or self.reserved+bound>Decimal(str(self.job['maxCostCny'])):
            self.last_error=WorkLimit('budget');raise self.last_error
        self.calls+=1;self.reserved+=bound
        try:
            async with (self.limiter or nullcontext()):
                result=await self.llm.chat(messages,**{**options,'max_tokens':output,'emit_tokens':False,'purpose':'memory'})
        except asyncio.CancelledError:
            self.usage_known=False
            raise
        except Exception as error:
            self.last_error=error;self.usage_known=False;raise
        if not getattr(result,'input_tokens',0):self.usage_known=False
        self.input_tokens+=getattr(result,'input_tokens',0);self.output_tokens+=getattr(result,'output_tokens',0)
        return result
    def usage(self):
        return dict(calls=self.calls,costUpperBoundCny=float(self.reserved),inputTokens=self.input_tokens if self.usage_known else None,outputTokens=self.output_tokens if self.usage_known else None)


class FactBatch:
    def __init__(self,job):
        self.job=job;self.candidate=None
        self.guard=None
    async def load_facts(self,external):
        return {k:copy.deepcopy(self.job[k]) for k in ('state','messages','skippedIds','nextCursor','more')}
    async def save_facts(self,state,external):
        self.candidate=copy.deepcopy(state)
        return {'saved':True,'state':{**state,'version':state['version']+1}}


async def extract_facts(model,job):
    memory=FactBatch(job)
    collector=SessionFacts(model,memory,True,model.llm.agent,job['env'])
    await collector.collect([],time.monotonic()+55,strict=True)
    if memory.candidate is None:
        if isinstance(model.last_error,(WorkLimit,TimeoutError)):raise model.last_error
        raise ValueError('invalid_output')
    return memory.candidate


def summary_prefix(job,budget):
    """Choose complete consecutive turns. Each chunk independently fits the cap."""
    chunks=[];current=[];selected=[]
    for group in turns(job['messages']):
        if group[-1].get('role')!='assistant':break
        candidate=current+group
        def cost(rows):
            material=json.dumps({'旧摘要':job['state']['content'],'历史片段':rows},ensure_ascii=False)
            return tokens([{'role':'system','content':SUMMARY_PROMPT},{'role':'user','content':material}],[])
        # Summary growth during rolling calls is bounded to 1200 tokens.
        if cost(candidate)>min(budget-1500,7000):
            if not current:raise WorkLimit('source_too_large')
            chunks.append(current);current=[]
            if len(chunks)>=job['maxCalls']:break
            if cost(group)>min(budget-1500,7000):
                break  # Commit the valid prefix; the next job exposes this oversized turn.
        current+=group;selected+=group
    if current and len(chunks)<job['maxCalls']:chunks.append(current)
    if not chunks:raise WorkLimit('source_too_large')
    selected=[row for chunk in chunks for row in chunk]
    return chunks,selected[-1]['id']


async def summarize(model,job):
    chunks,last=summary_prefix(job,job['maxInputTokens'])
    content=job['state']['content'];allowed=set(source_ids(content,limit=None))
    for chunk in chunks:
        allowed.update(str(r['id']) for r in chunk)
        response=await model.chat([TrustedMessage({'role':'system','content':SUMMARY_PROMPT}),
            {'role':'user','content':json.dumps({'旧摘要':content,'历史片段':chunk},ensure_ascii=False)}],tools=[],max_tokens=512)
        content=getattr(response,'content',response)
        if not isinstance(content,str) or not content.strip() or estimate_context_tokens([{'role':'user','content':content}])>1200:
            raise ValueError('invalid_output')
        refs=source_ids(content,limit=None)
        if not refs or any(ref not in allowed for ref in refs):raise ValueError('invalid_output')
    return {'version':job['expectedVersion'],'throughId':last,'content':content}


async def execute_combined(agent,remote,first,second,tracer,limiter=None):
    try:
        prepared=prepare_combined(first,second,summary_prefix)
    except WorkLimit:
        prepared=None
    if prepared is None:
        # No paid combined attempt occurred. Each lease keeps its original bounds.
        await asyncio.gather(execute(agent,remote,first,tracer,limiter),execute(agent,remote,second,tracer,limiter))
        return
    messages,summary,facts,rows,through=prepared
    with tracer.start_as_current_span('memory.maintenance',context=trace.Context(),attributes={
        'langfuse.observation.type':'agent','keel.agent':agent.name,'keel.env':first['env'],
        'service.instance.id':os.environ.get('HOSTNAME',''),'keel.memory.combined':True,
        'keel.memory.task_id':str(first['id']),'keel.memory.trigger_trace_id':first['triggerTraceId'],
        'langfuse.session.id':first['sessionId'],'keel.status':'ok',
    }) as root, ExitStack() as stack:
        trace_id=f'{root.get_span_context().trace_id:032x}' if root.get_span_context().is_valid else uuid.uuid4().hex
        llm=LlmClient(agent.name,agent.manifest.spec.models.default_,trace_id,tracer,retry_status_errors=False)
        limits={**first,'maxOutputTokens':min(first['maxOutputTokens'],second['maxOutputTokens'])}
        model=BoundedModel(llm,limits,os.environ['KEEL_MEMORY_MAX_INPUT_CNY_PER_TOKEN'],os.environ['KEEL_MEMORY_MAX_OUTPUT_CNY_PER_TOKEN'],limiter)
        events={}
        for job in (first,second):
            ev=stack.enter_context(evidence(agent.name,job['env'],job['kind'],tracer))
            ev.update(status='attempted',taskId=str(job['id']),triggerTraceId=job['triggerTraceId'],
                      targetId=job['targetId'],version=job['expectedVersion'],attempt=job['attempt'])
            events[job['id']]=ev
        def body(job):
            # Shared generation is accounted once. Both attempts share the trace.
            usage=model.usage() if job['id']==first['id'] else dict(calls=0,inputTokens=0,outputTokens=0,costUpperBoundCny=0)
            return dict(id=job['id'],leaseToken=job['leaseToken'],traceId=trace_id,**usage)
        async def fail(job,reason):
            events[job['id']].update(status='failed',reason=reason)
            root.set_attribute('keel.status','failed')
            try:await remote.fail_maintenance({**body(job),'reason':reason})
            except Exception:
                log.warning('memory combined report failed trace_id=%s agent=%s task=%s',trace_id,agent.name,job['id'])
        completed=set()
        try:
            async with asyncio.timeout(60):
                response=await model.chat(messages,tools=[],max_tokens=1600)
                content=getattr(response,'content',response)
                if not isinstance(content,str) or len(content)>24000:raise ValueError('invalid_output')
                parsed=json.loads(content)
                if not isinstance(parsed,dict):raise ValueError('invalid_output')
                # Independent validators and independent CAS. Never trust one result for the other.
                for job in (first,second):
                    try:
                        state=(validate_summary(parsed.get('summary'),summary,rows,through) if job['kind']=='summary'
                               else validate_facts(parsed.get('facts'),facts))
                    except (ValueError,TypeError,KeyError):
                        await fail(job,'invalid_output');completed.add(job['id']);continue
                    result=await remote.complete_maintenance({**body(job),'state':state})
                    events[job['id']].update(status='ok' if result['saved'] else 'failed',
                        reason='committed' if result['saved'] else 'conflict',cursorId=state.get('throughId',state.get('cursorId')))
                    if not result['saved']:root.set_attribute('keel.status','fallback')
                    completed.add(job['id'])
        except asyncio.CancelledError:
            for job in (first,second):
                if job['id'] not in completed:await asyncio.shield(fail(job,'cancelled'))
            raise
        except Exception as error:
            reason='timeout' if isinstance(error,TimeoutError) else str(error) if isinstance(error,WorkLimit) else 'invalid_output' if isinstance(error,ValueError) else 'upstream'
            for job in (first,second):
                if job['id'] not in completed:await fail(job,reason)
        finally:
            for job in (first,second):
                events[job['id']].update({k:v for k,v in body(job).items() if k not in ('id','leaseToken','traceId')})
            await llm.aclose()


async def execute(agent,remote,job,tracer,limiter=None):
    with tracer.start_as_current_span('memory.maintenance',context=trace.Context(),attributes={
        'langfuse.observation.type':'agent','keel.agent':agent.name,'keel.env':job['env'],
        'service.instance.id':os.environ.get('HOSTNAME',''),'keel.memory.trigger_trace_id':job['triggerTraceId'],'keel.memory.task_id':str(job['id']),
        'keel.status':'ok','langfuse.session.id':job['sessionId'],
    }) as root:
        trace_id=f'{root.get_span_context().trace_id:032x}' if root.get_span_context().is_valid else uuid.uuid4().hex
        llm=LlmClient(agent.name,agent.manifest.spec.models.default_,trace_id,tracer,retry_status_errors=False)
        model=BoundedModel(llm,job,os.environ['KEEL_MEMORY_MAX_INPUT_CNY_PER_TOKEN'],os.environ['KEEL_MEMORY_MAX_OUTPUT_CNY_PER_TOKEN'],limiter)
        base={'id':job['id'],'leaseToken':job['leaseToken'],'traceId':trace_id}
        with evidence(agent.name,job['env'],job['kind'],tracer) as ev:
            ev.update(status='attempted',taskId=str(job['id']),triggerTraceId=job['triggerTraceId'],
                      targetId=job['targetId'],version=job['expectedVersion'],attempt=job['attempt'])
            try:
                async with asyncio.timeout(60):
                    state=await (summarize(model,job) if job['kind']=='summary' else extract_facts(model,job))
                    result=await remote.complete_maintenance({**base,'state':state,**model.usage()})
                    ev.update(status='ok' if result['saved'] else 'failed',reason='committed' if result['saved'] else 'conflict',
                              cursorId=state.get('throughId',state.get('cursorId')),**model.usage())
                    if not result['saved']:root.set_attribute('keel.status','fallback')
            except asyncio.CancelledError:
                root.set_attribute('keel.status','failed');ev.update(status='failed',reason='cancelled',**model.usage())
                # Lease recovery handles hard process death; graceful shutdown can report sooner.
                try:await asyncio.shield(remote.fail_maintenance({**base,'reason':'cancelled',**model.usage()}))
                except Exception:pass
                raise
            except Exception as error:
                reason='timeout' if isinstance(error,TimeoutError) else (str(error) if isinstance(error,WorkLimit) else 'invalid_output' if isinstance(error,ValueError) else 'upstream')
                ev.update(status='failed',reason=reason,**model.usage());root.set_attribute('keel.status','failed')
                try:await remote.fail_maintenance({**base,'reason':reason,**model.usage()})
                except Exception:log.warning('memory job report failed trace_id=%s agent=%s task=%s',trace_id,agent.name,job['id'])
                log.warning('memory job failed trace_id=%s agent=%s task=%s reason=%s',trace_id,agent.name,job['id'],reason)
            finally:
                await llm.aclose()


def start(agent,transport,tracer):
    if os.environ.get('KEEL_MEMORY_MAINTENANCE_MODE','sync')!='async':return []
    if not agent.manifest.spec.models:raise ValueError('Memory workers require managed model configuration')
    # Fail startup rather than silently run jobs without a valid paid-call ceiling.
    for name in ('KEEL_MEMORY_MAX_INPUT_CNY_PER_TOKEN','KEEL_MEMORY_MAX_OUTPUT_CNY_PER_TOKEN'):
        price=Decimal(os.environ.get(name,'0'))
        if not price.is_finite() or price<=0:raise ValueError('Configure '+name)
    concurrency=int(os.environ.get('KEEL_MEMORY_WORKERS','4'))
    if not 1<=concurrency<=32:raise ValueError('KEEL_MEMORY_WORKERS must be 1..32')
    async def poll():
        active=set()
        limiter=asyncio.Semaphore(concurrency)
        try:
            with use_transport(transport):
                remote=UserMemory(agent.name,os.environ.get('KEEL_ENV'))
                while True:
                    try:
                        if len(active)>=concurrency:
                            await asyncio.wait(active,return_when=asyncio.FIRST_COMPLETED)
                        active={t for t in active if not t.done()}
                        response=await remote.claim_maintenance();job=response.get('job')
                        if job:
                            from keel.protocol.memory import MaintenanceJob
                            job=MaintenanceJob.model_validate(job).model_dump(by_alias=True)
                            companion=response.get('companion')
                            if companion is not None:
                                companion=MaintenanceJob.model_validate(companion).model_dump(by_alias=True)
                            task=asyncio.create_task(execute_combined(agent,remote,job,companion,tracer,limiter) if companion else execute(agent,remote,job,tracer,limiter))
                            # Retrieve unexpected errors as well; leased jobs recover after a crash.
                            def completed(t):
                                if not t.cancelled() and t.exception():
                                    log.error('memory worker crashed trace_id=background agent=%s',agent.name)
                            task.add_done_callback(completed)
                            active.add(task)
                        else:await asyncio.sleep(.5)
                    except asyncio.CancelledError:raise
                    except Exception:
                        log.warning('memory worker polling failed trace_id=background agent=%s',agent.name)
                        await asyncio.sleep(2)
        finally:
            for task in active:task.cancel()
            await asyncio.gather(*active,return_exceptions=True)
    return [asyncio.create_task(poll(),name='keel-memory-dispatcher')]
