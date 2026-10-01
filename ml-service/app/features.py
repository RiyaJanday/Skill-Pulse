"""Feature engineering shared by the synthetic data generator, training and the live API."""
import math
from datetime import date
from typing import Iterable, List, Optional, Sequence

import numpy as np

# Order matters: the trained model expects features in exactly this order.
FEATURES = [
    "daysGap",
    "recentAccuracy",
    "overallAccuracy",
    "trend",
    "difficulty",
    "attempts",
    "hintsMean",
    "completionRate",
]


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def mean(values: Iterable[Optional[float]]) -> float:
    vals = [float(v) for v in values if v is not None]
    return sum(vals) / len(vals) if vals else 0.0


def slope(values: Sequence[float]) -> float:
    """Least-squares slope of the values against their index."""
    n = len(values)
    if n < 2:
        return 0.0
    x = np.arange(n, dtype=float)
    y = np.asarray(values, dtype=float)
    x_centered = x - x.mean()
    denom = float((x_centered ** 2).sum())
    return float((x_centered * (y - y.mean())).sum() / denom) if denom else 0.0


def normalize_difficulty(values: Iterable[Optional[float]]) -> float:
    """Difficulty arrives on a 1-3 scale (EASY=1, MEDIUM=2, HARD=3); returns 0-1."""
    vals = [float(v) for v in values if v is not None]
    if not vals:
        return 0.5
    return clamp((mean(vals) - 1.0) / 2.0, 0.0, 1.0)


def days_since_last(timestamps: Sequence[str], today: Optional[date] = None) -> int:
    today = today or date.today()
    parsed = []
    for stamp in timestamps or []:
        try:
            parsed.append(date.fromisoformat(str(stamp)[:10]))
        except ValueError:
            continue
    if not parsed:
        return 0
    return max(0, (today - max(parsed)).days)


def feature_vector(days_gap: float, accuracies: Sequence[float], difficulty_norm: float,
                   hints: Sequence[float], completion: Sequence[int]) -> List[float]:
    acc = [float(a) for a in accuracies if a is not None]
    if acc:
        recent, overall, trend = mean(acc[-5:]), mean(acc), slope(acc)
    else:
        recent, overall, trend = 0.5, 0.5, 0.0
    return [
        float(days_gap),
        recent,
        overall,
        trend,
        float(difficulty_norm),
        float(len(acc)),
        mean(hints),
        mean(completion) if completion else 1.0,
    ]


def formula_recall(days_gap: float, recent: float, trend: float, difficulty_norm: float) -> float:
    """The original hand-tuned forgetting-curve heuristic (kept as fallback and as a baseline)."""
    difficulty = 1.0 + 2.0 * difficulty_norm
    half_life = math.exp(0.4 * recent + 0.3 * max(0.0, trend) - 0.15 * max(1.0, difficulty))
    half_life = clamp(half_life * 12.0, 0.5, 90.0)
    return clamp(2.0 ** (-days_gap / half_life), 0.0, 1.0)
