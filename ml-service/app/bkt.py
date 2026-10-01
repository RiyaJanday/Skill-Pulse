"""Bayesian Knowledge Tracing: per-answer mastery updates, forgetting, and per-topic parameter fitting."""
import math
from dataclasses import dataclass
from typing import Dict, List, Sequence, Tuple

import numpy as np

DAILY_RETENTION = 0.995


@dataclass(frozen=True)
class BktParams:
    p_init: float = 0.20
    p_learn: float = 0.12
    p_guess: float = 0.20
    p_slip: float = 0.10


def _clamp_mastery(value: float) -> float:
    return max(0.01, min(0.99, value))


def effective_mastery(mastery: float, days_since_last: int) -> float:
    """Mastery after forgetting: decays by 0.5% per idle day."""
    days = max(0, days_since_last)
    return _clamp_mastery(mastery * (DAILY_RETENTION ** days))


def days_until_threshold(mastery: float, days_since_last: int, threshold: float = 0.60) -> int:
    p = effective_mastery(mastery, days_since_last)
    if p <= threshold:
        return 0
    return max(0, int(math.floor(math.log(threshold / p) / math.log(DAILY_RETENTION))))


def update(mastery: float, correct: bool, params: BktParams = BktParams(), difficulty: str = "MEDIUM",
           seconds: int = 0, days_since_last: int = 0) -> float:
    """One BKT step. Harder questions are harder to guess and easier to slip on; slow answers add slip."""
    p = mastery
    if days_since_last > 0:
        p = max(0.05, p * (DAILY_RETENTION ** days_since_last))

    shift = {"HARD": 0.05, "MEDIUM": 0.02}.get(str(difficulty).upper(), 0.0)
    guess = max(0.05, params.p_guess - shift)
    slip = min(0.30, params.p_slip + shift)
    if seconds > 180:
        slip = min(0.35, slip + 0.03)

    if correct:
        posterior = (p * (1 - slip)) / (p * (1 - slip) + (1 - p) * guess)
    else:
        posterior = (p * slip) / (p * slip + (1 - p) * (1 - guess))
    return _clamp_mastery(posterior + (1 - posterior) * params.p_learn)


def replay(attempts: Sequence[Dict], params: BktParams = BktParams()) -> float:
    """Run a learner's whole answer history (oldest first) through BKT and return final mastery."""
    mastery = params.p_init
    for a in attempts:
        mastery = update(mastery, bool(a.get("correct")), params, a.get("difficulty", "MEDIUM"),
                         int(a.get("seconds", 0)), int(a.get("daysSinceLast", 0)))
    return mastery


def _grid_loglik(seqs: List[List[bool]], p_init, p_learn, p_guess, p_slip) -> np.ndarray:
    """Log-likelihood of all sequences for every parameter combination at once (vectorised)."""
    total = np.zeros_like(p_init)
    for seq in seqs:
        p = p_init.copy()
        for correct in seq:
            p_correct = np.maximum(p * (1 - p_slip) + (1 - p) * p_guess, 1e-12)
            if correct:
                total += np.log(p_correct)
                posterior = p * (1 - p_slip) / p_correct
            else:
                p_wrong = np.maximum(1 - p_correct, 1e-12)
                total += np.log(p_wrong)
                posterior = p * p_slip / p_wrong
            p = posterior + (1 - posterior) * p_learn
    return total


def loglik(seqs: Sequence[Sequence[bool]], params: BktParams) -> float:
    arrays = [np.array([v]) for v in (params.p_init, params.p_learn, params.p_guess, params.p_slip)]
    return float(_grid_loglik([[bool(x) for x in s] for s in seqs], *arrays)[0])


def fit(sequences: Sequence[Sequence[bool]], max_sequences: int = 1000) -> Tuple[BktParams, float, int, int]:
    """Fit BKT parameters for one topic by maximum likelihood (coarse grid, then a refined grid).

    Guess is bounded at 0.35 and slip at 0.30 so the model stays identifiable (guess + slip < 1).
    Returns (params, logLikelihood, sequencesUsed, observations).
    """
    seqs = [[bool(x) for x in s] for s in sequences if len(s) >= 2][:max_sequences]
    if len(seqs) < 5:
        raise ValueError("Need at least 5 answer sequences with 2+ answers each to fit a topic.")

    bounds_lo = [0.05, 0.02, 0.05, 0.02]
    bounds_hi = [0.60, 0.40, 0.35, 0.30]
    lo, hi = list(bounds_lo), list(bounds_hi)
    best: List[float] = []
    best_ll = -math.inf

    for stage in range(2):
        points = 8 if stage == 0 else 7
        axes = [np.linspace(lo[k], hi[k], points) for k in range(4)]
        flat = [m.ravel() for m in np.meshgrid(*axes, indexing="ij")]
        ll = _grid_loglik(seqs, *flat)
        i = int(np.argmax(ll))
        best = [float(f[i]) for f in flat]
        best_ll = float(ll[i])
        steps = [(hi[k] - lo[k]) / (points - 1) for k in range(4)]
        lo = [max(bounds_lo[k], best[k] - steps[k]) for k in range(4)]
        hi = [min(bounds_hi[k], best[k] + steps[k]) for k in range(4)]

    params = BktParams(*[round(v, 4) for v in best])
    return params, round(best_ll, 3), len(seqs), sum(len(s) for s in seqs)
