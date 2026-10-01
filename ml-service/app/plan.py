"""7-day personalized plan generation through the LLM chain, with strict validation."""
import json
import math
from typing import Any, Dict

from . import llm
from .schemas import PlanRequest

SYSTEM_PROMPT = ("You are SkillPulse, a careful learning coach. Return only valid JSON. "
                 "Never invent student measurements.")


def _round(value: float) -> int:
    return int(math.floor(value + 0.5))


def target_minutes(daily_minutes: int, adherence: float) -> int:
    """Shrink the load when the learner is falling behind, stretch it when they are on track."""
    if adherence < 0.4:
        return max(10, _round(daily_minutes * 0.7))
    if adherence > 0.8:
        return min(180, _round(daily_minutes * 1.1))
    return daily_minutes


def build_prompt(req: PlanRequest) -> str:
    minutes = target_minutes(req.dailyMinutes, req.adherence)
    return (
        "Create a seven-day plan for subject=%s. Preferences: style=%s, goal=%s, minutes=%d, "
        "difficulty=%s, language=%s. Previous-plan adherence=%d%%. Adaptive signals=%s. "
        "Prioritize low mastery and due reviews; use shorter high-priority tasks when adherence is low. "
        "Return JSON exactly with summary:string, primaryWeakness:string, strengths:string[], "
        "days:exactly 7 objects with day:1-7, focus:string, objective:string, explanationStyle:string, "
        "difficulty:string, minutes:number, activities:string[]. "
        "Give each day 2-4 concrete completable tasks and keep within minutes."
        % (req.subject, req.explanationStyle, req.learningGoal, minutes, req.preferredDifficulty,
           req.language, _round(req.adherence * 100), json.dumps(req.signals, default=str)))


def validate(plan: Dict[str, Any], req: PlanRequest) -> Dict[str, Any]:
    days = plan.get("days")
    if not isinstance(days, list) or len(days) != 7:
        raise ValueError("plan must contain exactly 7 days")
    cleaned = []
    for item in days:
        day = int(item.get("day", 0))
        minutes = int(item.get("minutes", 0))
        activities = [str(a) for a in (item.get("activities") or []) if str(a).strip()]
        if not 1 <= day <= 7 or not 10 <= minutes <= 180 or not activities:
            raise ValueError("plan day out of range or without activities")
        cleaned.append({
            "day": day,
            "focus": str(item.get("focus") or req.subject),
            "objective": str(item.get("objective") or ""),
            "explanationStyle": str(item.get("explanationStyle") or req.explanationStyle),
            "difficulty": str(item.get("difficulty") or req.preferredDifficulty),
            "minutes": minutes,
            "activities": activities,
        })
    if sorted(item["day"] for item in cleaned) != list(range(1, 8)):
        raise ValueError("plan days must be 1 to 7, each exactly once")
    return {
        "summary": str(plan.get("summary") or "Personalized learning plan"),
        "primaryWeakness": str(plan.get("primaryWeakness") or req.subject),
        "strengths": [str(s) for s in (plan.get("strengths") or [])],
        "days": cleaned,
    }


def generate_plan(req: PlanRequest) -> Dict[str, Any]:
    """Raises llm.LlmUnavailable if every provider fails, ValueError if the answer is unusable."""
    text, engine = llm.chat(
        [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": build_prompt(req)}],
        json_mode=True, temperature=0.3, max_tokens=3000)
    plan = validate(llm.extract_json(text), req)
    plan["engine"] = engine
    return plan
