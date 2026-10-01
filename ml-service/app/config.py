"""Runtime configuration, read from environment variables (or ml-service/.env)."""
import os
from pathlib import Path
from typing import Dict, List

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")

MODEL_DIR = BASE_DIR / "models"
RECALL_MODEL_PATH = MODEL_DIR / "recall_xgb.json"
RECALL_METRICS_PATH = MODEL_DIR / "recall_metrics.json"
RISK_MODEL_PATH = MODEL_DIR / "risk_xgb.json"
RISK_METRICS_PATH = MODEL_DIR / "risk_metrics.json"


def env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in ("1", "true", "yes", "on")


AUTO_TRAIN = env_bool("AUTO_TRAIN", True)
SERVICE_KEY = os.getenv("ML_SERVICE_KEY", "").strip()
LLM_TIMEOUT = float(os.getenv("LLM_TIMEOUT_SECONDS", "40"))
# One deadline for the WHOLE provider chain (Groq -> Gemini -> Ollama). Keep it below the Java
# client's plan timeout (skillpulse.ml.plan-timeout-seconds, 60 s) so Java never gives up while
# Python is still working.
LLM_TOTAL_TIMEOUT = float(os.getenv("LLM_TOTAL_TIMEOUT_SECONDS", "45"))
# Study chat: the message router (a small, fast classifier call) gets a short deadline of its own, and a streamed
# reply may run at most this long in total. Keep the stream limit below the Java SSE timeout (120 s).
ROUTER_TIMEOUT = float(os.getenv("CHAT_ROUTER_TIMEOUT_SECONDS", "6"))
STREAM_MAX_SECONDS = float(os.getenv("CHAT_STREAM_MAX_SECONDS", "90"))


def llm_providers() -> List[Dict[str, str]]:
    """Ordered fallback chain: Groq -> Gemini -> Ollama. Only configured ones are used.

    All three expose an OpenAI-compatible chat-completions API, so one client handles them.
    """
    providers: List[Dict[str, str]] = []

    groq_key = os.getenv("GROQ_API_KEY", "").strip()
    if groq_key:
        providers.append({
            "name": "groq",
            "url": os.getenv("GROQ_API_URL", "https://api.groq.com/openai/v1/chat/completions"),
            "model": os.getenv("GROQ_MODEL", "openai/gpt-oss-120b"),
            "fast_model": os.getenv("GROQ_ROUTER_MODEL", "llama-3.1-8b-instant"),
            "key": groq_key,
        })

    gemini_key = os.getenv("GEMINI_API_KEY", "").strip()
    if gemini_key:
        providers.append({
            "name": "gemini",
            "url": os.getenv("GEMINI_API_URL",
                             "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"),
            "model": os.getenv("GEMINI_MODEL", "gemini-2.5-flash"),
            "fast_model": os.getenv("GEMINI_ROUTER_MODEL", os.getenv("GEMINI_MODEL", "gemini-2.5-flash")),
            "key": gemini_key,
        })

    if env_bool("OLLAMA_ENABLED", False):
        providers.append({
            "name": "ollama",
            "url": os.getenv("OLLAMA_URL", "http://localhost:11434/v1/chat/completions"),
            "model": os.getenv("OLLAMA_MODEL", "llama3.1:8b"),
            "fast_model": os.getenv("OLLAMA_ROUTER_MODEL", os.getenv("OLLAMA_MODEL", "llama3.1:8b")),
            "key": "",
        })

    return providers
