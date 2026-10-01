from fastapi.testclient import TestClient

from app import bkt, config


def test_replay_matches_step_by_step_updates():
    attempts = [
        {"correct": True, "difficulty": "EASY", "seconds": 20, "daysSinceLast": 0},
        {"correct": False, "difficulty": "HARD", "seconds": 200, "daysSinceLast": 3},
        {"correct": True, "difficulty": "MEDIUM", "seconds": 40, "daysSinceLast": 1},
    ]
    params = bkt.BktParams()
    expected = params.p_init
    for a in attempts:
        expected = bkt.update(expected, a["correct"], params, a["difficulty"], a["seconds"], a["daysSinceLast"])
    assert bkt.replay(attempts, params) == expected


def test_replay_endpoint(monkeypatch):
    monkeypatch.setattr(config, "SERVICE_KEY", "")
    from app.main import app
    with TestClient(app) as client:
        body = {"items": [
            {"key": "7", "attempts": [{"correct": True}, {"correct": True}, {"correct": True}]},
            {"key": "8", "attempts": [{"correct": False}, {"correct": False}]},
        ]}
        results = {r["key"]: r for r in client.post("/bkt/replay", json=body).json()["results"]}
        assert results["7"]["attempts"] == 3
        assert results["7"]["mastery"] > results["8"]["mastery"]


def test_sm2_replay_matches_step_by_step():
    from app import sm2
    attempts = [{"correct": True, "seconds": 20}, {"correct": True, "seconds": 90},
                {"correct": True, "seconds": 30}, {"correct": False, "seconds": 50}]
    state = {"repetitions": 0, "intervalDays": 0, "easeFactor": 2.5}
    for a in attempts:
        state = sm2.schedule(state["repetitions"], state["intervalDays"], state["easeFactor"],
                             a["correct"], a["seconds"])
    replayed = sm2.replay(attempts)
    assert replayed["repetitions"] == state["repetitions"] == 0
    assert replayed["intervalDays"] == state["intervalDays"] == 1
    assert replayed["easeFactor"] == state["easeFactor"]


def test_review_replay_endpoint(monkeypatch):
    monkeypatch.setattr(config, "SERVICE_KEY", "")
    from app.main import app
    with TestClient(app) as client:
        body = {"items": [
            {"key": "1", "attempts": [{"correct": True}, {"correct": True}, {"correct": True}]},
            {"key": "2", "attempts": [{"correct": True}, {"correct": False}]},
        ]}
        results = {r["key"]: r for r in client.post("/review/replay", json=body).json()["results"]}
        assert results["1"]["repetitions"] == 3 and results["1"]["intervalDays"] > 6
        assert results["2"]["repetitions"] == 0 and results["2"]["intervalDays"] == 1
        assert results["1"]["attempts"] == 3


def test_plan_validation_rejects_duplicate_days():
    import pytest
    from app import plan
    from app.schemas import PlanRequest
    day = {"focus": "x", "minutes": 30, "activities": ["a"]}
    days = [dict(day, day=n) for n in (1, 2, 3, 4, 5, 6, 6)]
    with pytest.raises(ValueError):
        plan.validate({"days": days}, PlanRequest(subject="Java"))
