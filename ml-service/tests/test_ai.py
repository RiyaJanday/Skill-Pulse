"""Tests for the learner-analytics and AI-coach phases: risk, segments, question quality, adaptive order, tutor."""
import json

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app import adaptive_order, config, learner_risk, question_quality, segments, tutor
from app.llm import LlmUnavailable


def _learner(**overrides):
    base = dict(daysSinceLast=0, attempts7d=10, attempts30d=40, accuracy7d=0.8, accuracy30d=0.8,
                activeDays14=10, streak=5, avgMastery=0.7, dueReviews=1, avgSeconds=30)
    base.update(overrides)
    return base


def _gone(**overrides):
    return _learner(daysSinceLast=20, attempts7d=0, activeDays14=0, streak=0, accuracy30d=0.3,
                    accuracy7d=0.0, **overrides)


# ---- Phase 3: dropout risk -------------------------------------------------------------------
def test_heuristic_ranks_disengaged_above_engaged():
    active = learner_risk.heuristic_risk(learner_risk.feature_vector(_learner()))
    gone = learner_risk.heuristic_risk(learner_risk.feature_vector(_gone()))
    assert 0.0 <= active < gone <= 1.0


def test_risk_levels_and_reasons():
    assert learner_risk.level(0.70) == "HIGH"
    assert learner_risk.level(0.50) == "MEDIUM"
    assert learner_risk.level(0.10) == "LOW"

    reasons = learner_risk.explain(_gone(), 0.9)
    assert 1 <= len(reasons) <= 3
    assert any("20 days" in r for r in reasons)
    assert learner_risk.explain(_learner(), 0.05) == ["Practice pattern looks healthy"]


def test_simulator_is_internally_consistent():
    X, y = learner_risk.generate(3000, seed=1)
    d = {name: X[:, i] for i, name in enumerate(learner_risk.FEATURES)}
    assert (d["attempts7d"] <= d["attempts30d"]).all()
    assert (d["activeDays14"] <= d["attempts30d"]).all()
    assert (d["activeDays14"] <= 14).all()
    assert ((d["accuracy7d"] >= 0) & (d["accuracy7d"] <= 1)).all()
    assert 0.05 < y.mean() < 0.95


def test_train_save_and_reload_risk_model(tmp_path, monkeypatch):
    pytest.importorskip("xgboost")
    pytest.importorskip("sklearn")
    monkeypatch.setattr(config, "MODEL_DIR", tmp_path)
    monkeypatch.setattr(config, "RISK_MODEL_PATH", tmp_path / "risk_xgb.json")
    monkeypatch.setattr(config, "RISK_METRICS_PATH", tmp_path / "risk_metrics.json")
    monkeypatch.setattr(learner_risk, "_model", None)
    monkeypatch.setattr(learner_risk, "_engine", learner_risk.HEURISTIC_ENGINE)

    metrics = learner_risk.train_and_save(n_samples=3000, seed=3)
    assert metrics["source"] == "synthetic"
    assert metrics["xgboost"]["auc"] > 0.70
    assert (tmp_path / "risk_xgb.json").exists()

    learner_risk.load_or_train()
    scores, engine = learner_risk.predict([learner_risk.feature_vector(_learner()),
                                           learner_risk.feature_vector(_gone())])
    assert engine == learner_risk.MODEL_ENGINE
    assert scores[1] > scores[0]
    assert learner_risk.model_info()["metrics"]["source"] == "synthetic"


# ---- Phase 4: segmentation -------------------------------------------------------------------
def _group(rng, n, prefix, attempts, active, days, accuracy, mastery):
    return [{"key": "%s%d" % (prefix, i),
             "attempts30d": max(0.0, rng.normal(attempts, 2)),
             "activeDays14": float(np.clip(rng.normal(active, 1), 0, 14)),
             "daysSinceLast": max(0.0, rng.normal(days, 0.5)),
             "accuracy30d": float(np.clip(rng.normal(accuracy, 0.04), 0, 1)),
             "avgMastery": float(np.clip(rng.normal(mastery, 0.04), 0, 1))} for i in range(n)]


def test_kmeans_recovers_four_named_groups():
    rng = np.random.default_rng(0)
    rows = (_group(rng, 25, "A", 60, 12, 0.3, 0.90, 0.85) +   # consistent achievers
            _group(rng, 25, "B", 60, 12, 0.3, 0.35, 0.30) +   # active but struggling
            _group(rng, 25, "C", 5, 1, 12, 0.90, 0.80) +      # capable but drifting
            _group(rng, 25, "D", 3, 1, 20, 0.30, 0.25))       # at-risk / disengaged
    out = segments.cluster(rows, k=4)

    assert out["k"] == 4
    assert {s["name"] for s in out["segments"]} == {
        "Consistent achievers", "Active but struggling", "Capable but drifting", "At-risk / disengaged"}
    for prefix in "ABCD":  # each true group lands in exactly one cluster
        assert len({out["assignments"][r["key"]] for r in rows if r["key"].startswith(prefix)}) == 1
    assert sum(s["size"] for s in out["segments"]) == len(rows)
    assert out["silhouette"] > 0.5


def test_segments_handle_tiny_and_empty_cohorts():
    assert segments.cluster([], 4)["k"] == 0
    rows = _group(np.random.default_rng(1), 3, "X", 10, 5, 1, 0.7, 0.5)
    out = segments.cluster(rows, 4)
    assert out["k"] == 1 and out["segments"][0]["name"] == "All learners"


# ---- Phase 5: question quality (Rasch) --------------------------------------------------------
def _simulate(n_users=80, n_questions=15, seed=0):
    """Learners answer every question. The LAST question has a planted wrong key: stronger learners fail it."""
    rng = np.random.default_rng(seed)
    theta = rng.normal(0, 1, n_users)
    b = np.linspace(-1.5, 1.5, n_questions)
    responses = []
    for u in range(n_users):
        for j in range(n_questions):
            if j == n_questions - 1:
                p = 1.0 / (1.0 + np.exp(theta[u]))
            else:
                p = 1.0 / (1.0 + np.exp(-(theta[u] - b[j])))
            responses.append({"questionId": j + 1, "userId": u + 1,
                              "correct": bool(rng.random() < p), "seconds": int(rng.integers(15, 60))})
    return responses, b


def test_rasch_recovers_difficulty_and_flags_wrong_key():
    responses, true_b = _simulate()
    labelled = {j + 1: "MEDIUM" for j in range(len(true_b))}
    out = question_quality.calibrate(responses, labelled)

    assert out["insufficientData"] is False
    assert out["summary"]["questions"] == 15 and out["summary"]["learners"] == 80
    rows = {r["questionId"]: r for r in out["questions"]}

    fitted = np.array([rows[j + 1]["difficultyLogit"] for j in range(14)])
    assert np.corrcoef(fitted, true_b[:14])[0, 1] > 0.85
    assert fitted[-1] > fitted[0]  # the hardest real question measures harder than the easiest

    assert "POSSIBLE_WRONG_KEY" in rows[15]["flags"]
    assert rows[15]["discrimination"] < -0.10
    assert all("POSSIBLE_WRONG_KEY" not in rows[j + 1]["flags"] for j in range(14))


def test_label_disagreement_is_flagged():
    responses, _ = _simulate(seed=2)
    labelled = {j + 1: "MEDIUM" for j in range(15)}
    labelled[14] = "EASY"  # question 14 is genuinely one of the hardest
    out = question_quality.calibrate(responses, labelled)
    row = next(r for r in out["questions"] if r["questionId"] == 14)
    assert row["suggestedDifficulty"] == "HARD"
    assert "LABEL_TOO_EASY" in row["flags"]


def test_quality_needs_enough_data():
    assert question_quality.calibrate([], {})["insufficientData"] is True
    few = [{"questionId": 1, "userId": u, "correct": True, "seconds": 10} for u in range(5)]
    out = question_quality.calibrate(few, {1: "EASY"})
    assert out["insufficientData"] is True and "Need at least" in out["message"]


# ---- Phase 6: adaptive ordering ---------------------------------------------------------------
def test_strong_learners_get_harder_questions_first_and_weak_learners_easier():
    qs = [{"id": 1, "difficulty": "EASY"}, {"id": 2, "difficulty": "MEDIUM"}, {"id": 3, "difficulty": "HARD"}]
    assert adaptive_order.order(0.95, qs)["order"][0] == 3
    assert adaptive_order.order(0.05, qs)["order"][0] == 1


def test_due_questions_are_preferred_when_otherwise_equal():
    qs = [{"id": 1, "difficulty": "MEDIUM"}, {"id": 2, "difficulty": "MEDIUM", "due": True}]
    assert adaptive_order.order(0.5, qs)["order"] == [2, 1]


def test_calibrated_difficulty_overrides_the_label():
    labels_only = [{"id": 1, "difficulty": "EASY"}, {"id": 2, "difficulty": "HARD"}]
    calibrated = [{"id": 1, "difficulty": "EASY", "difficultyLogit": 2.0},
                  {"id": 2, "difficulty": "HARD", "difficultyLogit": -2.0}]
    assert adaptive_order.order(0.5, labels_only)["order"] == [1, 2]
    assert adaptive_order.order(0.5, calibrated)["order"] == [2, 1]


def test_predictions_are_probabilities_and_order_is_complete():
    qs = [{"id": i, "difficulty": d} for i, d in enumerate(["EASY", "MEDIUM", "HARD", "MEDIUM"], start=1)]
    out = adaptive_order.order(0.4, qs)
    assert sorted(out["order"]) == [1, 2, 3, 4]
    assert all(0.0 < p["pSuccess"] < 1.0 for p in out["predictions"])
    assert adaptive_order.order(0.4, [])["order"] == []


# ---- HTTP API ---------------------------------------------------------------------------------
@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "SERVICE_KEY", "")
    monkeypatch.setattr(config, "AUTO_TRAIN", False)  # keep API tests fast; the heuristic engine is enough here
    monkeypatch.setattr(config, "MODEL_DIR", tmp_path)
    monkeypatch.setattr(config, "RISK_MODEL_PATH", tmp_path / "risk_xgb.json")
    monkeypatch.setattr(config, "RISK_METRICS_PATH", tmp_path / "risk_metrics.json")
    monkeypatch.setattr(learner_risk, "_model", None)
    monkeypatch.setattr(learner_risk, "_engine", learner_risk.HEURISTIC_ENGINE)
    from app.main import app
    with TestClient(app) as c:
        yield c


def test_api_risk_and_segments(client):
    r = client.post("/risk/predict", json={"learners": [dict(key="ok", **_learner()), dict(key="gone", **_gone())]})
    assert r.status_code == 200
    body = r.json()
    scores = {x["key"]: x for x in body["results"]}
    assert scores["gone"]["riskScore"] > scores["ok"]["riskScore"]
    assert scores["gone"]["level"] in ("MEDIUM", "HIGH") and scores["gone"]["reasons"]
    assert body["engine"] in (learner_risk.HEURISTIC_ENGINE, learner_risk.MODEL_ENGINE)
    assert client.get("/risk/info").json()["engine"] == body["engine"]

    rng = np.random.default_rng(0)
    rows = _group(rng, 10, "A", 60, 12, 0.3, 0.9, 0.85) + _group(rng, 10, "D", 3, 1, 20, 0.3, 0.25)
    seg = client.post("/segments/cluster", json={"learners": rows, "k": 2}).json()
    assert seg["k"] == 2 and len(seg["assignments"]) == 20


def test_api_quality_and_adaptive_order(client):
    responses, true_b = _simulate(n_users=40)
    out = client.post("/questions/quality", json={
        "responses": responses, "questions": [{"id": j + 1, "difficulty": "MEDIUM"} for j in range(15)]}).json()
    assert out["insufficientData"] is False and out["summary"]["questions"] == 15

    order = client.post("/adaptive/order", json={
        "mastery": 0.6, "questions": [{"id": 5, "difficulty": "EASY"}, {"id": 6, "difficulty": "HARD"}]}).json()
    assert sorted(order["order"]) == [5, 6] and order["engine"] == "rasch-order-v1"


def test_api_key_is_enforced_on_ai_routes(client, monkeypatch):
    monkeypatch.setattr(config, "SERVICE_KEY", "secret")
    payload = {"learners": [dict(key="x", **_learner())]}
    assert client.post("/risk/predict", json=payload).status_code == 401
    assert client.post("/risk/predict", json=payload, headers={"X-Internal-Key": "secret"}).status_code == 200


# ---- tutor endpoints with a fake LLM ------------------------------------------------------------
def _fake_llm(monkeypatch, reply, seen=None):
    def fake_chat(messages, json_mode=False, temperature=0.3, max_tokens=3000):
        if seen is not None:
            seen.append(messages)
        if isinstance(reply, Exception):
            raise reply
        return (reply if isinstance(reply, str) else json.dumps(reply)), "fake-llm"
    monkeypatch.setattr(tutor.llm, "chat", fake_chat)


def test_tutor_chat_sends_context_and_caps_history(client, monkeypatch):
    seen = []
    _fake_llm(monkeypatch, "Try spaced repetition.", seen)
    history = [{"role": "user" if i % 2 == 0 else "assistant", "content": "turn %d" % i} for i in range(12)]
    r = client.post("/tutor/chat", json={"message": "How do I study?", "history": history,
                                          "context": {"learnerName": "Riya", "dueReviews": 3}})
    assert r.status_code == 200 and r.json() == {"reply": "Try spaced repetition.", "engine": "fake-llm"}
    messages = seen[0]
    assert messages[0]["role"] == "system" and "<learner_context>" in messages[0]["content"]
    assert "Riya" in messages[0]["content"]
    assert len(messages) == 1 + tutor.MAX_HISTORY_TURNS + 1  # system + capped history + new message


def test_tutor_returns_503_when_llm_is_down(client, monkeypatch):
    _fake_llm(monkeypatch, LlmUnavailable("no providers"))
    assert client.post("/tutor/chat", json={"message": "hi"}).status_code == 503


def test_tutor_explain_validates_and_returns_structured_answer(client, monkeypatch):
    _fake_llm(monkeypatch, {"whyWrong": "B confuses X with Y.", "whyCorrect": "C is right because Z.",
                            "memoryTip": "Think Z first.", "tryNext": "Solve one more."})
    body = {"prompt": "Which one?", "options": ["a", "b", "c", "d"], "selectedIndex": 1, "correctIndex": 2}
    r = client.post("/tutor/explain", json=body)
    assert r.status_code == 200
    assert r.json()["whyCorrect"].startswith("C is right") and r.json()["engine"] == "fake-llm"

    assert client.post("/tutor/explain", json=dict(body, correctIndex=9)).status_code == 503  # bad index rejected


def test_generated_questions_are_validated_and_deduplicated(client, monkeypatch):
    good = {"prompt": "What does a stack follow?", "options": ["FIFO", "LIFO", "Random", "Sorted"],
            "correctIndex": 1, "explanation": "A stack is last in, first out.", "difficulty": "EASY"}
    dup_options = dict(good, prompt="A different stack question?", options=["A", "A", "B", "C"])
    repeated = dict(good, prompt="Already in the bank?")
    _fake_llm(monkeypatch, {"questions": [good, dup_options, repeated]})
    r = client.post("/tutor/generate-questions", json={
        "subject": "Data Structures", "topic": "Stacks", "difficulty": "EASY", "count": 3,
        "existingPrompts": ["Already in the bank?"]})
    assert r.status_code == 200
    questions = r.json()["questions"]
    assert [q["prompt"] for q in questions] == ["What does a stack follow?"]


def test_nudge_email_is_signed(client, monkeypatch):
    _fake_llm(monkeypatch, {"subject": "A small step back in",
                            "body": "Hi Riya, you have a few reviews waiting. Ten minutes today would be a great restart."})
    r = client.post("/coach/nudge", json={"name": "Riya", "riskLevel": "HIGH", "reasons": ["No practice for 6 days"],
                                           "weakTopics": ["Stacks"], "dueReviews": 4, "streak": 0})
    assert r.status_code == 200
    assert r.json()["body"].rstrip().endswith("- SkillPulse")
