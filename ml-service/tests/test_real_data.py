import csv
from datetime import date, timedelta

import numpy as np
import pytest

from app import real_data
from app.training import train_and_save

HEADER = ["userId", "topicId", "questionId", "difficulty", "correct", "seconds", "attemptedAt"]


def write_attempts(path, rows):
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(HEADER)
        writer.writerows(rows)


def stamp(day_offset, time="10:00:00.123456Z"):
    return "%sT%s" % ((date(2026, 1, 1) + timedelta(days=day_offset)).isoformat(), time)


def test_examples_use_history_before_each_answer(tmp_path):
    path = tmp_path / "a.csv"
    # Deliberately out of order: the loader must sort by time itself.
    write_attempts(path, [
        (1, 7, 1, "MEDIUM", "true", 20, stamp(3)),
        (1, 7, 2, "MEDIUM", "true", 20, stamp(0)),
        (1, 7, 3, "MEDIUM", "false", 20, stamp(1)),
        (1, 7, 4, "MEDIUM", "true", 20, stamp(4)),
    ])
    X, y, groups = real_data.load_dataset(str(path))
    # Order in time: correct (day 0), wrong (day 1), correct (day 3), correct (day 4).
    assert X.shape == (2, 8)
    assert y.tolist() == [1, 1]
    assert groups.tolist() == ["1", "1"]
    assert X[0][0] == 2          # daysGap: day 1 -> day 3
    assert X[0][1] == pytest.approx(0.5)   # recent accuracy of [correct, wrong]
    assert X[0][5] == 2          # attempts in the history
    assert X[1][0] == 1          # daysGap: day 3 -> day 4
    assert X[1][5] == 3


def test_topics_and_learners_are_kept_separate(tmp_path):
    path = tmp_path / "a.csv"
    write_attempts(path, [
        (1, 7, 1, "EASY", "true", 10, stamp(0)),
        (1, 8, 2, "EASY", "false", 10, stamp(1)),   # other topic: not part of topic 7's history
        (1, 7, 3, "EASY", "true", 10, stamp(2)),
        (2, 7, 1, "HARD", "false", 10, stamp(0)),
    ])
    X, y, _ = real_data.load_dataset(str(path))
    assert len(y) == 0  # no sequence reaches 3 answers, so nothing to learn from yet


def test_missing_columns_are_reported(tmp_path):
    path = tmp_path / "bad.csv"
    path.write_text("userId,correct\n1,true\n", encoding="utf-8")
    with pytest.raises(ValueError, match="missing columns"):
        real_data.load_attempts(str(path))


def test_training_refuses_too_little_data(tmp_path):
    path = tmp_path / "small.csv"
    write_attempts(path, [(1, 7, i, "MEDIUM", "true", 10, stamp(i)) for i in range(6)])
    with pytest.raises(ValueError, match="Not enough real data"):
        train_and_save(attempts_path=str(path), save=False)


def test_training_on_real_style_data_splits_by_learner(tmp_path):
    rng = np.random.default_rng(1)
    rows = []
    for learner in range(40):
        ability = rng.normal(0.4, 0.8)
        day = 0
        for q in range(18):
            day += int(rng.integers(0, 6))
            p = 1.0 / (1.0 + np.exp(-(ability + 0.05 * q - 0.04 * day / 10)))
            rows.append((learner, 7, q, "MEDIUM", "true" if rng.random() < p else "false", 15, stamp(day)))
    path = tmp_path / "real.csv"
    write_attempts(path, rows)

    metrics = train_and_save(attempts_path=str(path), save=False)

    assert metrics["saved"] is False
    assert metrics["splitBy"] == "learner"
    assert metrics["learners"] == 40
    assert metrics["source"].startswith("attempts:")
    assert 0.0 <= metrics["xgboost"]["auc"] <= 1.0
    assert 0.0 <= metrics["heuristicFormula"]["auc"] <= 1.0
