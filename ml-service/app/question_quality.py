"""Question quality analytics: a Rasch (1-parameter IRT) model plus classical item statistics.

Every answer is modelled as  P(correct) = sigmoid(theta_learner - b_question)  and fitted by a few rounds of
regularised Newton updates (a weak Gaussian prior keeps learners who got everything right or wrong stable).
b is the question's measured difficulty on a logit scale, centred at 0 for the whole bank.

Each question is then flagged from evidence, so an admin can review it:
    TOO_HARD / TOO_EASY          almost nobody / almost everybody answers it correctly
    LOW_DISCRIMINATION           strong and weak learners do equally well (the question tells us little)
    POSSIBLE_WRONG_KEY           strong learners fail it MORE than weak ones (often a wrong answer key)
    SLOW                         takes far longer than typical questions (ambiguous or too long)
    LABEL_TOO_EASY / TOO_HARD    the labelled difficulty disagrees with the measured one
Flags are prompts for a human to look, not verdicts.
"""
from typing import Dict, List, Sequence

import numpy as np

EASY_LOGIT = -0.7
HARD_LOGIT = 0.7
PRIOR_VARIANCE = 2.0


def _sigmoid(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -30.0, 30.0)))


def _insufficient(message: str, responses: int = 0) -> Dict:
    return {"insufficientData": True, "message": message,
            "summary": {"questions": 0, "learners": 0, "responses": responses, "flagged": 0},
            "questions": []}


def suggested_difficulty(b: float) -> str:
    return "EASY" if b < EASY_LOGIT else "HARD" if b > HARD_LOGIT else "MEDIUM"


def fit_rasch(Q: np.ndarray, U: np.ndarray, y: np.ndarray, n_questions: int, n_users: int,
              iterations: int = 40):
    theta = np.zeros(n_users)
    b = np.zeros(n_questions)
    for _ in range(iterations):
        p = _sigmoid(theta[U] - b[Q])
        g = np.bincount(U, weights=y - p, minlength=n_users) - theta / PRIOR_VARIANCE
        h = np.bincount(U, weights=p * (1 - p), minlength=n_users) + 1.0 / PRIOR_VARIANCE
        theta = theta + g / h

        p = _sigmoid(theta[U] - b[Q])
        g = np.bincount(Q, weights=p - y, minlength=n_questions) - b / PRIOR_VARIANCE
        h = np.bincount(Q, weights=p * (1 - p), minlength=n_questions) + 1.0 / PRIOR_VARIANCE
        b = b + g / h

        shift = b.mean()  # identifiability: centre the question scale at 0
        b -= shift
        theta -= shift
    return theta, b


def calibrate(responses: Sequence[Dict], labelled: Dict[int, str], min_responses: int = 8) -> Dict:
    """responses: dicts with questionId, userId, correct, seconds. labelled: questionId -> EASY/MEDIUM/HARD."""
    if not responses:
        return _insufficient("No answers have been recorded yet.")

    q_raw = np.array([int(r["questionId"]) for r in responses])
    u_raw = np.array([int(r["userId"]) for r in responses])
    y = np.array([1.0 if r["correct"] else 0.0 for r in responses])
    sec = np.array([max(0.0, float(r.get("seconds") or 0)) for r in responses])

    _, q_idx = np.unique(q_raw, return_inverse=True)
    counts = np.bincount(q_idx)
    keep = counts[q_idx] >= min_responses
    if keep.sum() < 30 or (counts >= min_responses).sum() < 2:
        return _insufficient(
            "Need at least %d answers on at least 2 questions (and 30 answers overall) before calibrating."
            % min_responses, len(responses))
    q_raw, u_raw, y, sec = q_raw[keep], u_raw[keep], y[keep], sec[keep]

    q_ids, Q = np.unique(q_raw, return_inverse=True)
    u_ids, U = np.unique(u_raw, return_inverse=True)
    nq, nu = len(q_ids), len(u_ids)
    theta, b = fit_rasch(Q, U, y, nq, nu)

    n_per_q = np.bincount(Q, minlength=nq).astype(float)
    p_correct = np.bincount(Q, weights=y, minlength=nq) / n_per_q
    avg_seconds = np.bincount(Q, weights=sec, minlength=nq) / n_per_q
    median_seconds = float(np.median(avg_seconds[avg_seconds > 0])) if (avg_seconds > 0).any() else 0.0

    # Discrimination: correlation between the answer and the learner's score on all OTHER questions.
    tot_correct = np.bincount(U, weights=y, minlength=nu)
    tot_n = np.bincount(U, minlength=nu).astype(float)
    rest_n = tot_n[U] - 1.0
    valid = rest_n > 0
    rest_score = np.where(valid, (tot_correct[U] - y) / np.where(valid, rest_n, 1.0), 0.0)

    rows: List[Dict] = []
    for j in range(nq):
        mask = (Q == j) & valid
        disc = 0.0
        if mask.sum() >= 5 and y[mask].std() > 0 and rest_score[mask].std() > 0:
            disc = float(np.corrcoef(y[mask], rest_score[mask])[0, 1])
        n = int(n_per_q[j])
        qid = int(q_ids[j])
        flags: List[str] = []
        if p_correct[j] < 0.25:
            flags.append("TOO_HARD")
        if p_correct[j] > 0.97:
            flags.append("TOO_EASY")
        if n >= 15 and disc < -0.10:
            flags.append("POSSIBLE_WRONG_KEY")
        elif n >= 15 and disc < 0.10:
            flags.append("LOW_DISCRIMINATION")
        if median_seconds > 0 and avg_seconds[j] > 2.5 * median_seconds and n >= 10:
            flags.append("SLOW")
        label = str(labelled.get(qid, "")).upper()
        measured = suggested_difficulty(float(b[j]))
        if label == "EASY" and measured == "HARD":
            flags.append("LABEL_TOO_EASY")
        if label == "HARD" and measured == "EASY":
            flags.append("LABEL_TOO_HARD")
        rows.append({
            "questionId": qid,
            "responses": n,
            "pCorrect": round(float(p_correct[j]), 3),
            "difficultyLogit": round(float(b[j]), 3),
            "labelledDifficulty": label or None,
            "suggestedDifficulty": measured,
            "discrimination": round(disc, 3),
            "avgSeconds": round(float(avg_seconds[j]), 1),
            "flags": flags,
        })

    rows.sort(key=lambda r: (-len(r["flags"]), -abs(r["difficultyLogit"])))
    return {
        "insufficientData": False,
        "message": "",
        "summary": {"questions": nq, "learners": nu, "responses": int(len(y)),
                    "flagged": sum(1 for r in rows if r["flags"])},
        "questions": rows,
    }
