"""Shared-volume run state must survive concurrent runtime replicas."""
import multiprocessing
import os
from pathlib import Path


def _save_run(path, run_id):
    os.environ['KEEL_RUN_STORE'] = path
    from keel.runs import save
    save({'run_id': run_id, 'status': 'SUSPENDED', 'resume_token': 'test-token'})


def _claim_run(path, barrier, results):
    os.environ['KEEL_RUN_STORE'] = path
    from keel.runs import claim
    barrier.wait()
    results.put(claim('shared', 'test-token'))


def test_replicas_do_not_lose_independent_suspended_runs(tmp_path, monkeypatch):
    from keel.runs import get
    path = str(tmp_path / 'runs.json')
    monkeypatch.setenv('KEEL_RUN_STORE', path)
    ctx = multiprocessing.get_context('spawn')
    processes = [ctx.Process(target=_save_run, args=(path, str(i))) for i in range(12)]
    for p in processes: p.start()
    for p in processes:
        p.join(20)
        assert p.exitcode == 0
    assert all(get(str(i))['status'] == 'SUSPENDED' for i in range(12))


def test_resume_token_is_consumed_by_only_one_replica(tmp_path, monkeypatch):
    from keel.runs import get, claim
    path = str(tmp_path / 'runs.json')
    monkeypatch.setenv('KEEL_RUN_STORE', path)
    _save_run(path, 'shared')
    assert not claim('shared', 'wrong-token')
    ctx = multiprocessing.get_context('spawn')
    barrier, results = ctx.Barrier(2), ctx.Queue()
    processes = [ctx.Process(target=_claim_run, args=(path, barrier, results)) for _ in range(2)]
    for p in processes: p.start()
    for p in processes:
        p.join(20)
        assert p.exitcode == 0
    assert sorted([results.get(timeout=2), results.get(timeout=2)]) == [False, True]
    assert get('shared')['status'] == 'DONE'
