"""Timetable explanation, weekly report note and job skill-gap endpoints (mounted by main.py).

Same X-Internal-Key protection as the rest of the service. LLM failures answer 503, which the Java backend turns into
a built-in fallback (timetable, weekly email) or a friendly "unavailable" message (skill gap).
"""
import logging
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from . import coach_tools
from .ai_routes import require_key
from .llm import LlmUnavailable

log = logging.getLogger("skillpulse.extra")

router = APIRouter(dependencies=[Depends(require_key)])


def _unavailable(what: str, ex: Exception) -> HTTPException:
    log.warning("%s failed: %s", what, ex)
    return HTTPException(status_code=503, detail="%s unavailable: %s" % (what, ex))


# ---- timetable -------------------------------------------------------------------------------
class MovedTask(BaseModel):
    task: str = ""
    when: str = ""


class TimetableNoteRequest(BaseModel):
    availableMinutes: int = Field(default=0, ge=0, le=600)
    restDay: bool = False
    kept: List[str] = Field(default_factory=list, max_length=30)
    moved: List[MovedTask] = Field(default_factory=list, max_length=30)
    unplaced: List[str] = Field(default_factory=list, max_length=30)


class TimetableNoteResponse(BaseModel):
    message: str
    engine: str


@router.post("/timetable/explain", response_model=TimetableNoteResponse)
def timetable_explain(req: TimetableNoteRequest):
    try:
        return coach_tools.timetable_note(req.availableMinutes, req.restDay, req.kept,
                                          [m.model_dump() for m in req.moved], req.unplaced)
    except (LlmUnavailable, ValueError) as ex:
        raise _unavailable("Timetable note", ex)


# ---- weekly report -----------------------------------------------------------------------------
class WeeklyNoteRequest(BaseModel):
    name: str = ""
    overallHealth: Optional[Any] = None
    streakDays: Optional[Any] = None
    minutesThisWeek: Optional[Any] = None
    skillsNeedingAttention: Optional[Any] = None


class WeeklyNoteResponse(BaseModel):
    note: str
    engine: str


@router.post("/coach/weekly-note", response_model=WeeklyNoteResponse)
def coach_weekly_note(req: WeeklyNoteRequest):
    try:
        return coach_tools.weekly_note(req.name, req.overallHealth, req.streakDays, req.minutesThisWeek,
                                       req.skillsNeedingAttention)
    except (LlmUnavailable, ValueError) as ex:
        raise _unavailable("Weekly note", ex)


# ---- job skill gap -------------------------------------------------------------------------------
class LearnerTopic(BaseModel):
    topic: str
    subject: str = ""
    masteryPercent: int = Field(default=0, ge=0, le=100)


class SkillGapRequest(BaseModel):
    jobDescription: str = Field(min_length=40, max_length=6000)
    learnerTopics: List[LearnerTopic] = Field(default_factory=list, max_length=200)
    platformTopics: List[str] = Field(default_factory=list, max_length=300)


@router.post("/skills/gap")
def skills_gap(req: SkillGapRequest):
    try:
        return coach_tools.skill_gap(req.jobDescription, [t.model_dump() for t in req.learnerTopics],
                                     req.platformTopics)
    except (LlmUnavailable, ValueError) as ex:
        raise _unavailable("Skill-gap analysis", ex)
