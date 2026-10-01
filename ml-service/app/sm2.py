"""SM-2 spaced repetition scheduling (stateless: the caller stores the state)."""
import math
from typing import Dict


def quality_for(correct: bool, seconds: int) -> int:
    if not correct:
        return 2
    return 5 if seconds <= 60 else 4


def schedule(repetitions: int, interval_days: int, ease_factor: float, correct: bool, seconds: int) -> Dict:
    quality = quality_for(correct, seconds)
    if quality < 3:
        repetitions_new = 0
        interval_new = 1
    else:
        repetitions_new = repetitions + 1
        if repetitions_new == 1:
            interval_new = 1
        elif repetitions_new == 2:
            interval_new = 6
        else:
            interval_new = max(1, int(math.floor(interval_days * ease_factor + 0.5)))

    ease_new = ease_factor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
    ease_new = max(1.3, ease_new)
    return {
        "repetitions": repetitions_new,
        "intervalDays": interval_new,
        "easeFactor": round(ease_new, 4),
        "quality": quality,
        "dueInDays": interval_new,
    }


def replay(attempts) -> Dict:
    """Runs SM-2 over a question's stored attempts (oldest first) and returns the final state.

    Used to give old answers a real schedule instead of a fixed interval ladder. Each attempt is any
    object/dict with `correct` and `seconds`.
    """
    repetitions, interval_days, ease_factor, quality = 0, 0, 2.5, 0
    for a in attempts:
        correct = a["correct"] if isinstance(a, dict) else a.correct
        seconds = a.get("seconds", 0) if isinstance(a, dict) else a.seconds
        step = schedule(repetitions, interval_days, ease_factor, bool(correct), int(seconds or 0))
        repetitions, interval_days = step["repetitions"], step["intervalDays"]
        ease_factor, quality = step["easeFactor"], step["quality"]
    return {
        "repetitions": repetitions,
        "intervalDays": interval_days,
        "easeFactor": round(ease_factor, 4),
        "quality": quality,
        "dueInDays": interval_days,
    }
