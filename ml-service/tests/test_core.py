import numpy as np
import pytest
from fastapi.testclient import TestClient

from app import bkt, config, recall, sm2
from app.features import feature_vector, formula_recall


# ---- BKT -------------------------------------------------------------------------------------
def test_bkt_correct_raises_and_wrong_lowers_mastery():
    assert bkt.update(0.4, True) > 0.4
    assert bkt.update(0.4, False) < bkt.update(0.4, True)


def test_bkt_stays_in_bounds():
    m = 0.5
    for _ in range(200):
        m = bkt.update(m, True)
    assert 0.01 <= m <= 0.99


def test_bkt_decay_and_threshold():
    assert bkt.effective_mastery(0.8, 0) == pytest.approx(0.8)
    assert bkt.effective_mastery(0.8, 30) < 0.8
    assert bkt.days_until_threshold(0.5, 0, 0.6) == 0
    assert bkt.days_until_threshold(0.9, 0, 0.6) > 0


def test_bkt_fit_is_close_to_true_likelihood():
    rng = np.random.default_rng(0)
    true = bkt.BktParams(0.3, 0.2, 0.15, 0.08)
    sequences = []
    for _ in range(300):
        known = rng.random() < true.p_init
        seq = []
        for _ in range(12):
            p_correct = (1 - true.p_slip) if known else true.p_guess
            seq.append(bool(rng.random() < p_correct))
            if not known and rng.random() < true.p_learn:
                known = True
        sequences.append(seq)

    params, ll, used, observations = bkt.fit(sequences)
    true_ll = bkt.loglik(sequences, true)
    assert used == 300 and observations == 3600
    assert ll >= true_ll - 0.02 * abs(true_ll)
    assert params.p_guess + params.p_slip < 1.0


def test_bkt_fit_needs_enough_data():
    with pytest.raises(ValueError):
        bkt.fit([[True, False]])


# ---- SM-2 ------------------------------------------------------------------------------------
def test_sm2_progression():
    first = sm2.schedule(0, 0, 2.5, True, 30)
    assert (first["repetitions"], first["intervalDays"]) == (1, 1)
    second = sm2.schedule(first["repetitions"], first["intervalDays"], first["easeFactor"], True, 30)
    assert second["intervalDays"] == 6
    third = sm2.schedule(second["repetitions"], second["intervalDays"], second["easeFactor"], True, 30)
    assert third["intervalDays"] > 6


def test_sm2_wrong_answer_resets():
    result = sm2.schedule(5, 30, 2.5, False, 20)
    assert result["repetitions"] == 0 and result["intervalDays"] == 1
    assert result["easeFactor"] >= 1.3


# ---- Recall model ----------------------------------------------------------------------------
@pytest.fixture(scope="module")
def loaded_model():
    recall.load_or_train()
    return recall.model_info()


def test_formula_decays_with_time():
    assert formula_recall(1, 0.7, 0.0, 0.5) > formula_recall(60, 0.7, 0.0, 0.5)


def test_model_predicts_lower_recall_after_long_gap(loaded_model):
    accuracies = [0.6, 0.7, 0.7, 0.8, 0.8]
    short = feature_vector(1, accuracies, 0.5, [0.1] * 5, [1] * 5)
    long = feature_vector(60, accuracies, 0.5, [0.1] * 5, [1] * 5)
    p_short, engine = recall.predict_recall(short)
    p_long, _ = recall.predict_recall(long)
    assert 0.0 <= p_long <= p_short <= 1.0
    assert p_short > p_long
    assert engine in ("xgboost-recall-v1", "formula-fallback")


def test_model_beats_a_coin_flip(loaded_model):
    if loaded_model["engine"] != "xgboost-recall-v1":
        pytest.skip("xgboost model not available")
    assert loaded_model["metrics"]["xgboost"]["auc"] > 0.65


# ---- HTTP API --------------------------------------------------------------------------------
def test_api_endpoints(monkeypatch):
    monkeypatch.setattr(config, "SERVICE_KEY", "")
    from app.main import app
    with TestClient(app) as client:
        assert client.get("/health").json()["ready"] is True

        analysis = client.post("/analyze", json={
            "skillName": "Java", "timestamps": ["2026-01-01"], "accuracies": [0.9, 0.7, 0.5],
            "difficulties": [1, 2, 3]}).json()
        assert analysis["status"] in ("DECAYING", "STABLE", "IMPROVING")
        assert 0.0 <= analysis["recallProbability"] <= 1.0

        updated = client.post("/bkt/update", json={"correct": True}).json()
        assert updated["mastery"] > 0.20

        decay = client.post("/bkt/decay", json={"items": [{"key": "t1", "mastery": 0.9, "daysSinceLast": 10}]}).json()
        assert decay["results"][0]["key"] == "t1"

        review = client.post("/review/schedule", json={"correct": True, "seconds": 20}).json()
        assert review["intervalDays"] == 1


def test_api_key_is_enforced(monkeypatch):
    monkeypatch.setattr(config, "SERVICE_KEY", "secret")
    from app.main import app
    with TestClient(app) as client:
        assert client.post("/bkt/update", json={"correct": True}).status_code == 401
        ok = client.post("/bkt/update", json={"correct": True}, headers={"X-Internal-Key": "secret"})
        assert ok.status_code == 200
        assert client.get("/health").status_code == 200
