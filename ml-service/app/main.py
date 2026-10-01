"""SkillPulse ML service: the AI/ML brain behind the Java backend."""
import hmac
import logging
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException

from . import bkt, config, learner_risk, plan, recall, sm2
from .ai_routes import router as ai_router
from .extra_routes import router as extra_router
from .llm import LlmUnavailable
from .schemas import BktReplayRequest, BktReplayResponse, BktReplayResult
from .schemas import (BktFitRequest, BktFitResponse, BktParamsModel, BktUpdateRequest, BktUpdateResponse,
                      DecayRequest, DecayResponse, DecayResult, PlanRequest, PlanResponse,
                      ReviewReplayRequest, ReviewReplayResponse, ReviewReplayResult,
                      ReviewScheduleRequest, ReviewScheduleResponse, SkillAnalysisRequest,
                      SkillAnalysisResponse)

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    recall.load_or_train()
    learner_risk.load_or_train()
    yield


app = FastAPI(title="SkillPulse ML Service", version="1.0.0", lifespan=lifespan)


def require_key(x_internal_key: Optional[str] = Header(default=None)) -> None:
    """If ML_SERVICE_KEY is set, callers (the Java backend) must send it as X-Internal-Key."""
    if config.SERVICE_KEY and not hmac.compare_digest(x_internal_key or "", config.SERVICE_KEY):
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-Key.")


guarded = [Depends(require_key)]
app.include_router(ai_router)
app.include_router(extra_router)


@app.get("/health")
def health():
    info = recall.model_info()
    return {
        "ready": True,
        "recallEngine": info["engine"],
        "llmProviders": [p["name"] for p in config.llm_providers()],
    }


@app.get("/model/info", dependencies=guarded)
def model_info():
    return recall.model_info()


@app.post("/analyze", response_model=SkillAnalysisResponse, dependencies=guarded)
def analyze(req: SkillAnalysisRequest):
    return recall.analyze(req.skillName, req.timestamps, req.accuracies, req.difficulties,
                          req.hintsUsed, req.completionStatus)


@app.post("/bkt/update", response_model=BktUpdateResponse, dependencies=guarded)
def bkt_update(req: BktUpdateRequest):
    params = bkt.BktParams(req.params.pInit, req.params.pLearn, req.params.pGuess, req.params.pSlip)
    start = params.p_init if req.mastery is None else req.mastery
    new_mastery = bkt.update(start, req.correct, params, req.difficulty, req.seconds, req.daysSinceLast)
    return BktUpdateResponse(mastery=round(new_mastery, 6))


@app.post("/bkt/decay", response_model=DecayResponse, dependencies=guarded)
def bkt_decay(req: DecayRequest):
    return DecayResponse(results=[
        DecayResult(
            key=item.key,
            effectiveMastery=round(bkt.effective_mastery(item.mastery, item.daysSinceLast), 6),
            daysToThreshold=bkt.days_until_threshold(item.mastery, item.daysSinceLast, item.threshold))
        for item in req.items
    ])


@app.post("/bkt/replay", response_model=BktReplayResponse, dependencies=guarded)
def bkt_replay(req: BktReplayRequest):
    """Seed mastery for learners who already have answer history (one call for many topics)."""
    results = []
    for item in req.items:
        params = bkt.BktParams(item.params.pInit, item.params.pLearn, item.params.pGuess, item.params.pSlip)
        attempts = [a.model_dump() for a in item.attempts]
        results.append(BktReplayResult(
            key=item.key, mastery=round(bkt.replay(attempts, params), 6), attempts=len(attempts)))
    return BktReplayResponse(results=results)


@app.post("/bkt/fit", response_model=BktFitResponse, dependencies=guarded)
def bkt_fit(req: BktFitRequest):
    try:
        params, ll, sequences, observations = bkt.fit(req.sequences)
    except ValueError as ex:
        raise HTTPException(status_code=422, detail=str(ex))
    return BktFitResponse(
        params=BktParamsModel(pInit=params.p_init, pLearn=params.p_learn,
                              pGuess=params.p_guess, pSlip=params.p_slip),
        logLikelihood=ll, sequences=sequences, observations=observations)


@app.post("/review/schedule", response_model=ReviewScheduleResponse, dependencies=guarded)
def review_schedule(req: ReviewScheduleRequest):
    return sm2.schedule(req.repetitions, req.intervalDays, req.easeFactor, req.correct, req.seconds)


@app.post("/review/replay", response_model=ReviewReplayResponse, dependencies=guarded)
def review_replay(req: ReviewReplayRequest):
    """Final SM-2 state for many questions from their stored attempts (one call, oldest attempt first)."""
    results = []
    for item in req.items:
        state = sm2.replay([a.model_dump() for a in item.attempts])
        results.append(ReviewReplayResult(
            key=item.key, repetitions=state["repetitions"], intervalDays=state["intervalDays"],
            easeFactor=state["easeFactor"], quality=state["quality"], attempts=len(item.attempts)))
    return ReviewReplayResponse(results=results)


@app.post("/plan/generate", response_model=PlanResponse, dependencies=guarded)
def plan_generate(req: PlanRequest):
    try:
        return plan.generate_plan(req)
    except (LlmUnavailable, ValueError) as ex:
        # 503 tells the Java backend to use its own rule-based fallback plan.
        raise HTTPException(status_code=503, detail="Plan generation unavailable: %s" % ex)
