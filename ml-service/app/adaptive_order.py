"""Adaptive question ordering.

The learner's topic mastery (from BKT) is turned into the probability of answering a typical question
correctly, using the same guess/slip values as BKT, and then into an ability on the logit scale. For each
question a Rasch model gives P(success) = sigmoid(ability - difficulty). Questions are ordered so the ones
closest to a productive-struggle target (default 70% chance of success) come first; questions that are due
for spaced-repetition review get a small bonus. The difficulty is the calibrated logit when the admin
analytics have one, otherwise the EASY / MEDIUM / HARD label.
"""
import math
from typing import Dict, List

SLIP = 0.10
GUESS = 0.20
DIFFICULTY_LOGIT = {"EASY": -1.0, "MEDIUM": 0.0, "HARD": 1.0}
DUE_BONUS = 0.10


def _logit(p: float) -> float:
    p = min(max(p, 1e-3), 1 - 1e-3)
    return math.log(p / (1 - p))


def ability_from_mastery(mastery: float) -> float:
    m = min(max(float(mastery), 0.01), 0.99)
    return _logit(m * (1 - SLIP) + (1 - m) * GUESS)


def order(mastery: float, questions: List[Dict], target: float = 0.70) -> Dict:
    theta = ability_from_mastery(mastery)
    scored = []
    for q in questions:
        b = q.get("difficultyLogit")
        if b is None:
            b = DIFFICULTY_LOGIT.get(str(q.get("difficulty") or "MEDIUM").upper(), 0.0)
        p = 1.0 / (1.0 + math.exp(-(theta - float(b))))
        score = abs(p - target) - (DUE_BONUS if q.get("due") else 0.0)
        scored.append((score, int(q["id"]), p))
    scored.sort(key=lambda item: (item[0], item[1]))
    return {
        "order": [item[1] for item in scored],
        "predictions": [{"id": item[1], "pSuccess": round(item[2], 3)} for item in scored],
        "engine": "rasch-order-v1",
    }
