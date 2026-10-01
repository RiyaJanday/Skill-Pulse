"""Request/response models. Field names are camelCase to match the Java (Jackson) JSON contract."""
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


# ---- Skill analysis (replaces Java SkillPulseMlService) -------------------------------------
class SkillAnalysisRequest(BaseModel):
    token: Optional[str] = None  # ignored; accepted so the old Java payload still validates
    skillName: Optional[str] = None
    timestamps: List[str] = Field(default_factory=list)
    accuracies: List[float] = Field(default_factory=list)
    difficulties: List[float] = Field(default_factory=list)  # 1=EASY, 2=MEDIUM, 3=HARD
    errors: Dict[str, int] = Field(default_factory=dict)
    hintsUsed: List[float] = Field(default_factory=list)
    completionStatus: List[int] = Field(default_factory=list)


class SkillAnalysisResponse(BaseModel):
    skillName: str
    status: str
    confidence: float
    recallProbability: float
    priority: str
    actions: List[str]
    explanation: str
    engine: str


# ---- BKT -------------------------------------------------------------------------------------
class BktParamsModel(BaseModel):
    pInit: float = 0.20
    pLearn: float = 0.12
    pGuess: float = 0.20
    pSlip: float = 0.10


class BktUpdateRequest(BaseModel):
    mastery: Optional[float] = None  # null = first attempt, starts from params.pInit
    correct: bool
    difficulty: str = "MEDIUM"
    seconds: int = 0
    daysSinceLast: int = 0
    params: BktParamsModel = Field(default_factory=BktParamsModel)


class BktUpdateResponse(BaseModel):
    mastery: float
    engine: str = "bkt-v1"


class DecayItem(BaseModel):
    key: str
    mastery: float
    daysSinceLast: int = 0
    threshold: float = 0.60


class DecayRequest(BaseModel):
    items: List[DecayItem]


class DecayResult(BaseModel):
    key: str
    effectiveMastery: float
    daysToThreshold: int


class DecayResponse(BaseModel):
    results: List[DecayResult]


class BktFitRequest(BaseModel):
    sequences: List[List[bool]]  # one list of correct/incorrect answers per learner, oldest first


class BktFitResponse(BaseModel):
    params: BktParamsModel
    logLikelihood: float
    sequences: int
    observations: int


# ---- Spaced repetition -----------------------------------------------------------------------
class BktReplayAttempt(BaseModel):
    correct: bool
    difficulty: str = "MEDIUM"
    seconds: int = 0
    daysSinceLast: int = 0


class BktReplayItem(BaseModel):
    key: str
    params: BktParamsModel = Field(default_factory=BktParamsModel)
    attempts: List[BktReplayAttempt]  # oldest first


class BktReplayRequest(BaseModel):
    items: List[BktReplayItem]


class BktReplayResult(BaseModel):
    key: str
    mastery: float
    attempts: int


class BktReplayResponse(BaseModel):
    results: List[BktReplayResult]


class ReviewScheduleRequest(BaseModel):
    repetitions: int = 0
    intervalDays: int = 0
    easeFactor: float = 2.5
    correct: bool
    seconds: int = 0


class ReviewScheduleResponse(BaseModel):
    repetitions: int
    intervalDays: int
    easeFactor: float
    quality: int
    dueInDays: int


class ReviewReplayAttempt(BaseModel):
    correct: bool
    seconds: int = 0


class ReviewReplayItem(BaseModel):
    key: str  # question id
    attempts: List[ReviewReplayAttempt]  # oldest first


class ReviewReplayRequest(BaseModel):
    items: List[ReviewReplayItem]


class ReviewReplayResult(BaseModel):
    key: str
    repetitions: int
    intervalDays: int
    easeFactor: float
    quality: int
    attempts: int


class ReviewReplayResponse(BaseModel):
    results: List[ReviewReplayResult]


# ---- Personalized plan -----------------------------------------------------------------------
class PlanRequest(BaseModel):
    subject: str
    explanationStyle: str = "EXAMPLE_FIRST"
    learningGoal: str = "Build strong practical skills"
    dailyMinutes: int = 25
    preferredDifficulty: str = "ADAPTIVE"
    language: str = "English"
    adherence: float = 0.5
    signals: Dict[str, Any] = Field(default_factory=dict)  # weakTopics, dueReviews


class PlanDay(BaseModel):
    day: int
    focus: str
    objective: str = ""
    explanationStyle: str = ""
    difficulty: str = ""
    minutes: int
    activities: List[str]


class PlanResponse(BaseModel):
    summary: str
    primaryWeakness: str = ""
    strengths: List[str] = Field(default_factory=list)
    days: List[PlanDay]
    engine: str
