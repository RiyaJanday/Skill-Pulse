"""Train the SkillPulse models from the command line.

    python train.py                                 # recall model on 20,000 synthetic learners
    python train.py --samples 100000
    python train.py --csv features.csv              # features CSV: FEATURES columns + a 0/1 `label` column
    python train.py --attempts attempts.csv         # REAL data exported from the admin page (see below)
    python train.py --attempts attempts.csv --dry-run   # evaluate only, keep the current live model
    python train.py --risk                          # dropout-risk model (synthetic data only)

Getting real data: log in as admin and open /api/admin/ai/export-attempts.csv (or use the button on the
admin AI page). Save it as attempts.csv in this folder. The file holds anonymous numeric ids only.
Restart uvicorn after training so the service loads the new model.
"""
import argparse
import json
import sys

from app.training import train_and_save


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the SkillPulse models.")
    parser.add_argument("--samples", type=int, default=20000, help="synthetic learners to simulate")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--csv", default=None, help="train the recall model on a features CSV")
    parser.add_argument("--attempts", default=None, help="train the recall model on exported real attempts")
    parser.add_argument("--dry-run", action="store_true", help="evaluate without replacing the saved model")
    parser.add_argument("--risk", action="store_true", help="train the dropout-risk model instead")
    args = parser.parse_args()

    if args.risk:
        from app import learner_risk
        print(json.dumps(learner_risk.train_and_save(seed=args.seed), indent=2))
        return

    try:
        metrics = train_and_save(n_samples=args.samples, seed=args.seed, csv_path=args.csv,
                                 attempts_path=args.attempts, save=not args.dry_run)
    except ValueError as ex:
        print("Cannot train: %s" % ex, file=sys.stderr)
        sys.exit(1)
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
