"""Request/response models for the AI coach and learner-analytics endpoints (camelCase like the rest)."""
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


# ---- tutor -----------------------------------------------------------------------------------
class ChatTurn(BaseModel):
    role: str = "user"
    content: str = ""


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1200)
    history: List[ChatTurn] = Field(default_factory=list, max_length=40)
    context: Dict[str, Any] = Field(default_factory=dict)


class ChatResponse(BaseModel):
    reply: str
    engine: str


class StudyChatResponse(BaseModel):
    reply: str
    engine: str
    category: str


class ExplainRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=1500)
    options: List[str] = Field(min_length=2, max_length=4)
    selectedIndex: Optional[int] = None
    correctIndex: int
    baseExplanation: str = ""
    masteryPercent: Optional[int] = None
    topic: str = ""


class ExplainResponse(BaseModel):
    whyWrong: str = ""
    whyCorrect: str
    memoryTip: str
    tryNext: str = ""
    engine: str


class GenerateQuestionsRequest(BaseModel):
    subject: str
    topic: str
    difficulty: str = "MEDIUM"
    count: int = Field(default=3, ge=1, le=5)
    existingPrompts: List[str] = Field(default_factory=list)


class GeneratedQuestion(BaseModel):
    prompt: str
    options: List[str]
    correctIndex: int
    explanation: str
    difficulty: str


class GenerateQuestionsResponse(BaseModel):
    questions: List[GeneratedQuestion]
    engine: str


class NudgeRequest(BaseModel):
    name: str = ""
    riskLevel: str = "HIGH"
    reasons: List[str] = Field(default_factory=list)
    weakTopics: List[str] = Field(default_factory=list)
    dueReviews: int = 0
    streak: int = 0


class NudgeResponse(BaseModel):
    subject: str
    body: str
    engine: str


# ---- learner analytics -------------------------------------------------------------------------
class LearnerFeatures(BaseModel):
    key: str
    daysSinceLast: float = 0
    attempts7d: float = 0
    attempts30d: float = 0
    accuracy7d: float = 0
    accuracy30d: float = 0
    activeDays14: float = 0
    streak: float = 0
    avgMastery: float = 0.2
    dueReviews: float = 0
    avgSeconds: float = 40


class RiskRequest(BaseModel):
    learners: List[LearnerFeatures] = Field(default_factory=list, max_length=5000)


class RiskResult(BaseModel):
    key: str
    riskScore: float
    level: str
    reasons: List[str]


class RiskResponse(BaseModel):
    results: List[RiskResult]
    engine: str


class SegmentsRequest(BaseModel):
    learners: List[LearnerFeatures] = Field(default_factory=list, max_length=5000)
    k: int = Field(default=4, ge=1, le=6)


# ---- question quality and adaptive ordering ----------------------------------------------------------
class QualityAnswer(BaseModel):
    questionId: int
    userId: int
    correct: bool
    seconds: int = 0


class QualityQuestion(BaseModel):
    id: int
    difficulty: str = "MEDIUM"


class QualityRequest(BaseModel):
    responses: List[QualityAnswer] = Field(default_factory=list, max_length=200000)
    questions: List[QualityQuestion] = Field(default_factory=list)
    minResponses: int = Field(default=8, ge=3, le=200)


class OrderQuestion(BaseModel):
    id: int
    difficulty: str = "MEDIUM"
    difficultyLogit: Optional[float] = None
    due: bool = False


class OrderRequest(BaseModel):
    mastery: float = 0.2
    questions: List[OrderQuestion] = Field(default_factory=list, max_length=200)
    target: float = Field(default=0.70, ge=0.3, le=0.95)
