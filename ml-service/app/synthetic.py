"""Synthetic learner simulator used to train the recall model.

There is no large real dataset yet, so learners are simulated from a latent ability and an
Ebbinghaus-style forgetting curve. The label is whether the learner answers correctly on their
next attempt after `daysGap` days. Retrain on real exported attempts with `python train.py --csv`.
"""
from typing import Tuple

import numpy as np

from .features import feature_vector

GUESS = 0.20
SLIP = 0.08


def _sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


def generate(n_samples: int = 20000, seed: int = 42) -> Tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(seed)
    rows = []
    labels = []
    for _ in range(n_samples):
        ability = rng.normal(0.0, 1.0)
        difficulty_norm = float(rng.uniform(0.0, 1.0))
        n_sessions = int(rng.integers(3, 41))

        idx = np.arange(n_sessions)
        p_correct_session = _sigmoid(1.1 * ability - 1.4 * (difficulty_norm - 0.5) + 0.04 * idx + 0.2)
        accuracies = rng.binomial(5, p_correct_session) / 5.0
        hints = rng.beta(1.5, 6.0, size=n_sessions) * (1.0 - 0.3 * _sigmoid(ability))
        completion = (rng.random(n_sessions) < 0.6 + 0.35 * _sigmoid(ability)).astype(int)

        days_gap = int(min(90, rng.exponential(10.0)))
        vec = feature_vector(days_gap, accuracies.tolist(), difficulty_norm, hints.tolist(), completion.tolist())
        recent, hints_mean = vec[1], vec[6]

        # Latent memory strength (days): better ability/recent accuracy -> slower forgetting.
        strength = float(np.exp(1.6 + 0.6 * ability + 0.02 * n_sessions
                                - 0.5 * difficulty_norm + 0.9 * recent - 0.8 * hints_mean))
        recall = float(np.exp(-days_gap / strength))
        p_next_correct = recall * (1.0 - SLIP) + (1.0 - recall) * GUESS

        rows.append(vec)
        labels.append(1 if rng.random() < p_next_correct else 0)

    return np.asarray(rows, dtype=float), np.asarray(labels, dtype=int)
