"""Turn exported real practice attempts into training rows for the recall model.

The Java backend exports one row per answer (admin endpoint /api/admin/ai/export-attempts.csv) with only
anonymous numeric ids:

    userId,topicId,questionId,difficulty,correct,seconds,attemptedAt

For every learner and topic, the answers are put in time order. Each answer from the third one onwards becomes
one training example:

    features = the same feature_vector() the live API uses, computed from the answers BEFORE it
    daysGap  = days between the previous answer and this one
    label    = 1 if this answer was correct, else 0

So the model learns the same question the simulator asked: "after this history and this gap, is the next
answer correct?". Hints and completion are not recorded for practice answers, so they are left neutral
(hints 0, completion 1), exactly as the live API sends them.
"""
import csv
from collections import defaultdict
from datetime import date
from typing import Dict, List, Tuple

import numpy as np

from .features import feature_vector, normalize_difficulty

REQUIRED_COLUMNS = ["userId", "topicId", "questionId", "difficulty", "correct", "seconds", "attemptedAt"]
DIFFICULTY_VALUE = {"EASY": 1.0, "MEDIUM": 2.0, "HARD": 3.0}
HISTORY_WINDOW = 200  # only the latest answers feed the features, to keep long histories cheap


def _parse_bool(value: str) -> bool:
    return str(value).strip().lower() in ("1", "true", "t", "yes")


def load_attempts(path: str) -> List[Dict]:
    """Read the exported CSV. Raises ValueError with a clear message when columns are missing."""
    rows: List[Dict] = []
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        missing = [c for c in REQUIRED_COLUMNS if c not in (reader.fieldnames or [])]
        if missing:
            raise ValueError("Attempts CSV is missing columns: " + ", ".join(missing))
        for line in reader:
            stamp = str(line["attemptedAt"]).strip()
            try:
                day = date.fromisoformat(stamp[:10])
            except ValueError:
                continue  # skip an unreadable row rather than failing the whole file
            rows.append({
                "user": str(line["userId"]).strip(),
                "topic": str(line["topicId"]).strip(),
                "difficulty": DIFFICULTY_VALUE.get(str(line["difficulty"]).strip().upper(), 2.0),
                "correct": _parse_bool(line["correct"]),
                "stamp": stamp.rstrip("Z"),  # ISO text sorts in time order once the trailing Z is gone
                "day": day,
            })
    return rows


def build_dataset(rows: List[Dict], min_history: int = 2) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Returns (X, y, groups). groups is the learner id of each example, used to split train/test by learner."""
    sequences: Dict[Tuple[str, str], List[Dict]] = defaultdict(list)
    for row in rows:
        sequences[(row["user"], row["topic"])].append(row)

    features: List[List[float]] = []
    labels: List[int] = []
    groups: List[str] = []
    for (user, _topic), seq in sequences.items():
        seq.sort(key=lambda r: r["stamp"])
        for i in range(min_history, len(seq)):
            history = seq[max(0, i - HISTORY_WINDOW):i]
            gap = max(0, (seq[i]["day"] - seq[i - 1]["day"]).days)
            accuracy = [1.0 if r["correct"] else 0.0 for r in history]
            difficulty = normalize_difficulty([r["difficulty"] for r in history])
            features.append(feature_vector(min(gap, 90), accuracy, difficulty, [], []))
            labels.append(1 if seq[i]["correct"] else 0)
            groups.append(user)

    return (np.asarray(features, dtype=float).reshape(-1, 8),
            np.asarray(labels, dtype=int),
            np.asarray(groups))


def load_dataset(path: str) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    return build_dataset(load_attempts(path))
