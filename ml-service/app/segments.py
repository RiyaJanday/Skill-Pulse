"""Learner segmentation: k-means (numpy only) on standardised engagement and performance features.

Clusters are named from where their centre sits relative to the whole population, so the names stay
meaningful whatever the data looks like:
    engagement  = attempts in 30 days, active days in 14, and (negative) days since last practice
    performance = 30-day accuracy and average topic mastery
"""
from typing import Dict, List, Optional, Tuple

import numpy as np

FEATURES = ["attempts30d", "activeDays14", "daysSinceLast", "accuracy30d", "avgMastery"]
ENGAGEMENT = {"attempts30d": 1.0, "activeDays14": 1.0, "daysSinceLast": -1.0}
PERFORMANCE = {"accuracy30d": 1.0, "avgMastery": 1.0}

PROFILES = {
    "Consistent achievers": (
        "Practise regularly and answer accurately.",
        "Offer harder modules and stretch goals to keep them challenged."),
    "Active but struggling": (
        "Practise often but accuracy and mastery are low.",
        "Suggest easier warm-ups, explanations for wrong answers and shorter focused sessions."),
    "Capable but drifting": (
        "Accurate when they practise, but activity is falling away.",
        "Send gentle reminders and short review sessions before the streak is lost."),
    "At-risk / disengaged": (
        "Low activity and low accuracy.",
        "Reach out personally; restart with easy wins and a small daily goal."),
}


def _kmeans_once(X: np.ndarray, k: int, seed: int, iters: int = 80) -> Tuple[np.ndarray, np.ndarray, float]:
    rng = np.random.default_rng(seed)
    n = len(X)
    centers = [X[rng.integers(n)]]
    for _ in range(1, k):
        d2 = np.min(((X[:, None, :] - np.array(centers)[None, :, :]) ** 2).sum(axis=2), axis=1)
        total = d2.sum()
        centers.append(X[rng.integers(n)] if total <= 1e-12 else X[rng.choice(n, p=d2 / total)])
    centers = np.array(centers, dtype=float)

    labels = np.full(n, -1)
    for _ in range(iters):
        dist = ((X[:, None, :] - centers[None, :, :]) ** 2).sum(axis=2)
        new_labels = dist.argmin(axis=1)
        if np.array_equal(new_labels, labels):
            break
        labels = new_labels
        for j in range(k):
            members = labels == j
            if members.any():
                centers[j] = X[members].mean(axis=0)
    inertia = float(((X - centers[labels]) ** 2).sum())
    return labels, centers, inertia


def kmeans(X: np.ndarray, k: int, restarts: int = 8) -> Tuple[np.ndarray, np.ndarray, float]:
    """Best of several k-means++ restarts (lowest inertia)."""
    best: Optional[Tuple[np.ndarray, np.ndarray, float]] = None
    for seed in range(restarts):
        result = _kmeans_once(X, k, seed)
        if best is None or result[2] < best[2]:
            best = result
    return best  # type: ignore[return-value]


def silhouette(X: np.ndarray, labels: np.ndarray) -> Optional[float]:
    """Mean silhouette score (-1..1); higher means better separated clusters."""
    n = len(X)
    ids = np.unique(labels)
    if len(ids) < 2 or n > 800:
        return None
    dist = np.sqrt(((X[:, None, :] - X[None, :, :]) ** 2).sum(axis=2))
    scores = []
    for i in range(n):
        own = labels == labels[i]
        if own.sum() <= 1:
            scores.append(0.0)
            continue
        a = dist[i][own].sum() / (own.sum() - 1)
        b = min(dist[i][labels == c].mean() for c in ids if c != labels[i])
        scores.append((b - a) / max(a, b, 1e-12))
    return round(float(np.mean(scores)), 3)


def cluster(rows: List[Dict], k: int = 4) -> Dict:
    """rows: dicts with a `key` plus the FEATURES. Returns segments and a key -> segment id map."""
    n = len(rows)
    if n == 0:
        return {"k": 0, "segments": [], "assignments": {}, "silhouette": None, "inertia": 0.0}

    raw = np.array([[float(r.get(name) or 0.0) for name in FEATURES] for r in rows], dtype=float)
    mean = raw.mean(axis=0)
    std = raw.std(axis=0)
    std[std < 1e-9] = 1.0
    Z = (raw - mean) / std

    k = max(1, min(int(k), n // 2 if n >= 4 else 1))
    labels, centers, inertia = kmeans(Z, k) if k > 1 else (np.zeros(n, dtype=int), Z.mean(axis=0, keepdims=True), 0.0)

    segments = []
    used_names: Dict[str, int] = {}
    for j in range(len(centers)):
        members = np.where(labels == j)[0]
        if len(members) == 0:
            continue
        c = centers[j]
        engagement = float(np.mean([ENGAGEMENT[name] * c[FEATURES.index(name)] for name in ENGAGEMENT]))
        performance = float(np.mean([PERFORMANCE[name] * c[FEATURES.index(name)] for name in PERFORMANCE]))
        if k == 1:
            base = "All learners"
            description, action = "Only one group could be formed from the current data.", "Collect more activity first."
        else:
            base = ("Consistent achievers" if engagement >= 0 and performance >= 0 else
                    "Active but struggling" if engagement >= 0 else
                    "Capable but drifting" if performance >= 0 else "At-risk / disengaged")
            description, action = PROFILES[base]
        used_names[base] = used_names.get(base, 0) + 1
        name = base if used_names[base] == 1 else "%s (%d)" % (base, used_names[base])
        original = raw[members].mean(axis=0)
        segments.append({
            "id": int(j),
            "name": name,
            "size": int(len(members)),
            "share": round(len(members) / n, 3),
            "description": description,
            "recommendedAction": action,
            "centroid": {feature: round(float(value), 2) for feature, value in zip(FEATURES, original)},
        })

    segments.sort(key=lambda s: -s["size"])
    return {
        "k": len(segments),
        "segments": segments,
        "assignments": {str(rows[i]["key"]): int(labels[i]) for i in range(n)},
        "silhouette": silhouette(Z, labels),
        "inertia": round(inertia, 3),
    }
