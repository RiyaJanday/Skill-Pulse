"""Train and evaluate the XGBoost recall model, comparing it against the old hand-tuned formula."""
import json
import logging
from typing import Optional, Tuple

import numpy as np
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score
from sklearn.model_selection import GroupShuffleSplit, train_test_split
from xgboost import XGBClassifier

from . import config
from .features import FEATURES, formula_recall
from .synthetic import generate

log = logging.getLogger("skillpulse.training")

MIN_REAL_EXAMPLES = 200
MIN_REAL_LEARNERS = 5
MIN_PER_CLASS = 20


def load_csv(path: str) -> Tuple[np.ndarray, np.ndarray]:
    """CSV with a header row: the FEATURES columns plus a 0/1 `label` column."""
    data = np.genfromtxt(path, delimiter=",", names=True)
    missing = [name for name in FEATURES + ["label"] if name not in data.dtype.names]
    if missing:
        raise ValueError("CSV is missing columns: " + ", ".join(missing))
    X = np.column_stack([data[name] for name in FEATURES]).astype(float)
    y = np.asarray(data["label"]).astype(int)
    return X, y


def _scores(y_true: np.ndarray, proba: np.ndarray) -> dict:
    proba = np.clip(proba, 1e-6, 1 - 1e-6)
    return {
        "auc": round(float(roc_auc_score(y_true, proba)), 4),
        "logloss": round(float(log_loss(y_true, proba)), 4),
        "brier": round(float(brier_score_loss(y_true, proba)), 4),
    }


def _check_real_data(X: np.ndarray, y: np.ndarray, groups: np.ndarray) -> None:
    learners = len(set(groups.tolist()))
    positives = int(y.sum())
    negatives = int(len(y) - positives)
    if len(y) < MIN_REAL_EXAMPLES or learners < MIN_REAL_LEARNERS or min(positives, negatives) < MIN_PER_CLASS:
        raise ValueError(
            "Not enough real data to train honestly: %d examples from %d learners (%d correct, %d wrong). "
            "Need at least %d examples, %d learners and %d of each outcome. Keep collecting answers."
            % (len(y), learners, positives, negatives, MIN_REAL_EXAMPLES, MIN_REAL_LEARNERS, MIN_PER_CLASS))


def train_and_save(n_samples: int = 20000, seed: int = 42, csv_path: Optional[str] = None,
                   attempts_path: Optional[str] = None, save: bool = True) -> dict:
    """Train on synthetic data (default), a features CSV (`csv_path`) or exported real attempts (`attempts_path`).

    With attempts_path the train/test split is by LEARNER, so the test score measures how well the model
    handles people it has never seen. With save=False the model is evaluated but the live one is not replaced.
    """
    groups = None
    if attempts_path:
        from .real_data import load_dataset
        X, y, groups = load_dataset(attempts_path)
        _check_real_data(X, y, groups)
        source = "attempts:" + attempts_path
    elif csv_path:
        X, y = load_csv(csv_path)
        source = "csv:" + csv_path
    else:
        X, y = generate(n_samples, seed)
        source = "synthetic"

    if groups is not None:
        splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=seed)
        train_idx, test_idx = next(splitter.split(X, y, groups))
        X_train, X_test, y_train, y_test = X[train_idx], X[test_idx], y[train_idx], y[test_idx]
        if len(set(y_test.tolist())) < 2 or len(set(y_train.tolist())) < 2:
            raise ValueError("The learner split left only one outcome in the train or test set. "
                             "Collect more answers from more learners and try again.")
    else:
        X_train, X_test, y_train, y_test = train_test_split(
            X, y, test_size=0.2, random_state=seed, stratify=y)

    model = XGBClassifier(
        n_estimators=250, max_depth=4, learning_rate=0.06, subsample=0.9,
        colsample_bytree=0.9, eval_metric="logloss", n_jobs=2, random_state=seed)
    model.fit(X_train, y_train)

    proba = model.predict_proba(X_test)[:, 1]
    baseline = np.array([formula_recall(row[0], row[1], row[3], row[4]) for row in X_test])

    metrics = {
        "source": source,
        "trainSamples": int(len(X_train)),
        "testSamples": int(len(X_test)),
        "positiveRate": round(float(y.mean()), 4),
        "xgboost": _scores(y_test, proba),
        "heuristicFormula": _scores(y_test, baseline),
        "featureImportance": {
            name: round(float(score), 4) for name, score in zip(FEATURES, model.feature_importances_)
        },
    }
    if groups is not None:
        metrics["learners"] = len(set(groups.tolist()))
        metrics["splitBy"] = "learner"
    metrics["saved"] = bool(save)

    if save:
        config.MODEL_DIR.mkdir(parents=True, exist_ok=True)
        model.save_model(str(config.RECALL_MODEL_PATH))
        config.RECALL_METRICS_PATH.write_text(json.dumps(metrics, indent=2), encoding="utf-8")
        log.info("Recall model trained: %s", metrics["xgboost"])
    return metrics
