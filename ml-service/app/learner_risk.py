"""Learner drop-out risk: an XGBoost model with a transparent rule-based fallback.

Question answered: how likely is this learner to STOP practising (no practice at all in the next 14 days)?

There is no large real dataset yet, so the model is trained on a simulator (see `generate`). The simulator
has a hidden engagement level and a hidden "fading" tendency; the features below are noisy views of them and
the label is drawn from them. Numbers reported in models/risk_metrics.json therefore describe how well the
model recovers the simulator, NOT how well it predicts real students. Retrain on exported real activity
once enough exists (`python train.py --risk`).
"""
import json
import logging
from typing import Dict, List, Sequence, Tuple

import numpy as np

from . import config

log = logging.getLogger("skillpulse.risk")

FEATURES = ["daysSinceLast", "attempts7d", "attempts30d", "accuracy7d", "accuracy30d",
            "activeDays14", "streak", "avgMastery", "dueReviews", "avgSeconds"]

HEURISTIC_ENGINE = "risk-heuristic-v1"
MODEL_ENGINE = "xgboost-risk-v1"

_model = None
_engine = HEURISTIC_ENGINE


def _sigmoid(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -30.0, 30.0)))


def feature_vector(features: Dict) -> List[float]:
    return [float(features.get(name) or 0.0) for name in FEATURES]


def heuristic_risk(vec: Sequence[float]) -> float:
    """Simple, explainable score used when no trained model is available."""
    days, a7, a30, acc30, active14 = vec[0], vec[1], vec[2], vec[4], vec[5]
    struggle = (0.5 - acc30) if a30 > 0 else 0.0
    z = -0.6 + 0.10 * min(days, 30.0) - 0.12 * min(active14, 14.0) - 0.03 * min(a7, 20.0) + 0.8 * struggle
    return float(_sigmoid(z))


def level(score: float) -> str:
    if score >= 0.65:
        return "HIGH"
    if score >= 0.40:
        return "MEDIUM"
    return "LOW"


def explain(features: Dict, score: float) -> List[str]:
    """Human-readable warning signs read straight from the learner's numbers (at most three)."""
    days = int(features.get("daysSinceLast") or 0)
    a7 = float(features.get("attempts7d") or 0)
    a30 = float(features.get("attempts30d") or 0)
    acc7 = float(features.get("accuracy7d") or 0)
    active14 = int(features.get("activeDays14") or 0)
    due = int(features.get("dueReviews") or 0)

    reasons: List[str] = []
    if days >= 5:
        reasons.append("No practice for %d days" % days)
    elif a7 == 0 and a30 > 0:
        reasons.append("No practice in the last 7 days")
    if a7 > 0 and a30 >= 6 and a7 < (a30 / 30.0 * 7.0) * 0.5:
        reasons.append("Practice volume is well below last month's pace")
    if a7 >= 3 and acc7 < 0.5:
        reasons.append("Recent accuracy is below 50%")
    if due >= 8:
        reasons.append("%d review questions are overdue" % due)
    if a30 > 0 and days < 5 and active14 <= 2:
        reasons.append("Practised on only %d of the last 14 days" % active14)
    if not reasons:
        reasons.append("Practice pattern looks healthy" if score < 0.40
                       else "Overall practice pattern is trending down")
    return reasons[:3]


def generate(n_samples: int = 8000, seed: int = 42) -> Tuple[np.ndarray, np.ndarray]:
    """Simulate learners. Feature constraints are kept consistent (e.g. attempts7d <= attempts30d)."""
    rng = np.random.default_rng(seed)
    n = n_samples
    engagement = rng.normal(0.0, 1.0, n)
    ability = rng.normal(0.0, 1.0, n)
    fade = rng.beta(2.0, 5.0, n)  # 0 = steady, 1 = fading fast

    rate = np.exp(-0.7 + 0.8 * engagement)  # attempts per day
    attempts30 = rng.poisson(np.clip(rate * 30.0, 0, 300))
    attempts7 = rng.poisson(np.clip(rate * 7.0 * (1.0 - 0.8 * fade), 0, 100))
    active14 = rng.binomial(14, np.clip(_sigmoid(0.2 + 0.9 * engagement - 1.4 * fade), 0.01, 0.99))
    days_since = np.floor(rng.exponential(0.8 + 5.0 * fade + 1.5 * np.exp(-0.8 * engagement))).clip(0, 60)

    attempts30 = np.where(days_since > 30, 0, attempts30)
    active14 = np.where(days_since > 14, 0, active14)
    attempts7 = np.where(days_since > 7, 0, attempts7)
    attempts30 = np.maximum(attempts30, attempts7)
    active14 = np.where(attempts7 > 0, np.maximum(active14, 1), active14)
    active14 = np.minimum(active14, attempts30)
    streak = np.where(days_since <= 1, np.floor(rng.random(n) * (active14 + 1)), 0.0)

    base_acc = _sigmoid(1.0 * ability + 0.4)
    acc30 = np.where(attempts30 > 0, np.clip(base_acc + rng.normal(0, 0.08, n), 0, 1), 0.0)
    acc7 = np.where(attempts7 > 0, np.clip(base_acc + rng.normal(0, 0.15, n), 0, 1), 0.0)
    mastery = np.clip(0.2 + 0.6 * base_acc + rng.normal(0, 0.06, n), 0.05, 0.95)
    due = np.clip(rng.poisson(1.0 + 4.0 * fade + 0.5 * np.maximum(0.0, -engagement)), 0, 60)
    seconds = np.clip(rng.normal(40 - 8 * ability, 12), 5, 200)

    struggle = np.where(attempts30 > 0, 0.5 - acc30, 0.0)
    z = -1.5 - 1.0 * engagement + 0.10 * days_since + 1.3 * fade + 0.9 * struggle
    labels = (rng.random(n) < _sigmoid(z)).astype(int)

    X = np.column_stack([days_since, attempts7, attempts30, acc7, acc30, active14, streak,
                         mastery, due, seconds]).astype(float)
    return X, labels


def train_and_save(n_samples: int = 8000, seed: int = 42) -> Dict:
    from sklearn.metrics import brier_score_loss, roc_auc_score
    from sklearn.model_selection import train_test_split
    from xgboost import XGBClassifier

    X, y = generate(n_samples, seed)
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=seed, stratify=y)
    model = XGBClassifier(n_estimators=160, max_depth=3, learning_rate=0.07, subsample=0.9,
                          colsample_bytree=0.9, eval_metric="logloss", n_jobs=2, random_state=seed)
    model.fit(X_train, y_train)

    proba = model.predict_proba(X_test)[:, 1]
    baseline = np.array([heuristic_risk(row) for row in X_test])
    metrics = {
        "source": "synthetic",
        "trainSamples": int(len(X_train)),
        "testSamples": int(len(X_test)),
        "positiveRate": round(float(y.mean()), 4),
        "xgboost": {"auc": round(float(roc_auc_score(y_test, proba)), 4),
                    "brier": round(float(brier_score_loss(y_test, proba)), 4)},
        "heuristic": {"auc": round(float(roc_auc_score(y_test, baseline)), 4),
                      "brier": round(float(brier_score_loss(y_test, baseline)), 4)},
        "featureImportance": {name: round(float(score), 4)
                              for name, score in zip(FEATURES, model.feature_importances_)},
    }
    config.MODEL_DIR.mkdir(parents=True, exist_ok=True)
    model.save_model(str(config.RISK_MODEL_PATH))
    config.RISK_METRICS_PATH.write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    log.info("Risk model trained: %s", metrics["xgboost"])
    return metrics


def load_or_train() -> None:
    """Load the saved model; train one on synthetic data if none exists and AUTO_TRAIN is on."""
    global _model, _engine
    try:
        from xgboost import XGBClassifier
        if not config.RISK_MODEL_PATH.exists() and config.AUTO_TRAIN:
            log.info("No risk model found; training one on synthetic data...")
            train_and_save()
        if config.RISK_MODEL_PATH.exists():
            model = XGBClassifier()
            model.load_model(str(config.RISK_MODEL_PATH))
            _model = model
            _engine = MODEL_ENGINE
    except Exception as ex:  # never stop the service from starting
        log.warning("Risk model unavailable, using heuristic: %s", ex)
        _model = None
        _engine = HEURISTIC_ENGINE


def predict(vectors: Sequence[Sequence[float]]) -> Tuple[List[float], str]:
    if not vectors:
        return [], _engine
    if _model is not None:
        proba = _model.predict_proba(np.asarray(vectors, dtype=float))[:, 1]
        return [float(p) for p in proba], _engine
    return [heuristic_risk(v) for v in vectors], HEURISTIC_ENGINE


def model_info() -> Dict:
    metrics = None
    if config.RISK_METRICS_PATH.exists():
        try:
            metrics = json.loads(config.RISK_METRICS_PATH.read_text(encoding="utf-8"))
        except ValueError:
            metrics = None
    return {"engine": _engine, "metrics": metrics}
