"""Recall/decay prediction: XGBoost model with the old heuristic as a safety fallback."""
import json
import logging
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from . import config
from .features import (clamp, days_since_last, feature_vector, formula_recall, normalize_difficulty)

log = logging.getLogger("skillpulse.recall")

_model = None
_engine = "formula-fallback"


def load_or_train() -> None:
    """Load the saved model; train one on synthetic data if none exists and AUTO_TRAIN is on."""
    global _model, _engine
    try:
        from xgboost import XGBClassifier
        if not config.RECALL_MODEL_PATH.exists() and config.AUTO_TRAIN:
            log.info("No recall model found; training one on synthetic data...")
            from .training import train_and_save
            train_and_save()
        if config.RECALL_MODEL_PATH.exists():
            model = XGBClassifier()
            model.load_model(str(config.RECALL_MODEL_PATH))
            _model = model
            _engine = "xgboost-recall-v1"
    except Exception as ex:  # never stop the service from starting
        log.warning("Recall model unavailable, using formula fallback: %s", ex)
        _model = None
        _engine = "formula-fallback"


def predict_recall(vec: Sequence[float]) -> Tuple[float, str]:
    """Probability that the learner answers correctly on their next attempt."""
    if _model is not None:
        proba = _model.predict_proba(np.asarray([vec], dtype=float))[0][1]
        return float(proba), _engine
    return formula_recall(vec[0], vec[1], vec[3], vec[4]), "formula-fallback"


def model_info() -> Dict:
    metrics: Optional[Dict] = None
    if config.RECALL_METRICS_PATH.exists():
        try:
            metrics = json.loads(config.RECALL_METRICS_PATH.read_text(encoding="utf-8"))
        except ValueError:
            metrics = None
    return {"engine": _engine, "metrics": metrics}


def _priority(status: str, days_gap: int, recent: float) -> str:
    if status == "DECAYING":
        return "HIGH"
    if days_gap > 7 or recent < 0.70:
        return "MEDIUM"
    return "LOW"


def _actions(status: str, days_gap: int, recent: float) -> List[str]:
    actions: List[str] = []
    if status == "DECAYING":
        actions.append("Schedule immediate refresher practice.")
        if days_gap > 14:
            actions.append("Reduce the long practice gap with daily micro-sessions.")
        if recent < 0.60:
            actions.append("Restart with easier tasks before increasing difficulty.")
    elif status == "IMPROVING":
        actions.append("Continue the current practice pattern.")
        actions.append("Increase difficulty slightly to lock in mastery.")
    else:
        actions.append("Maintain light review practice this week.")
    return actions


def analyze(skill_name: Optional[str], timestamps: Sequence[str], accuracies: Sequence[float],
            difficulties: Sequence[float], hints: Sequence[float], completion: Sequence[int]) -> Dict:
    days_gap = days_since_last(timestamps)
    vec = feature_vector(days_gap, accuracies, normalize_difficulty(difficulties), hints, completion)
    recall, engine = predict_recall(vec)
    recent, overall, trend = vec[1], vec[2], vec[3]

    if recall < 0.50 or (recent < 0.60 and trend < -0.03):
        status = "DECAYING"
        confidence = clamp(0.70 + abs(trend) + (0.60 - recent), 0.70, 0.97)
    elif trend > 0.04 and recent > 0.75:
        status = "IMPROVING"
        confidence = clamp(0.68 + trend + recent / 10.0, 0.65, 0.95)
    else:
        status = "STABLE"
        confidence = clamp(0.62 + overall / 5.0, 0.60, 0.90)

    return {
        "skillName": skill_name or "Skill",
        "status": status,
        "confidence": round(confidence, 3),
        "recallProbability": round(recall, 3),
        "priority": _priority(status, days_gap, recent),
        "actions": _actions(status, days_gap, recent),
        "explanation": (
            "Predicted chance of answering correctly next time is %d%%. Recent accuracy is %d%%, "
            "overall accuracy is %d%%, trend is %.3f, and last practice was %d day(s) ago."
            % (round(recall * 100), round(recent * 100), round(overall * 100), trend, days_gap)),
        "engine": engine,
    }
