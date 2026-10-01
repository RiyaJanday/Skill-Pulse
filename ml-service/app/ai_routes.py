"""AI coach, learner analytics and adaptive-ordering endpoints (mounted by main.py).

Protected by the same X-Internal-Key as the rest of the service. LLM-backed endpoints answer 503 when every
provider fails, which the Java backend turns into a friendly "coach unavailable" message.
"""
import hmac
import json
import logging
from typing import Iterator, Optional

from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import StreamingResponse

from . import adaptive_order, config, learner_risk, question_quality, segments, study_chat, tutor
from .llm import LlmUnavailable
from .schemas_ai import (ChatRequest, ChatResponse, ExplainRequest, ExplainResponse, GenerateQuestionsRequest,
                         GenerateQuestionsResponse, NudgeRequest, NudgeResponse, OrderRequest, QualityRequest,
                         RiskRequest, RiskResponse, RiskResult, SegmentsRequest, StudyChatResponse)

log = logging.getLogger("skillpulse.ai")


def require_key(x_internal_key: Optional[str] = Header(default=None)) -> None:
    if config.SERVICE_KEY and not hmac.compare_digest(x_internal_key or "", config.SERVICE_KEY):
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-Key.")


router = APIRouter(dependencies=[Depends(require_key)])


def _unavailable(what: str, ex: Exception) -> HTTPException:
    log.warning("%s failed: %s", what, ex)
    return HTTPException(status_code=503, detail="%s unavailable: %s" % (what, ex))


# ---- LLM-backed ------------------------------------------------------------------------------
@router.post("/tutor/chat", response_model=ChatResponse)
def tutor_chat(req: ChatRequest):
    try:
        return tutor.chat(req.message, [t.model_dump() for t in req.history], req.context)
    except (LlmUnavailable, ValueError) as ex:
        raise _unavailable("Tutor", ex)


@router.post("/chat/reply", response_model=StudyChatResponse)
def chat_reply(req: ChatRequest):
    """Routed, scoped study chat, one complete reply. History and context come from the Java backend."""
    try:
        return study_chat.reply(req.message, [t.model_dump() for t in req.history], req.context)
    except (LlmUnavailable, ValueError) as ex:
        raise _unavailable("Study chat", ex)


@router.post("/chat/stream")
def chat_stream(req: ChatRequest):
    """Same as /chat/reply but as Server-Sent Events: meta, delta..., done (or error)."""
    history = [t.model_dump() for t in req.history]

    def events() -> Iterator[str]:
        for event in study_chat.stream(req.message, history, req.context):
            yield "data: " + json.dumps(event, ensure_ascii=False) + "\n\n"

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/tutor/explain", response_model=ExplainResponse)
def tutor_explain(req: ExplainRequest):
    try:
        return tutor.explain(req.prompt, req.options, req.selectedIndex, req.correctIndex,
                             req.baseExplanation, req.masteryPercent, req.topic)
    except (LlmUnavailable, ValueError) as ex:
        raise _unavailable("Explanation", ex)


@router.post("/tutor/generate-questions", response_model=GenerateQuestionsResponse)
def tutor_generate(req: GenerateQuestionsRequest):
    try:
        return tutor.generate_questions(req.subject, req.topic, req.difficulty, req.count, req.existingPrompts)
    except (LlmUnavailable, ValueError) as ex:
        raise _unavailable("Question generation", ex)


@router.post("/coach/nudge", response_model=NudgeResponse)
def coach_nudge(req: NudgeRequest):
    try:
        return tutor.nudge(req.name, req.riskLevel, req.reasons, req.weakTopics, req.dueReviews, req.streak)
    except (LlmUnavailable, ValueError) as ex:
        raise _unavailable("Nudge", ex)


# ---- classical ML ----------------------------------------------------------------------------
@router.post("/risk/predict", response_model=RiskResponse)
def risk_predict(req: RiskRequest):
    learners = [f.model_dump() for f in req.learners]
    scores, engine = learner_risk.predict([learner_risk.feature_vector(f) for f in learners])
    results = [RiskResult(key=f["key"], riskScore=round(score, 4), level=learner_risk.level(score),
                          reasons=learner_risk.explain(f, score))
               for f, score in zip(learners, scores)]
    return RiskResponse(results=results, engine=engine)


@router.get("/risk/info")
def risk_info():
    return learner_risk.model_info()


@router.post("/segments/cluster")
def segments_cluster(req: SegmentsRequest):
    return segments.cluster([f.model_dump() for f in req.learners], req.k)


@router.post("/questions/quality")
def questions_quality(req: QualityRequest):
    return question_quality.calibrate([r.model_dump() for r in req.responses],
                                      {q.id: q.difficulty for q in req.questions}, req.minResponses)


@router.post("/adaptive/order")
def adaptive_order_endpoint(req: OrderRequest):
    return adaptive_order.order(req.mastery, [q.model_dump() for q in req.questions], req.target)
